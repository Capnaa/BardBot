package dev.capna.bardbot.ops;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** One of the standing leaderboards the bot keeps up to date in a channel. */
public enum BoardKind {

    VIRTUE("virtue", "Total virtue"),
    HOUSE("house", "Renown this month");

    private final String key;
    private final String display;

    BoardKind(String key, String display) {
        this.key = key;
        this.display = display;
    }

    public String key() {
        return key;
    }

    public String display() {
        return display;
    }

    public static Optional<BoardKind> byKey(String key) {
        return Arrays.stream(values())
                .filter(kind -> kind.key.equals(key.toLowerCase(Locale.ROOT)))
                .findFirst();
    }
}
