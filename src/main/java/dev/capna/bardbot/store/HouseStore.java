package dev.capna.bardbot.store;

import dev.capna.bardbot.model.House;
import dev.capna.bardbot.model.HouseColor;
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
     * Creates a house under a head.
     *
     * <p>The head is given at creation rather than appointed afterwards, because a house without
     * one is a dead end: nobody can invite anyone into it or edit it, and the only way out is a
     * second command that the tribunal has to remember to run. It can still be changed later.
     *
     * @throws HouseRejected if the name is taken or unusable, the roll is full, or the head already
     *                       belongs to a house. Rejections are distinct from failures: the caller
     *                       reports them to whoever asked rather than logging them as faults.
     */
    public synchronized House add(String name, String headId) throws IOException, HouseRejected {
        return add(name, headId, HouseColor.fallback());
    }

    /** @param color how the house is written wherever it is named */
    public synchronized House add(String name, String headId, HouseColor color)
            throws IOException, HouseRejected {
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
        Optional<House> existing = holding(headId);
        if (existing.isPresent()) {
            throw new HouseRejected("That Bard is already in " + existing.get().name()
                    + ". They have to leave it before they can head a new house.");
        }

        House house = new House(id, trimmed, Optional.empty(), Optional.empty(), Optional.empty(),
                color, List.of(headId), Set.of(), Set.of(), List.of(), Map.of());
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
     * Appoints another head.
     *
     * <p>More than one is allowed: a house may genuinely be led by two people, and the alternative
     * is a handover where the house is briefly headless.
     *
     * @throws HouseRejected if they already lead it, or belong to another house
     */
    public synchronized House addHead(String id, String userId) throws IOException, HouseRejected {
        load();
        House house = require(id);
        if (house.isHead(userId)) {
            throw new HouseRejected("They already head " + house.name() + ".");
        }
        Optional<House> elsewhere = holding(userId);
        if (elsewhere.isPresent() && !elsewhere.get().id().equals(house.id())) {
            throw new HouseRejected("That Bard is already in " + elsewhere.get().name()
                    + ". They have to leave it before they can head another house.");
        }
        List<String> heads = new ArrayList<>(house.headIds());
        heads.add(userId);
        return update(house.id(), current -> withHeads(current, heads));
    }

    /**
     * Stands a head down.
     *
     * <p>The last one cannot be removed. A headless house cannot be edited, cannot invite anyone
     * and cannot grant its own titles, so it would be a house nobody could do anything with and
     * nothing in the bot would say why.
     *
     * @throws HouseRejected if they do not head it, or they are the only one who does
     */
    public synchronized House removeHead(String id, String userId) throws IOException, HouseRejected {
        load();
        House house = require(id);
        if (!house.isHead(userId)) {
            throw new HouseRejected("They do not head " + house.name() + ".");
        }
        if (house.headIds().size() == 1) {
            throw new HouseRejected(house.name() + " would be left with no head. "
                    + "Appoint another one first, or remove the house.");
        }
        List<String> heads = new ArrayList<>(house.headIds());
        heads.remove(userId);
        return update(house.id(), current -> withHeads(current, heads));
    }

    /**
     * Invites a Bard.
     *
     * <p>An invitation rather than an assignment. Being put into a house without agreeing is the
     * kind of thing that starts an argument the bot cannot settle, and the head loses nothing by
     * asking.
     *
     * @throws HouseRejected if they already belong somewhere, or have already been asked
     */
    public synchronized House invite(String id, String userId) throws IOException, HouseRejected {
        load();
        House house = require(id);
        if (house.holds(userId)) {
            throw new HouseRejected("They are already in " + house.name() + ".");
        }
        Optional<House> elsewhere = holding(userId);
        if (elsewhere.isPresent()) {
            throw new HouseRejected("They are already in " + elsewhere.get().name()
                    + ". They have to leave it before they can join another house.");
        }
        if (house.invitedIds().contains(userId)) {
            throw new HouseRejected("They have already been invited to " + house.name() + ".");
        }
        Set<String> invited = new LinkedHashSet<>(house.invitedIds());
        invited.add(userId);
        return update(house.id(), current ->
                current.with(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        null, null, invited, null, null));
    }

    /** Every house that has asked for a Bard, so an invitation cannot be lost. */
    public synchronized List<House> invitationsFor(String userId) {
        load();
        return byId.values().stream()
                .filter(house -> house.invitedIds().contains(userId))
                .toList();
    }

    /**
     * Takes up an invitation.
     *
     * <p>Every other invitation is dropped at the same time. A Bard is in at most one house, so an
     * invitation they can no longer accept is only there to be clicked and refused later.
     *
     * @throws HouseRejected if they were not invited, or joined somewhere in the meantime
     */
    public synchronized House accept(String id, String userId) throws IOException, HouseRejected {
        load();
        House house = require(id);
        if (!house.invitedIds().contains(userId)) {
            throw new HouseRejected(house.name() + " has not invited you.");
        }
        Optional<House> elsewhere = holding(userId);
        if (elsewhere.isPresent()) {
            throw new HouseRejected("You are already in " + elsewhere.get().name() + ".");
        }

        for (House other : List.copyOf(byId.values())) {
            if (!other.id().equals(house.id()) && other.invitedIds().contains(userId)) {
                Set<String> withdrawn = new LinkedHashSet<>(other.invitedIds());
                withdrawn.remove(userId);
                update(other.id(), current ->
                        current.with(Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.empty(), null, null, withdrawn, null, null));
            }
        }

        Set<String> members = new LinkedHashSet<>(house.memberIds());
        members.add(userId);
        Set<String> invited = new LinkedHashSet<>(house.invitedIds());
        invited.remove(userId);
        return update(house.id(), current ->
                current.with(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        null, members, invited, null, null));
    }

    /** Turns an invitation down. Silent about invitations that were never made. */
    public synchronized void decline(String id, String userId) throws IOException {
        load();
        Optional<House> house = byId(id);
        if (house.isEmpty() || !house.get().invitedIds().contains(userId)) {
            return;
        }
        Set<String> invited = new LinkedHashSet<>(house.get().invitedIds());
        invited.remove(userId);
        update(house.get().id(), current ->
                current.with(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        null, null, invited, null, null));
    }

    /**
     * Leaves a house, giving up its titles.
     *
     * <p>The grants go with the membership rather than being kept in case they return: a title is
     * the house's, and an ex-member wearing its rank is exactly what the grant was for.
     *
     * @throws HouseRejected if they are its only head, since that would leave it leaderless
     */
    public synchronized Optional<House> leave(String userId) throws IOException, HouseRejected {
        load();
        Optional<House> house = holding(userId);
        if (house.isEmpty()) {
            return Optional.empty();
        }
        House held = house.get();
        if (held.isHead(userId) && held.headIds().size() == 1) {
            throw new HouseRejected("You are the only head of " + held.name()
                    + ". Appoint another before you leave, or ask the tribunal to dissolve it.");
        }

        List<String> heads = new ArrayList<>(held.headIds());
        heads.remove(userId);
        Set<String> members = new LinkedHashSet<>(held.memberIds());
        members.remove(userId);
        Map<String, Set<String>> grants = new LinkedHashMap<>(held.nobleGrants());
        grants.remove(userId);

        return Optional.of(update(held.id(), current ->
                current.with(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        heads, members, null, null, grants)));
    }

    /** Removes a member, used when a head turns somebody out. */
    public synchronized House expel(String id, String userId) throws IOException, HouseRejected {
        load();
        House house = require(id);
        if (house.isHead(userId)) {
            throw new HouseRejected("Stand them down as head first.");
        }
        if (!house.holds(userId)) {
            throw new HouseRejected("They are not in " + house.name() + ".");
        }
        Set<String> members = new LinkedHashSet<>(house.memberIds());
        members.remove(userId);
        Map<String, Set<String>> grants = new LinkedHashMap<>(house.nobleGrants());
        grants.remove(userId);
        return update(house.id(), current ->
                current.with(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        null, members, null, null, grants));
    }

    /** The color of a house by id, falling back for one that has since been dissolved. */
    public HouseColor colorOf(String id) {
        return byId(id).map(House::color).orElse(HouseColor.fallback());
    }

    /** Changes the color the house is written in. */
    public synchronized House setColor(String id, HouseColor color) throws IOException {
        load();
        return update(id, current -> current.withColor(color));
    }

    /** The head's own description of the house. Absent values are left as they were. */
    public synchronized House edit(String id, Optional<String> motto, Optional<String> description,
                                   Optional<String> crestUrl) throws IOException {
        load();
        return update(id, current ->
                current.with(Optional.empty(), motto, description, crestUrl,
                        null, null, null, null, null));
    }

    /** Adds a title the house may grant. */
    public synchronized House addTitle(String id, String title) throws IOException, HouseRejected {
        load();
        House house = require(id);
        String trimmed = title.strip();
        if (trimmed.isEmpty() || trimmed.length() > House.MAX_TITLE) {
            throw new HouseRejected("A title has to be between 1 and "
                    + House.MAX_TITLE + " characters.");
        }
        if (house.nobleTitles().stream().anyMatch(existing -> existing.equalsIgnoreCase(trimmed))) {
            throw new HouseRejected(house.name() + " already has that title.");
        }
        List<String> titles = new ArrayList<>(house.nobleTitles());
        titles.add(trimmed);
        return update(house.id(), current ->
                current.with(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        null, null, null, titles, null));
    }

    /**
     * Retires a title.
     *
     * <p>The grants are left alone. A title the house no longer defines stops being offered to
     * anybody wearing it, so there is nothing to clean up and nothing that could be missed.
     */
    public synchronized House removeTitle(String id, String title) throws IOException {
        load();
        House house = require(id);
        List<String> titles = house.nobleTitles().stream()
                .filter(existing -> !existing.equalsIgnoreCase(title))
                .toList();
        return update(house.id(), current ->
                current.with(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        null, null, null, titles, null));
    }

    /** Lets one member wear one of the house's titles. */
    public synchronized House grantTitle(String id, String userId, String title)
            throws IOException, HouseRejected {
        load();
        House house = require(id);
        if (!house.holds(userId)) {
            throw new HouseRejected("They are not in " + house.name() + ".");
        }
        Optional<String> defined = house.nobleTitles().stream()
                .filter(existing -> existing.equalsIgnoreCase(title))
                .findFirst();
        if (defined.isEmpty()) {
            throw new HouseRejected(house.name() + " has no title by that name.");
        }
        Map<String, Set<String>> grants = new LinkedHashMap<>(house.nobleGrants());
        Set<String> held = new LinkedHashSet<>(grants.getOrDefault(userId, Set.of()));
        held.add(defined.get());
        grants.put(userId, held);
        return update(house.id(), current ->
                current.with(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        null, null, null, null, grants));
    }

    /** Takes a granted title back. */
    public synchronized House revokeTitle(String id, String userId, String title)
            throws IOException {
        load();
        House house = require(id);
        Map<String, Set<String>> grants = new LinkedHashMap<>(house.nobleGrants());
        Set<String> held = new LinkedHashSet<>(grants.getOrDefault(userId, Set.of()));
        held.removeIf(existing -> existing.equalsIgnoreCase(title));
        if (held.isEmpty()) {
            grants.remove(userId);
        } else {
            grants.put(userId, held);
        }
        return update(house.id(), current ->
                current.with(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        null, null, null, null, grants));
    }

    private House require(String id) {
        return byId(id).orElseThrow(() -> new IllegalArgumentException("No house with id " + id));
    }

    private static House withHeads(House house, List<String> heads) {
        return new House(house.id(), house.name(), house.motto(), house.description(),
                house.crestUrl(), house.color(), heads, house.memberIds(), house.invitedIds(),
                house.nobleTitles(), house.nobleGrants());
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
                    .put("nobleTitles", new JSONArray(house.nobleTitles()))
                    .put("color", house.color().key());
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
                        HouseColor.byKey(json.optString("color", ""))
                                .orElse(HouseColor.fallback()),
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
