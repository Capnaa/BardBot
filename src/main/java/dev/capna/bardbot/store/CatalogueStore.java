package dev.capna.bardbot.store;

import dev.capna.bardbot.model.Catalogue;
import dev.capna.bardbot.model.Goal;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The virtue goals and the government titles, kept in one editable file.
 *
 * <p>Both halves are the government's to change, and neither is worth a deploy. The file is meant
 * to be edited by hand as well as by the bot, so it is written indented and read forgivingly: a
 * malformed goal is skipped and reported rather than taken as a reason to refuse to start.
 *
 * <p>Goals are held sorted by threshold, so every list of them reads in the order a Bard will meet
 * them however the file was written.
 */
public final class CatalogueStore {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogueStore.class);

    private final Path file;
    private volatile Catalogue current;

    public CatalogueStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        this.current = load(file);
    }

    public Catalogue current() {
        return current;
    }

    /** The goals for one virtue, or the goals measured against the total when none is given. */
    public List<Goal> goals(Optional<Virtue> virtue) {
        return current.goals().stream()
                .filter(goal -> goal.virtue().equals(virtue))
                .toList();
    }

    /** Re-reads the file, for when it has been edited by hand and should take effect now. */
    public synchronized void reload() {
        current = load(file);
    }

    /**
     * Adds a goal, or moves the title on one that already exists at that threshold.
     *
     * <p>Replacing rather than refusing, because "40 Honor is now called something else" is a
     * normal thing for a government to decide and should not require deleting the tier first.
     */
    public synchronized void addGoal(Goal goal) throws IOException {
        List<Goal> goals = new ArrayList<>(current.goals().stream()
                .filter(existing -> !sameTier(existing, goal))
                .toList());
        goals.add(goal);
        goals.sort(Comparator.comparingInt(Goal::threshold));
        persist(new Catalogue(goals, current.governmentTitles()));
    }

    /** @return false when there was no goal at that threshold, so the caller can say so */
    public synchronized boolean removeGoal(Optional<Virtue> virtue, int threshold) throws IOException {
        List<Goal> goals = current.goals().stream()
                .filter(goal -> !(goal.virtue().equals(virtue) && goal.threshold() == threshold))
                .toList();
        if (goals.size() == current.goals().size()) {
            return false;
        }
        persist(new Catalogue(goals, current.governmentTitles()));
        return true;
    }

    /** Two goals are the same tier when they measure the same thing at the same score. */
    private static boolean sameTier(Goal one, Goal other) {
        return one.virtue().equals(other.virtue()) && one.threshold() == other.threshold();
    }

    private void persist(Catalogue updated) throws IOException {
        JSONArray goals = new JSONArray();
        for (Goal goal : updated.goals()) {
            JSONObject json = new JSONObject()
                    .put("virtue", goal.virtue().map(Virtue::key).orElse("total"))
                    .put("threshold", goal.threshold());
            goal.title().ifPresent(title -> json.put("title", title));
            goal.note().ifPresent(note -> json.put("note", note));
            goals.put(json);
        }
        AtomicFiles.writeString(file, new JSONObject()
                .put("goals", goals)
                .put("governmentTitles", new JSONArray(updated.governmentTitles()))
                .toString(2));
        current = updated;
    }

    private static Catalogue load(Path file) {
        if (!Files.isReadable(file)) {
            // A guild that has not written any goals yet. Every command that lists them says so
            // rather than showing an empty table with no explanation.
            LOG.info("No title catalogue at {}; starting with none", file);
            return Catalogue.empty();
        }
        try {
            JSONObject root = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));

            List<Goal> goals = new ArrayList<>();
            JSONArray array = root.optJSONArray("goals");
            for (int i = 0; array != null && i < array.length(); i++) {
                JSONObject json = array.getJSONObject(i);
                String virtueKey = json.optString("virtue", "");
                Optional<Virtue> virtue = virtueKey.isEmpty() || virtueKey.equalsIgnoreCase("total")
                        ? Optional.empty()
                        : Virtue.byKey(virtueKey);
                if (!virtueKey.isEmpty() && !virtueKey.equalsIgnoreCase("total") && virtue.isEmpty()) {
                    LOG.warn("Ignoring goal for unknown virtue: {}", virtueKey);
                    continue;
                }
                if (!json.has("threshold")) {
                    LOG.warn("Ignoring goal with no threshold: {}", json);
                    continue;
                }
                goals.add(new Goal(virtue, json.getInt("threshold"),
                        optional(json, "title"), optional(json, "note")));
            }
            goals.sort(Comparator.comparingInt(Goal::threshold));

            List<String> government = new ArrayList<>();
            JSONArray titles = root.optJSONArray("governmentTitles");
            for (int i = 0; titles != null && i < titles.length(); i++) {
                government.add(titles.getString(i));
            }

            LOG.info("Read {} goal(s) and {} government title(s) from {}",
                    goals.size(), government.size(), file);
            return new Catalogue(goals, government);
        } catch (IOException | JSONException e) {
            // Unlike virtue, nothing here can be silently overwritten by carrying on: the bot only
            // ever reads this file. Losing it costs the goal list until it is fixed, which is worth
            // less than refusing to start.
            LOG.error("Could not read the title catalogue at {}; continuing with none", file, e);
            return Catalogue.empty();
        }
    }

    private static Optional<String> optional(JSONObject json, String key) {
        String value = json.optString(key, "");
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
