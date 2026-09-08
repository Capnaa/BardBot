package dev.capna.bardbot.model;

import java.util.Arrays;
import java.util.Optional;
import java.util.Locale;

/**
 * The four virtues of Bardonia.
 *
 * <p>There is deliberately no fifth constant for the total. A total is a sum of these, computed
 * where it is displayed, so it cannot be awarded directly and cannot disagree with its parts.
 */
public enum Virtue {

    HONOR("Honor"),
    MERIT("Merit"),
    GLORY("Glory"),
    FAME("Fame");

    private final String display;

    Virtue(String display) {
        this.display = display;
    }

    /** How the virtue is written wherever a person reads it. */
    public String display() {
        return display;
    }

    /** The form used in command options and on disk, which must never change once written. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * @return the virtue with this key, or empty. Empty rather than a thrown exception because the
     *         caller is usually reading a stored file that a person may have edited by hand.
     */
    public static Optional<Virtue> byKey(String key) {
        return Arrays.stream(values())
                .filter(virtue -> virtue.key().equalsIgnoreCase(key))
                .findFirst();
    }
}
