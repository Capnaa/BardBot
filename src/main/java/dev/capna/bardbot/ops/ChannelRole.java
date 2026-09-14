package dev.capna.bardbot.ops;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * A job some channel in the guild does for the bot.
 *
 * <p>Set by command rather than in configuration. Where a message goes is something the tribunal
 * changes as the server is reorganised, and it should not need a file edit and a restart.
 */
public enum ChannelRole {

    /** Every award, as it happens. The busy one. */
    AWARDS("awards", "Awards"),

    /**
     * Goal crossings only.
     *
     * <p>Separate from awards because it is rare and worth noticing, and a title unlock buried
     * under forty routine awards is a title unlock nobody saw. Pointing both at the same channel is
     * allowed for anyone who disagrees.
     */
    UNLOCKS("unlocks", "Unlocks"),

    /**
     * The monthly house standings.
     *
     * <p>Meant for a channel only the tribunal can read. That is enforced by Discord's permissions
     * on the channel itself, not by the bot, which will post wherever it is told.
     */
    RENOWN("renown", "Monthly renown"),

    /** Warnings and errors, mirrored out of the console. */
    CONSOLE("console", "Console"),

    /**
     * Writs being served, completed and struck.
     *
     * <p>Its own channel rather than the awards one, because a writ is tribunal business and the
     * notice pings the people involved. Buried in a stream of awards it would be missed, and
     * posted where everyone reads it would be noise.
     */
    WRITS("writs", "Writs"),

    /**
     * The Virtue Board, where public writ tasks are posted.
     *
     * <p>The only role that has to be a forum rather than a text channel. Each task is a post of
     * its own there, so the community can pick one up and talk about it under it.
     */
    VIRTUE_BOARD("virtueboard", "The Virtue Board");

    private final String key;
    private final String display;

    ChannelRole(String key, String display) {
        this.key = key;
        this.display = display;
    }

    public String key() {
        return key;
    }

    public String display() {
        return display;
    }

    public static Optional<ChannelRole> byKey(String key) {
        return Arrays.stream(values())
                .filter(role -> role.key.equals(key.toLowerCase(Locale.ROOT)))
                .findFirst();
    }
}
