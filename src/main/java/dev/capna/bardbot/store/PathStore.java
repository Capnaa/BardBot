package dev.capna.bardbot.store;

import dev.capna.bardbot.model.PathReward;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What the Path of Virtue has to remember.
 *
 * <p>Very little. Progress is a sum over the award log, like everything else, so it is not here.
 * What is here is the part that cannot be recomputed: how many vouchers each Bard is holding,
 * which lines they have already been handed a voucher for this month so a correction that dips
 * them below and back does not pay twice, and which month the roll has been done for.
 *
 * <p>Vouchers do not expire. A voucher is the one thing a Pilgrim takes with them when the month
 * ends and the role comes off, so it is kept without a month against it.
 */
public final class PathStore {

    private static final Logger LOG = LoggerFactory.getLogger(PathStore.class);

    private final Path file;
    private final Map<String, Integer> vouchers = new LinkedHashMap<>();
    private final Map<String, Set<PathReward>> granted = new LinkedHashMap<>();
    private Optional<YearMonth> grantedMonth = Optional.empty();
    private Optional<YearMonth> lastRolled = Optional.empty();
    private boolean loaded;

    public PathStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** Vouchers a Bard is holding and has not claimed. */
    public synchronized int vouchers(String userId) {
        load();
        return vouchers.getOrDefault(userId, 0);
    }

    /** The lines somebody has already been paid for this month. Empty once the month turns. */
    public synchronized Set<PathReward> granted(String userId, YearMonth month) {
        load();
        if (grantedMonth.filter(month::equals).isEmpty()) {
            return EnumSet.noneOf(PathReward.class);
        }
        return EnumSet.copyOf(granted.getOrDefault(userId, EnumSet.noneOf(PathReward.class)));
    }

    /**
     * Records that somebody reached these lines, and pays out the vouchers among them.
     *
     * <p>Lines already paid this month are skipped rather than paid again. A month older than the
     * one recorded wipes the slate: nothing from a finished month is owed in the next.
     *
     * @return the lines that were new, which is what gets announced
     */
    public synchronized List<PathReward> grant(String userId, YearMonth month,
                                               List<PathReward> reached) throws IOException {
        load();
        if (grantedMonth.filter(month::equals).isEmpty()) {
            granted.clear();
            grantedMonth = Optional.of(month);
        }
        Set<PathReward> already = granted.computeIfAbsent(userId,
                id -> EnumSet.noneOf(PathReward.class));
        List<PathReward> fresh = new ArrayList<>();
        for (PathReward reward : reached) {
            if (already.add(reward)) {
                fresh.add(reward);
            }
        }
        if (fresh.isEmpty()) {
            return fresh;
        }
        int paid = (int) fresh.stream().filter(PathReward::isVoucher).count();
        int before = vouchers(userId);
        vouchers.put(userId, before + paid);
        try {
            persist();
        } catch (IOException e) {
            fresh.forEach(already::remove);
            vouchers.put(userId, before);
            throw e;
        }
        if (paid > 0) {
            LOG.info("{} reached {} and now holds {} voucher(s)", userId, fresh, before + paid);
        }
        return fresh;
    }

    /**
     * Spends one voucher.
     *
     * @throws PathRejected if they have none
     */
    public synchronized void claim(String userId) throws IOException, PathRejected {
        load();
        int before = vouchers(userId);
        if (before <= 0) {
            throw new PathRejected("You have no vouchers.");
        }
        vouchers.put(userId, before - 1);
        try {
            persist();
        } catch (IOException e) {
            vouchers.put(userId, before);
            throw e;
        }
        LOG.info("{} claimed a voucher and holds {}", userId, before - 1);
    }

    /** Whether the month has been rolled, which is what makes rolling safe to retry. */
    public synchronized boolean hasRolled(YearMonth month) {
        load();
        return lastRolled.filter(last -> !last.isBefore(month)).isPresent();
    }

    /** The last month rolled, or empty if the Path has never rolled one. */
    public synchronized Optional<YearMonth> lastRolled() {
        load();
        return lastRolled;
    }

    /**
     * Records that a month has been rolled.
     *
     * <p>Written before the summary is posted and the roles come off, for the same reason the
     * renown archive is: a crash between the two costs an announcement somebody can ask for, where
     * the other order would strip the role from everybody who bought in that morning.
     */
    public synchronized void markRolled(YearMonth month) throws IOException {
        load();
        Optional<YearMonth> previous = lastRolled;
        lastRolled = Optional.of(month);
        try {
            persist();
        } catch (IOException e) {
            lastRolled = previous;
            throw e;
        }
    }

    private void persist() throws IOException {
        JSONObject held = new JSONObject();
        vouchers.forEach((userId, count) -> {
            if (count > 0) {
                held.put(userId, count);
            }
        });

        JSONObject paid = new JSONObject();
        granted.forEach((userId, rewards) -> {
            if (!rewards.isEmpty()) {
                paid.put(userId, new JSONArray(rewards.stream().map(Enum::name).toList()));
            }
        });

        JSONObject root = new JSONObject()
                .put("vouchers", held)
                .put("granted", paid);
        grantedMonth.ifPresent(month -> root.put("grantedMonth", month.toString()));
        lastRolled.ifPresent(month -> root.put("lastRolled", month.toString()));
        AtomicFiles.writeString(file, root.toString(2));
    }

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isReadable(file)) {
            return;
        }
        try {
            JSONObject root = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));

            JSONObject held = root.optJSONObject("vouchers");
            if (held != null) {
                for (String userId : held.keySet()) {
                    vouchers.put(userId, held.getInt(userId));
                }
            }

            String month = root.optString("grantedMonth", "");
            grantedMonth = month.isEmpty() ? Optional.empty() : Optional.of(YearMonth.parse(month));
            JSONObject paid = root.optJSONObject("granted");
            if (paid != null) {
                for (String userId : paid.keySet()) {
                    Set<PathReward> rewards = EnumSet.noneOf(PathReward.class);
                    JSONArray names = paid.getJSONArray(userId);
                    for (int i = 0; i < names.length(); i++) {
                        try {
                            rewards.add(PathReward.valueOf(names.getString(i)));
                        } catch (IllegalArgumentException unknown) {
                            // A line since removed from the ladder. Nothing to pay for it.
                            LOG.warn("Ignoring unknown Path reward: {}", names.getString(i));
                        }
                    }
                    granted.put(userId, rewards);
                }
            }

            String rolled = root.optString("lastRolled", "");
            lastRolled = rolled.isEmpty() ? Optional.empty() : Optional.of(YearMonth.parse(rolled));
            LOG.info("Read {} voucher holder(s) from {}", vouchers.size(), file);
        } catch (IOException | JSONException | java.time.format.DateTimeParseException e) {
            // Vouchers are earned and this is the only record of them. Starting empty would take
            // them away and then overwrite the real file on the next claim.
            loaded = false;
            vouchers.clear();
            granted.clear();
            throw new IllegalStateException("Could not read the Path at " + file
                    + ". Nothing has been changed; fix or restore the file and restart.", e);
        }
    }

    /** A refusal a person should read, as opposed to a fault the console should. */
    public static class PathRejected extends Exception {
        public PathRejected(String message) {
            super(message);
        }
    }
}
