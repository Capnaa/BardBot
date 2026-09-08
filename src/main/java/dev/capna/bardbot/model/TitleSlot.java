package dev.capna.bardbot.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The three places a title can sit in a Bard's name.
 *
 * <p>They read as one line, {@code Lord Prophet Azurov the Veteran of The Tribunal}, but they are
 * granted by three different authorities and are otherwise unrelated. Keeping them as separate
 * slots is what lets a Bard hold one, two or all three without any of the words between them
 * needing a special case.
 */
public enum TitleSlot {

    /** Granted by the Bard's house, and lost with the house. */
    NOBLE("Noble", "noble"),

    /** Earned by crossing a virtue threshold. Nobody grants it and nobody can take it away. */
    VIRTUE("Virtue", "virtue"),

    /** Granted by the tribunal, for a position in the government. */
    GOVERNMENT("Government", "gov");

    private final String display;
    private final String key;

    TitleSlot(String display, String key) {
        this.display = display;
        this.key = key;
    }

    public String display() {
        return display;
    }

    /** The form used in command options and on disk, which must never change once written. */
    public String key() {
        return key;
    }

    public static Optional<TitleSlot> byKey(String key) {
        return Arrays.stream(values())
                .filter(slot -> slot.key.equalsIgnoreCase(key.toLowerCase(Locale.ROOT)))
                .findFirst();
    }
}
