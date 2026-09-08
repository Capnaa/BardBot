package dev.capna.bardbot.store;

import dev.capna.bardbot.model.Profile;
import dev.capna.bardbot.model.TitleSlot;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Every Bard's character, kept on disk.
 *
 * <p>A profile is created the moment anyone asks for one rather than being registered, so there is
 * no state where a Bard has virtue but no profile to show it on. Only edited profiles are written:
 * an empty one is the default, so storing millions of them would be storing nothing.
 *
 * <p>Loaded once and rewritten whole on every change. Synchronised throughout, since a Bard editing
 * their profile and the tribunal granting them a title genuinely overlap.
 */
public final class ProfileStore {

    private static final Logger LOG = LoggerFactory.getLogger(ProfileStore.class);

    private final Path file;
    private final Map<String, Profile> byUser = new LinkedHashMap<>();
    private boolean loaded;

    public ProfileStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** Never empty. A Bard who has never edited anything still has a profile. */
    public synchronized Profile get(String userId) {
        load();
        return byUser.getOrDefault(userId, Profile.empty(userId));
    }

    /**
     * Applies a change and persists it.
     *
     * <p>Every mutation goes through here so that no caller can update the map and forget to write,
     * which is the failure that looks like it worked until the next restart.
     */
    public synchronized Profile update(String userId, UnaryOperator<Profile> change) throws IOException {
        load();
        Profile updated = change.apply(get(userId));
        Profile previous = byUser.put(userId, updated);
        try {
            persist();
        } catch (IOException e) {
            if (previous == null) {
                byUser.remove(userId);
            } else {
                byUser.put(userId, previous);
            }
            throw e;
        }
        return updated;
    }

    private void persist() throws IOException {
        JSONObject root = new JSONObject();
        for (Profile profile : byUser.values()) {
            JSONObject json = new JSONObject();
            profile.name().ifPresent(v -> json.put("name", v));
            profile.gender().ifPresent(v -> json.put("gender", v));
            profile.age().ifPresent(v -> json.put("age", v));
            profile.description().ifPresent(v -> json.put("description", v));
            profile.familyTree().ifPresent(v -> json.put("familyTree", v));
            profile.imageUrl().ifPresent(v -> json.put("image", v));
            profile.wikiUrl().ifPresent(v -> json.put("wiki", v));

            JSONObject equipped = new JSONObject();
            profile.equipped().forEach((slot, title) -> equipped.put(slot.key(), title));
            if (!equipped.isEmpty()) {
                json.put("titles", equipped);
            }
            if (!profile.governmentTitles().isEmpty()) {
                json.put("governmentTitles", new JSONArray(profile.governmentTitles()));
            }
            root.put(profile.userId(), json);
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
            for (String userId : root.keySet()) {
                JSONObject json = root.getJSONObject(userId);

                Map<TitleSlot, String> equipped = new EnumMap<>(TitleSlot.class);
                JSONObject titles = json.optJSONObject("titles");
                if (titles != null) {
                    for (String key : titles.keySet()) {
                        TitleSlot.byKey(key).ifPresent(slot -> equipped.put(slot, titles.getString(key)));
                    }
                }

                Set<String> government = new LinkedHashSet<>();
                JSONArray granted = json.optJSONArray("governmentTitles");
                for (int i = 0; granted != null && i < granted.length(); i++) {
                    government.add(granted.getString(i));
                }

                byUser.put(userId, new Profile(userId,
                        optional(json, "name"),
                        optional(json, "gender"),
                        optional(json, "age"),
                        optional(json, "description"),
                        optional(json, "familyTree"),
                        optional(json, "image"),
                        optional(json, "wiki"),
                        equipped,
                        government));
            }
            LOG.info("Read {} profile(s) from {}", byUser.size(), file);
        } catch (IOException | JSONException e) {
            // Every character's lore is in this file. Carrying on with an empty map would show
            // everyone a blank profile and then overwrite the real thing on the next edit.
            loaded = false;
            byUser.clear();
            throw new IllegalStateException("Could not read profiles at " + file
                    + ". Nothing has been changed; fix or restore the file and restart.", e);
        }
    }

    private static Optional<String> optional(JSONObject json, String key) {
        String value = json.optString(key, "");
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
