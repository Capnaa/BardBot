package dev.capna.bardbot.ops;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the tribunal can change while the bot is running.
 *
 * <p>Separate from {@code config}, which is fixed at startup. The distinction is whether a change
 * should require a restart: which features ship is decided once when the bot is deployed, where a
 * message is posted is decided whenever the server is reorganised.
 *
 * <p>Immutable. Changes produce a new instance, so a reader holding one sees a consistent view
 * rather than a set of fields being mutated underneath it.
 *
 * @param enabled  features currently switched on, seeded from configuration at first start
 * @param channels which channel does which job, empty for a job nothing is set for yet
 * @param boards   where each standing leaderboard lives, as {@code channelId/messageId}. The
 *                 message is remembered rather than reposted, so the channel holds one board that
 *                 is always current instead of a scroll of stale ones.
 */
public record Settings(Set<Feature> enabled,
                       Map<ChannelRole, String> channels,
                       Map<BoardKind, String> boards) {

    public Settings {
        enabled = Set.copyOf(enabled);
        channels = Map.copyOf(channels);
        boards = Map.copyOf(boards);
    }

    public static Settings of(Feature... features) {
        return new Settings(features.length == 0
                ? EnumSet.noneOf(Feature.class)
                : EnumSet.copyOf(Set.of(features)), Map.of(), Map.of());
    }

    public Optional<String> board(BoardKind kind) {
        return Optional.ofNullable(boards.get(kind));
    }

    public Settings with(BoardKind kind, String channelId, String messageId) {
        Map<BoardKind, String> updated = boards.isEmpty()
                ? new EnumMap<>(BoardKind.class)
                : new EnumMap<>(boards);
        updated.put(kind, channelId + "/" + messageId);
        return new Settings(enabled, channels, updated);
    }

    public boolean isEnabled(Feature feature) {
        return enabled.contains(feature);
    }

    /** Empty when nothing has been set, which every caller has to handle: posting is optional. */
    public Optional<String> channel(ChannelRole role) {
        return Optional.ofNullable(channels.get(role));
    }

    public Settings with(Feature feature, boolean on) {
        EnumSet<Feature> updated = enabled.isEmpty()
                ? EnumSet.noneOf(Feature.class)
                : EnumSet.copyOf(enabled);
        if (on) {
            updated.add(feature);
        } else {
            updated.remove(feature);
        }
        return new Settings(updated, channels, boards);
    }

    public Settings with(ChannelRole role, String channelId) {
        Map<ChannelRole, String> updated = channels.isEmpty()
                ? new EnumMap<>(ChannelRole.class)
                : new EnumMap<>(channels);
        updated.put(role, channelId);
        return new Settings(enabled, updated, boards);
    }
}
