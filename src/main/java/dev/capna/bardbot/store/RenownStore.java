package dev.capna.bardbot.store;

import dev.capna.bardbot.model.Standings;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The months that have already been settled.
 *
 * <p>Two jobs, and they are the same job. It is where a finished month's result is kept so that
 * later corrections cannot rewrite it, and it is how the bot knows a month has already been rolled,
 * so a restart on the first of the month cannot post the standings twice.
 *
 * <p>Nothing here is ever removed. A year of months is a few kilobytes, and the history is the only
 * record of who won what.
 */
public final class RenownStore {

    private static final Logger LOG = LoggerFactory.getLogger(RenownStore.class);

    private final Path file;
    private final List<Standings> archived = new ArrayList<>();
    private boolean loaded;

    public RenownStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** Whether this month has already been settled, which is what makes rolling safe to retry. */
    public synchronized boolean hasRolled(YearMonth month) {
        load();
        return archived.stream().anyMatch(standings -> standings.month().equals(month));
    }

    /** The most recently settled month, which is what {@code /house winner} answers from. */
    public synchronized Optional<Standings> latest() {
        load();
        return archived.stream().max(Comparator.comparing(Standings::month));
    }

    public synchronized Optional<Standings> forMonth(YearMonth month) {
        load();
        return archived.stream().filter(s -> s.month().equals(month)).findFirst();
    }

    /**
     * Settles a month.
     *
     * <p>Writing this is what makes the roll idempotent, so it happens before the standings are
     * posted. A crash between the two costs an announcement, which someone can ask for; a crash the
     * other way around would post the same result on every restart for a day.
     */
    public synchronized void archive(Standings standings) throws IOException {
        load();
        if (hasRolled(standings.month())) {
            LOG.warn("{} has already been settled; leaving the archived result alone",
                    standings.month());
            return;
        }
        archived.add(standings);
        try {
            persist();
        } catch (IOException e) {
            archived.remove(archived.size() - 1);
            throw e;
        }
        LOG.info("Settled {} with {} house(s) placed", standings.month(), standings.places().size());
    }

    private void persist() throws IOException {
        JSONArray root = new JSONArray();
        for (Standings standings : archived) {
            JSONArray places = new JSONArray();
            for (Standings.Place place : standings.places()) {
                places.put(new JSONObject()
                        .put("house", place.houseId())
                        .put("name", place.houseName())
                        .put("renown", place.renown()));
            }
            root.put(new JSONObject()
                    .put("month", standings.month().toString())
                    .put("archived", standings.archived().toString())
                    .put("places", places));
        }
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
            JSONArray root = new JSONArray(Files.readString(file, StandardCharsets.UTF_8));
            for (int i = 0; i < root.length(); i++) {
                JSONObject json = root.getJSONObject(i);
                List<Standings.Place> places = new ArrayList<>();
                JSONArray array = json.optJSONArray("places");
                for (int p = 0; array != null && p < array.length(); p++) {
                    JSONObject place = array.getJSONObject(p);
                    places.add(new Standings.Place(
                            place.getString("house"),
                            place.optString("name", place.getString("house")),
                            place.getInt("renown")));
                }
                archived.add(new Standings(
                        YearMonth.parse(json.getString("month")),
                        places,
                        Instant.parse(json.getString("archived"))));
            }
            LOG.info("Read {} settled month(s) from {}", archived.size(), file);
        } catch (IOException | JSONException | java.time.format.DateTimeParseException e) {
            // This file is what stops a month being rolled twice. Continuing without it would
            // repost a month that has already been settled, so it fails loudly instead.
            loaded = false;
            archived.clear();
            throw new IllegalStateException("Could not read the renown archive at " + file
                    + ". Nothing has been changed; fix or restore the file and restart.", e);
        }
    }
}
