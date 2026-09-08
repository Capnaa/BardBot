package dev.capna.bardbot.ops;

import dev.capna.bardbot.store.AtomicFiles;
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
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps the runtime settings, and keeps them across restarts.
 *
 * <p>Reads are a volatile field rather than a lock. Every command checks its feature toggle and
 * anything that posts checks a channel, so this is read constantly and written rarely, and readers
 * must never queue behind somebody setting a channel.
 */
public final class SettingsStore {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsStore.class);

    private final Path file;
    private volatile Settings current;

    /**
     * @param defaults used when no settings file exists yet, normally the startup features from
     *                 configuration. Once the file exists it wins, since it holds what the tribunal
     *                 last chose and a redeploy must not silently undo that.
     */
    public SettingsStore(Path file, Settings defaults) {
        this.file = Objects.requireNonNull(file, "file");
        this.current = load(file, Objects.requireNonNull(defaults, "defaults"));
    }

    public Settings current() {
        return current;
    }

    public boolean isEnabled(Feature feature) {
        return current.isEnabled(feature);
    }

    public Optional<String> channel(ChannelRole role) {
        return current.channel(role);
    }

    public synchronized void setChannel(ChannelRole role, String channelId) throws IOException {
        update(current.with(role, channelId));
        LOG.info("{} messages now go to channel {}", role.display(), channelId);
    }

    /** Where a standing leaderboard lives, as {@code channelId/messageId}. */
    public Optional<String> board(BoardKind kind) {
        return current.board(kind);
    }

    public synchronized void setBoard(BoardKind kind, String channelId, String messageId)
            throws IOException {
        update(current.with(kind, channelId, messageId));
        LOG.info("The {} board is now message {} in channel {}",
                kind.key(), messageId, channelId);
    }

    private void update(Settings updated) throws IOException {
        JSONArray features = new JSONArray();
        updated.enabled().forEach(feature -> features.put(feature.name()));

        JSONObject channels = new JSONObject();
        updated.channels().forEach((role, channelId) -> channels.put(role.key(), channelId));

        JSONObject boards = new JSONObject();
        updated.boards().forEach((kind, location) -> boards.put(kind.key(), location));

        AtomicFiles.writeString(file, new JSONObject()
                .put("features", features)
                .put("channels", channels)
                .put("boards", boards)
                .toString(2));
        current = updated;
    }

    private static Settings load(Path file, Settings defaults) {
        if (!Files.isReadable(file)) {
            return defaults;
        }
        try {
            JSONObject json = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));

            EnumSet<Feature> enabled = EnumSet.noneOf(Feature.class);
            JSONArray features = json.optJSONArray("features");
            for (int i = 0; features != null && i < features.length(); i++) {
                try {
                    enabled.add(Feature.valueOf(features.getString(i)));
                } catch (IllegalArgumentException unknown) {
                    // A feature removed in a later version, or one from a newer version on a
                    // rollback. Neither is a reason to refuse to start.
                    LOG.warn("Ignoring unknown feature in settings: {}", features.getString(i));
                }
            }

            Map<ChannelRole, String> channels = new EnumMap<>(ChannelRole.class);
            JSONObject stored = json.optJSONObject("channels");
            if (stored != null) {
                for (String key : stored.keySet()) {
                    ChannelRole.byKey(key).ifPresentOrElse(
                            role -> channels.put(role, stored.getString(key)),
                            () -> LOG.warn("Ignoring unknown channel role in settings: {}", key));
                }
            }

            Map<BoardKind, String> boards = new EnumMap<>(BoardKind.class);
            JSONObject storedBoards = json.optJSONObject("boards");
            if (storedBoards != null) {
                for (String key : storedBoards.keySet()) {
                    BoardKind.byKey(key).ifPresent(
                            kind -> boards.put(kind, storedBoards.getString(key)));
                }
            }

            return new Settings(enabled, channels, boards);
        } catch (IOException | JSONException e) {
            // Starting with defaults beats not starting. The tribunal's choices are lost, which is
            // bad, but a bot that will not boot over a malformed settings file is worse.
            LOG.error("Could not read settings from {}; using startup defaults", file, e);
            return defaults;
        }
    }
}
