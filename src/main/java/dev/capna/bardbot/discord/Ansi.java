package dev.capna.bardbot.discord;

import dev.capna.bardbot.model.Virtue;

/**
 * Color inside a Discord code block.
 *
 * <p>Discord renders a fence marked {@code ansi} with a small set of terminal colors. There is no
 * orange among them, so Merit uses yellow, which is as close as the palette goes.
 *
 * <p>Padding has to happen before the codes are added. An escape sequence has width on the string
 * and none on the screen, so formatting a colored string to a fixed width silently knocks every
 * row out of line.
 *
 * <p>A client that does not understand the fence shows the text without color rather than showing
 * the escape codes, so this cannot leave anybody looking at rubbish.
 */
public final class Ansi {

    public static final String FENCE = "```ansi\n";

    private static final String RESET = "\033[0m";

    private Ansi() {
    }

    /** The color each virtue is spoken about in. */
    public static String color(Virtue virtue) {
        return switch (virtue) {
            case HONOR -> "\033[0;34m";
            case MERIT -> "\033[0;33m";
            case GLORY -> "\033[0;31m";
            case FAME -> "\033[0;35m";
        };
    }

    /**
     * The virtue's colour as a real one, for the stripe down the side of an embed.
     *
     * <p>Kept beside the terminal codes so the two cannot drift apart: an award drawn red in its
     * table and blue down its edge would just look broken.
     */
    public static java.awt.Color rgb(Virtue virtue) {
        return switch (virtue) {
            case HONOR -> new java.awt.Color(0x3B, 0x6F, 0xD1);
            case MERIT -> new java.awt.Color(0xE0, 0x8A, 0x1E);
            case GLORY -> new java.awt.Color(0xDD, 0x33, 0x33);
            case FAME -> new java.awt.Color(0x8A, 0x4F, 0xC4);
        };
    }

    public static String virtue(Virtue virtue, String text) {
        return color(virtue) + text + RESET;
    }

    /** Totals and headings, which belong to no single virtue. */
    public static String white(String text) {
        return "\033[1;37m" + text + RESET;
    }

    /**
     * Makes player written text safe to put inside a fence.
     *
     * <p>Escaping is wrong in a code block, since a backslash would simply be shown. The one thing
     * that matters is that nothing can close the fence early and turn the rest of the message into
     * whatever it likes.
     */
    public static String inFence(String text) {
        return text.replace("`", "\u02cb");
    }

    /** Figures, so the number reads louder than the label beside it. */
    public static String number(String text) {
        return "\033[0;37m" + text + RESET;
    }
}
