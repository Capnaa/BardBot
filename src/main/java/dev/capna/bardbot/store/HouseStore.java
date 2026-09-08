package dev.capna.bardbot.store;

import dev.capna.bardbot.model.House;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The noble houses, who is in them, and the titles they hand out.
 *
 * <p>Houses are keyed by a slug derived from the name once, at creation. The name can then be
 * changed without orphaning the memberships and awards that point at the house, which is the
 * failure that would otherwise take a month's renown with it.
 *
 * <p>Renown is not here. It is a sum over the award log, which is what keeps a house's figure and
 * its members' scores from ever disagreeing.
 */
public final class HouseStore {

    private static final Logger LOG = LoggerFactory.getLogger(HouseStore.class);

    /** Enough for a house to be found by name in a list without an autocomplete having to page. */
    public static final int MAX_HOUSES = 100;

    private final Path file;
    private final Map<String, House> byId = new LinkedHashMap<>();
    private boolean loaded;

    public HouseStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public synchronized List<House> all() {
        load();
        return List.copyOf(byId.values());
    }

    public synchronized Optional<House> byId(String id) {
        load();
        return Optional.ofNullable(byId.get(id.toLowerCase(Locale.ROOT)));
    }

    /** Matched case-insensitively, because nobody types a house's capitalisation the same way twice. */
    public synchronized Optional<House> byName(String name) {
        load();
        return byId.values().stream()
                .filter(house -> house.name().equalsIgnoreCase(name.strip()))
                .findFirst();
    }

    /** The house holding a Bard, if any. A Bard belongs to at most one. */
    public synchronized Optional<House> holding(String userId) {
        load();
        return byId.values().stream().filter(house -> house.holds(userId)).findFirst();
    }

    /**
     * Creates a house.
     *
     * @throws HouseRejected if the name is taken or unusable, or the roll is full. Rejections are
     *                       distinct from failures: the caller reports them to whoever asked rather
     *                       than logging them as faults.
     */
    public synchronized House add(String name) throws IOException, HouseRejected {
        load();
        String trimmed = name.strip();
        if (trimmed.isEmpty() || trimmed.length() > House.MAX_NAME) {
            throw new HouseRejected("A house name must be between 1 and "
                    + House.MAX_NAME + " characters.");
        }
        if (byName(trimmed).isPresent()) {
            throw new HouseRejected("There is already a house called " + trimmed + ".");
        }
        if (byId.size() >= MAX_HOUSES) {
            throw new HouseRejected("There are already " + MAX_HOUSES
                    + " houses. Remove one before adding another.");
        }
        String id = slug(trimmed);
        if (id.isEmpty()) {
            throw new HouseRejected("A house name needs at least one letter or number in it.");
        }
        if (byId.containsKey(id)) {
            throw new HouseRejected("That name is too close to an existing house's to tell apart.");
        }

        House house = new House(id, trimmed, Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Set.of(), Set.of(), List.of(), Map.of());
        byId.put(id, house);
        persistOrRollBack(id, null);
        return house;
    }

    /**
     * Removes a house.
     *
     * <p>Its awards stay in the log, so all-time renown for a house that no longer exists is still
     * answerable. What goes is the house, its members and its titles.
     */
    public synchronized boolean remove(String id) throws IOException {
        load();
        House removed = byId.remove(id.toLowerCase(Locale.ROOT));
        if (removed == null) {
            return false;
        }
        persistOrRollBack(removed.id(), removed);
        LOG.info("Removed house {}", removed.name());
        return true;
    }

    /** Applies a change to one house and persists it. Every mutation goes through here. */
    public synchronized House update(String id, UnaryOperator<House> change) throws IOException {
        load();
        String key = id.toLowerCase(Locale.ROOT);
        House previous = byId.get(key);
        if (previous == null) {
            throw new IllegalArgumentException("No house with id " + id);
        }
        House updated = change.apply(previous);
        byId.put(key, updated);
        persistOrRollBack(key, previous);
        return updated;
    }

    /**
     * A stable key derived from the name, once.
     *
     * <p>Lowercase, with anything that is not a letter or a number becoming a hyphen. The house's
     * displayed name is stored separately and is the only thing anybody sees, so this only has to
     * be unique and unchanging, not pretty.
     */
    static String slug(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private void persistOrRollBack(String key, House previous) throws IOException {
        try {
            persist();
        } catch (IOException e) {
            if (previous == null) {
                byId.remove(key);
            } else {
                byId.put(key, previous);
            }
            throw e;
        }
    }

    private void persist() throws IOException {
        JSONObject root = new JSONObject();
        for (House house : byId.values()) {
            JSONObject json = new JSONObject()
                    .put("name", house.name())
                    .put("heads", new JSONArray(house.headIds()))
                    .put("members", new JSONArray(house.memberIds()))
                    .put("invited", new JSONArray(house.invitedIds()))
                    .put("nobleTitles", new JSONArray(house.nobleTitles()));
            house.motto().ifPresent(v -> json.put("motto", v));
            house.description().ifPresent(v -> json.put("description", v));
            house.crestUrl().ifPresent(v -> json.put("crest", v));

            JSONObject grants = new JSONObject();
            house.nobleGrants().forEach((userId, titles) -> grants.put(userId, new JSONArray(titles)));
            if (!grants.isEmpty()) {
                json.put("nobleGrants", grants);
            }
            root.put(house.id(), json);
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
            JSONObject root = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            for (String id : root.keySet()) {
                JSONObject json = root.getJSONObject(id);
                Map<String, Set<String>> grants = new LinkedHashMap<>();
                JSONObject granted = json.optJSONObject("nobleGrants");
                if (granted != null) {
                    for (String userId : granted.keySet()) {
                        grants.put(userId, strings(granted.optJSONArray(userId)));
                    }
                }
                byId.put(id, new House(id,
                        json.getString("name"),
                        optional(json, "motto"),
                        optional(json, "description"),
                        optional(json, "crest"),
                        List.copyOf(strings(json.optJSONArray("heads"))),
                        strings(json.optJSONArray("members")),
                        strings(json.optJSONArray("invited")),
                        List.copyOf(strings(json.optJSONArray("nobleTitles"))),
                        grants));
            }
            LOG.info("Read {} house(s) from {}", byId.size(), file);
        } catch (IOException | JSONException e) {
            loaded = false;
            byId.clear();
            throw new IllegalStateException("Could not read houses at " + file
                    + ". Nothing has been changed; fix or restore the file and restart.", e);
        }
    }

    /** A set rather than a list, and ordered, so membership reads the way it was written. */
    private static Set<String> strings(JSONArray array) {
        Set<String> values = new LinkedHashSet<>();
        for (int i = 0; array != null && i < array.length(); i++) {
            values.add(array.getString(i));
        }
        return values;
    }

    private static Optional<String> optional(JSONObject json, String key) {
        String value = json.optString(key, "");
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /** A refusal a person should read, as opposed to a fault the console should. */
    public static class HouseRejected extends Exception {
        public HouseRejected(String message) {
            super(message);
        }
    }
}
