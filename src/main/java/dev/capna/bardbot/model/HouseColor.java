package dev.capna.bardbot.model;

import java.awt.Color;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The color a house is written in.
 *
 * <p>Chosen from a fixed set rather than typed as a hex code, because the color has to work in two
 * places that do not agree on what a color is. Discord's ANSI code blocks offer eight terminal
 * colors and nothing else, so a house picking {@code #7f3fbf} could not be drawn in one. Each
 * entry therefore carries both the terminal code and the nearest real color for the side of an
 * embed.
 */
public enum HouseColor {

    RED("red", "Red", "\033[0;31m", new Color(0xDD, 0x33, 0x33)),
    ORANGE("orange", "Orange", "\033[0;33m", new Color(0xE0, 0x8A, 0x1E)),
    YELLOW("yellow", "Yellow", "\033[1;33m", new Color(0xE5, 0xC0, 0x3A)),
    GREEN("green", "Green", "\033[0;32m", new Color(0x3B, 0xA5, 0x5D)),
    CYAN("cyan", "Cyan", "\033[0;36m", new Color(0x35, 0xA9, 0xB4)),
    BLUE("blue", "Blue", "\033[0;34m", new Color(0x3B, 0x6F, 0xD1)),
    PURPLE("purple", "Purple", "\033[0;35m", new Color(0x8A, 0x4F, 0xC4)),
    GREY("grey", "Grey", "\033[0;37m", new Color(0x9A, 0x9A, 0x9A));

    private final String key;
    private final String display;
    private final String ansi;
    private final Color rgb;

    HouseColor(String key, String display, String ansi, Color rgb) {
        this.key = key;
        this.display = display;
        this.ansi = ansi;
        this.rgb = rgb;
    }

    public String key() {
        return key;
    }

    public String display() {
        return display;
    }

    public Color rgb() {
        return rgb;
    }

    /** Wraps text so it is drawn in this color inside a code block. */
    public String paint(String text) {
        return ansi + text + "\033[0m";
    }

    /** What a house is given when nobody has chosen, so a house always has a color. */
    public static HouseColor fallback() {
        return GREY;
    }

    public static Optional<HouseColor> byKey(String key) {
        return Arrays.stream(values())
                .filter(color -> color.key.equals(key.toLowerCase(Locale.ROOT)))
                .findFirst();
    }
}
