package dev.capna.bardbot.store;

import dev.capna.bardbot.model.Award;
import dev.capna.bardbot.model.Virtue;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Every award ever made, and every figure derived from them.
 *
 * <p>This is the only place virtue is stored. Scores, totals, leaderboards and house renown are all
 * sums over this list rather than counters kept beside it, which is what makes them incapable of
 * disagreeing with each other. Correcting a mistaken award corrects every figure it touched, with
 * nobody having to remember which.
 *
 * <p>Append only. Nothing is edited and nothing is removed, so the log is a record of what happened
 * rather than of what somebody would prefer had happened.
 *
 * <p>Held in memory and rewritten whole on every append, like every other store here. A guild
 * producing a hundred awards a week takes years to reach a file worth reading incrementally, and
 * the alternative is a second file format and a second set of failure modes to get right.
 */
public final class AwardLog {

    private static final Logger LOG = LoggerFactory.getLogger(AwardLog.class);

    private final Path file;
    private final List<Award> awards = new ArrayList<>();
    private boolean loaded;

    public AwardLog(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /**
     * Records an award.
     *
     * <p>Persists before returning, so a caller never reports a new score that a restart would
     * undo. The award is only added to the in-memory list once the write has succeeded, for the
     * same reason.
     */
    public synchronized void append(Award award) throws IOException {
        load();
        awards.add(award);
        try {
            persist();
        } catch (IOException e) {
            awards.remove(awards.size() - 1);
            throw e;
        }
    }

    /** One Bard's four scores. Absent virtues are zero rather than missing. */
    public synchronized Map<Virtue, Integer> scores(String userId) {
        load();
        Map<Virtue, Integer> scores = new EnumMap<>(Virtue.class);
        for (Virtue virtue : Virtue.values()) {
            scores.put(virtue, 0);
        }
        for (Award award : awards) {
            if (award.recipientId().equals(userId)) {
                scores.merge(award.virtue(), award.amount(), Integer::sum);
            }
        }
        return scores;
    }

    public synchronized int score(String userId, Virtue virtue) {
        return scores(userId).getOrDefault(virtue, 0);
    }

    /** The sum of all four. Never stored, so it cannot drift away from the parts it is made of. */
    public synchronized int total(String userId) {
        return scores(userId).values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Every Bard who has ever been awarded anything, with their score in one virtue, or their total
     * when no virtue is given.
     *
     * <p>Only Bards with a record appear. Somebody who has never been awarded anything has a score
     * of zero, and a leaderboard listing every member of the server at zero is not a leaderboard.
     */
    public synchronized Map<String, Integer> standings(Optional<Virtue> virtue) {
        load();
        Map<String, Integer> byUser = new HashMap<>();
        for (Award award : awards) {
            if (virtue.isEmpty() || award.virtue() == virtue.get()) {
                byUser.merge(award.recipientId(), award.amount(), Integer::sum);
            }
        }
        return byUser;
    }

    /**
     * What each house earned within a month, by the house that held the Bard at the time.
     *
     * @param month the month in the guild's own zone, which is what decides where its boundaries
     *              fall. An award at half past eleven on the 31st belongs to the month the guild
     *              thinks it does, not the one UTC does.
     */
    public synchronized Map<String, Integer> renown(YearMonth month, ZoneId zone) {
        load();
        Map<String, Integer> byHouse = new HashMap<>();
        for (Award award : awards) {
            if (award.houseId().isEmpty()) {
                continue;
            }
            if (YearMonth.from(award.at().atZone(zone)).equals(month)) {
                byHouse.merge(award.houseId().get(), award.amount(), Integer::sum);
            }
        }
        return byHouse;
    }

    /** What each house has earned in its entire existence. */
    public synchronized Map<String, Integer> renownAllTime() {
        load();
        Map<String, Integer> byHouse = new HashMap<>();
        for (Award award : awards) {
            award.houseId().ifPresent(house -> byHouse.merge(house, award.amount(), Integer::sum));
        }
        return byHouse;
    }

    /**
     * When the first award was made, or empty when none ever has been.
     *
     * <p>Used to decide how far back the monthly roll has to look. Without it, a bot that was down
     * for two months would settle only the most recent one and leave a hole in the record.
     */
    public synchronized Optional<Instant> earliest() {
        load();
        return awards.stream().map(Award::at).min(Instant::compareTo);
    }

    /** One Bard's awards, most recent first, for when a number is being argued about. */
    public synchronized List<Award> history(String userId, int limit) {
        load();
        return awards.stream()
                .filter(award -> award.recipientId().equals(userId))
                .sorted((a, b) -> b.at().compareTo(a.at()))
                .limit(limit)
                .toList();
    }

    private void persist() throws IOException {
        JSONArray array = new JSONArray();
        for (Award award : awards) {
            JSONObject json = new JSONObject()
                    .put("recipient", award.recipientId())
                    .put("granter", award.granterId())
                    .put("virtue", award.virtue().key())
                    .put("amount", award.amount())
                    .put("at", award.at().toString());
            award.reason().ifPresent(reason -> json.put("reason", reason));
            award.houseId().ifPresent(house -> json.put("house", house));
            array.put(json);
        }
        AtomicFiles.writeString(file, array.toString(2));
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
            JSONArray array = new JSONArray(Files.readString(file, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.getJSONObject(i);
                Optional<Virtue> virtue = Virtue.byKey(json.optString("virtue", ""));
                if (virtue.isEmpty()) {
                    // A virtue this version does not know. Dropping the row would quietly change
                    // somebody's score, so it is kept out of the sums and said out loud instead.
                    LOG.warn("Ignoring award with unknown virtue: {}", json.optString("virtue"));
                    continue;
                }
                awards.add(new Award(
                        json.getString("recipient"),
                        json.optString("granter", ""),
                        virtue.get(),
                        json.getInt("amount"),
                        optional(json, "reason"),
                        optional(json, "house"),
                        Instant.parse(json.getString("at"))));
            }
            LOG.info("Read {} award(s) from {}", awards.size(), file);
        } catch (IOException | JSONException | java.time.format.DateTimeParseException e) {
            // Everyone's virtue is in this file. Starting with an empty one would show the whole
            // server a score of zero and then overwrite the real thing on the next award, so the
            // log stays unreadable and every command that needs it says so.
            loaded = false;
            throw new IllegalStateException("Could not read the award log at " + file
                    + ". Nothing has been changed; fix or restore the file and restart.", e);
        }
    }

    private static Optional<String> optional(JSONObject json, String key) {
        String value = json.optString(key, "");
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
