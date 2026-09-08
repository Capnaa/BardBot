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
    CONSOLE("console", "Console");

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
