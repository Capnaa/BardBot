package dev.capna.bardbot.store;

import dev.capna.bardbot.model.WritStatus;
import dev.capna.bardbot.model.WritTask;
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
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Writs: who holds how many, and every task one has been spent on.
 *
 * <p>A writ is a token that lets a tribunal member hand out work. Everyone on the tribunal gets one
 * on the first of the month and can hold at most {@link #MAX_HELD}, so they cannot pile up and the
 * pressure is to spend them. Spending one either burns it on a public task in the forum, or locks
 * it into a task on another tribunal member, who gets it when they finish.
 *
 * <p>The count is stored rather than summed, unlike virtue, because a writ is not a record of
 * anything: it is permission to make work, and its history is the tasks it was spent on.
 *
 * <p>Nothing about the cap is enforced here against who is on the tribunal. That is a Discord
 * role, checked by the commands; this store only knows user IDs.
 */
public final class WritStore {

    private static final Logger LOG = LoggerFactory.getLogger(WritStore.class);

    /**
     * Two, with one arriving a month.
     *
     * <p>Tight on purpose. A member who does not spend for a month is full and the next grant is
     * wasted, which is the pressure that stops writs being sat on. It also bounds the total: this
     * many per member, plus whatever is locked in open tasks.
     */
    public static final int MAX_HELD = 2;

    private final Path file;
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final List<WritTask> tasks = new ArrayList<>();
    private Optional<YearMonth> lastGranted = Optional.empty();
    private int nextId = 1;
    private boolean loaded;

    public WritStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** How many a member is holding. Nobody is missing; they hold none. */
    public synchronized int held(String userId) {
        load();
        return counts.getOrDefault(userId, 0);
    }

    /**
     * The open tasks on a member, oldest first.
     *
     * <p>This order is what the numbers in {@code /tribunal writ view} mean, so it is stated once
     * here and every command that takes a number reads the same list.
     */
    public synchronized List<WritTask> openOn(String userId) {
        load();
        return tasks.stream()
                .filter(task -> task.isOpen() && task.servedOn().equals(userId))
                .sorted(Comparator.comparing(WritTask::createdAt))
                .toList();
    }

    /** The open tasks a member has served on others, oldest first. Numbered the same way. */
    public synchronized List<WritTask> openBy(String userId) {
        load();
        return tasks.stream()
                .filter(task -> task.isOpen() && task.servedBy().equals(userId))
                .sorted(Comparator.comparing(WritTask::createdAt))
                .toList();
    }

    /**
     * Spends a writ on a task for another tribunal member.
     *
     * <p>The writ leaves the server's count now and is not anybody's until the task ends. That is
     * what makes serving somebody cost something: the member is down a writ for as long as the
     * work is outstanding.
     *
     * @throws WritRejected if they have none to spend, or are serving themselves
     */
    public synchronized WritTask serve(String servedBy, String servedOn, String task,
                                       LocalDate dueDate) throws IOException, WritRejected {
        load();
        if (servedBy.equals(servedOn)) {
            throw new WritRejected("You cannot serve a writ on yourself.");
        }
        String trimmed = task.strip();
        if (trimmed.isEmpty() || trimmed.length() > WritTask.MAX_TASK) {
            throw new WritRejected("The task has to be between 1 and "
                    + WritTask.MAX_TASK + " characters.");
        }
        requireOne(servedBy);

        WritTask served = new WritTask(nextId, servedBy, servedOn, trimmed, dueDate,
                Instant.now(), WritStatus.OPEN, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
        counts.merge(servedBy, -1, Integer::sum);
        tasks.add(served);
        nextId++;
        try {
            persist();
        } catch (IOException e) {
            nextId--;
            tasks.remove(tasks.size() - 1);
            counts.merge(servedBy, 1, Integer::sum);
            throw e;
        }
        LOG.info("{} served writ {} on {}, due {}", servedBy, served.id(), servedOn, dueDate);
        return served;
    }

    /**
     * Spends a writ on a public task.
     *
     * <p>The writ is burned. Nothing is recorded here because the task lives in the forum as a
     * post, and the only thing this store needs to know is that the writ is gone.
     *
     * @throws WritRejected if they have none to spend
     */
    public synchronized void spendOnPublicTask(String userId) throws IOException, WritRejected {
        load();
        requireOne(userId);
        counts.merge(userId, -1, Integer::sum);
        try {
            persist();
        } catch (IOException e) {
            counts.merge(userId, 1, Integer::sum);
            throw e;
        }
        LOG.info("{} spent a writ on a public task", userId);
    }

    /**
     * Marks one of a member's own tasks done. The writ becomes theirs.
     *
     * <p>Or is burned, if they are already holding {@link #MAX_HELD}. Bouncing it back to whoever
     * served it would make serving somebody who is full a way to hand out work for nothing.
     *
     * @param number the position in {@link #openOn}, counting from one
     * @throws WritRejected if there is no task at that number, or nothing was submitted
     */
    public synchronized WritTask complete(String userId, int number, Optional<String> coords,
                                          Optional<String> link) throws IOException, WritRejected {
        load();
        if (coords.isEmpty() && link.isEmpty()) {
            throw new WritRejected("Fill in coords, a link, or both.");
        }
        WritTask open = numbered(openOn(userId), number);
        return close(open, open.closed(WritStatus.COMPLETED, Instant.now(), coords, link,
                Optional.empty()), userId);
    }

    /**
     * Takes back a task the member served that has not been done. The writ returns to them.
     *
     * <p>No penalty. This exists so a task that somebody never gets round to does not eat a writ
     * forever. It is still capped: a member who has since filled up burns it instead.
     *
     * @param number the position in {@link #openBy}, counting from one
     */
    public synchronized WritTask cancel(String userId, int number) throws IOException, WritRejected {
        load();
        WritTask open = numbered(openBy(userId), number);
        return close(open, open.closed(WritStatus.CANCELLED, Instant.now(), Optional.empty(),
                Optional.empty(), Optional.empty()), userId);
    }

    /**
     * Throws out a task on somebody as unreasonable. The writ goes back to whoever served it.
     *
     * <p>Back rather than burned, so it can be spent on something more sensible. Capped like every
     * other return.
     *
     * @param number the position in {@link #openOn} for {@code servedOn}, counting from one
     */
    public synchronized WritTask strike(String struckBy, String servedOn, int number)
            throws IOException, WritRejected {
        load();
        WritTask open = numbered(openOn(servedOn), number);
        return close(open, open.closed(WritStatus.STRUCK, Instant.now(), Optional.empty(),
                Optional.empty(), Optional.of(struckBy)), open.servedBy());
    }

    /**
     * Hands out the month's writ, once.
     *
     * <p>Everyone named who is holding fewer than {@link #MAX_HELD} gets one. Everyone at the cap
     * gets nothing, which is deliberate: the grant is lost, and that is the pressure to spend.
     *
     * <p>Remembers the month, so a restart on the first cannot hand out a second one. The names
     * are whoever holds a tribunal role at the time, which the caller has to look up: this store
     * knows nothing about Discord.
     *
     * @return whether anything was done, or the month had already been granted
     */
    public synchronized boolean grantMonthly(YearMonth month, Collection<String> tribunalIds)
            throws IOException {
        load();
        if (hasGranted(month)) {
            return false;
        }
        Map<String, Integer> previous = new LinkedHashMap<>(counts);
        Optional<YearMonth> previousMonth = lastGranted;
        int granted = 0;
        for (String userId : tribunalIds) {
            if (held(userId) < MAX_HELD) {
                counts.merge(userId, 1, Integer::sum);
                granted++;
            }
        }
        lastGranted = Optional.of(month);
        try {
            persist();
        } catch (IOException e) {
            counts.clear();
            counts.putAll(previous);
            lastGranted = previousMonth;
            throw e;
        }
        LOG.info("Granted the writ for {} to {} of {} tribunal member(s)",
                month, granted, tribunalIds.size());
        return true;
    }

    /** Whether the month's writ has gone out, which is what makes granting safe to retry. */
    public synchronized boolean hasGranted(YearMonth month) {
        load();
        return lastGranted.filter(last -> !last.isBefore(month)).isPresent();
    }

    /**
     * Moves a count by one, within the cap. For seeding at launch and fixing mistakes.
     *
     * @return the count afterwards
     */
    public synchronized int adjust(String userId, int by) throws IOException {
        load();
        int before = held(userId);
        int after = Math.max(0, Math.min(MAX_HELD, before + by));
        if (after == before) {
            return after;
        }
        counts.put(userId, after);
        try {
            persist();
        } catch (IOException e) {
            counts.put(userId, before);
            throw e;
        }
        LOG.info("{} now holds {} writ(s)", userId, after);
        return after;
    }

    private void requireOne(String userId) throws WritRejected {
        if (held(userId) <= 0) {
            throw new WritRejected("You have no writs.");
        }
    }

    private static WritTask numbered(List<WritTask> open, int number) throws WritRejected {
        if (number < 1 || number > open.size()) {
            throw new WritRejected(open.isEmpty()
                    ? "There are no open writs there."
                    : "There is no writ " + number + ". The list goes up to " + open.size() + ".");
        }
        return open.get(number - 1);
    }

    /**
     * Replaces an open task with its closed form and gives the writ to somebody.
     *
     * <p>Every way a task ends is this: the record changes, and one person's count goes up by one
     * unless they are full. Doing it in one place is what keeps complete, cancel and strike from
     * disagreeing about the cap.
     */
    private WritTask close(WritTask open, WritTask closed, String writGoesTo) throws IOException {
        int index = tasks.indexOf(open);
        int before = held(writGoesTo);
        tasks.set(index, closed);
        if (before < MAX_HELD) {
            counts.put(writGoesTo, before + 1);
        }
        try {
            persist();
        } catch (IOException e) {
            tasks.set(index, open);
            counts.put(writGoesTo, before);
            throw e;
        }
        if (before >= MAX_HELD) {
            LOG.info("Writ {} {}; {} was already holding {}, so it was burned",
                    closed.id(), closed.status().name().toLowerCase(java.util.Locale.ROOT),
                    writGoesTo, MAX_HELD);
        } else {
            LOG.info("Writ {} {}; {} now holds {}", closed.id(),
                    closed.status().name().toLowerCase(java.util.Locale.ROOT),
                    writGoesTo, before + 1);
        }
        return closed;
    }

    private void persist() throws IOException {
        JSONObject held = new JSONObject();
        counts.forEach((userId, count) -> {
            if (count > 0) {
                held.put(userId, count);
            }
        });

        JSONArray array = new JSONArray();
        for (WritTask task : tasks) {
            JSONObject json = new JSONObject()
                    .put("id", task.id())
                    .put("servedBy", task.servedBy())
                    .put("servedOn", task.servedOn())
                    .put("task", task.task())
                    .put("due", task.dueDate().toString())
                    .put("createdAt", task.createdAt().toString())
                    .put("status", task.status().name());
            task.closedAt().ifPresent(at -> json.put("closedAt", at.toString()));
            task.coords().ifPresent(coords -> json.put("coords", coords));
            task.link().ifPresent(link -> json.put("link", link));
            task.struckBy().ifPresent(by -> json.put("struckBy", by));
            array.put(json);
        }

        JSONObject root = new JSONObject()
                .put("held", held)
                .put("nextId", nextId)
                .put("tasks", array);
        lastGranted.ifPresent(month -> root.put("lastGranted", month.toString()));
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

            JSONObject held = root.optJSONObject("held");
            if (held != null) {
                for (String userId : held.keySet()) {
                    counts.put(userId, held.getInt(userId));
                }
            }
            nextId = root.optInt("nextId", 1);
            String granted = root.optString("lastGranted", "");
            lastGranted = granted.isEmpty() ? Optional.empty()
                    : Optional.of(YearMonth.parse(granted));

            JSONArray array = root.optJSONArray("tasks");
            for (int i = 0; array != null && i < array.length(); i++) {
                JSONObject json = array.getJSONObject(i);
                tasks.add(new WritTask(
                        json.getInt("id"),
                        json.getString("servedBy"),
                        json.getString("servedOn"),
                        json.getString("task"),
                        LocalDate.parse(json.getString("due")),
                        Instant.parse(json.getString("createdAt")),
                        WritStatus.valueOf(json.getString("status")),
                        optional(json, "closedAt").map(Instant::parse),
                        optional(json, "coords"),
                        optional(json, "link"),
                        optional(json, "struckBy")));
            }
            LOG.info("Read {} writ task(s) from {}", tasks.size(), file);
        } catch (IOException | JSONException | IllegalArgumentException
                 | java.time.format.DateTimeParseException e) {
            // Counts and open tasks both live here. Starting empty would hand everyone zero writs
            // and lose every task in flight, then overwrite the real file on the next change.
            loaded = false;
            counts.clear();
            tasks.clear();
            throw new IllegalStateException("Could not read writs at " + file
                    + ". Nothing has been changed; fix or restore the file and restart.", e);
        }
    }

    private static Optional<String> optional(JSONObject json, String key) {
        String value = json.optString(key, "");
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /** A refusal a person should read, as opposed to a fault the console should. */
    public static class WritRejected extends Exception {
        public WritRejected(String message) {
            super(message);
        }
    }
}
