package dev.capna.bardbot.discord;

/**
 * Making player-written text safe to put in a message.
 *
 * <p>Every character name, house name, motto and title on this bot was typed by a person, and
 * Discord reads underscores and asterisks in them as formatting. A house called {@code House_Vale}
 * becomes italic and loses its underscore, and a name written to exploit that can impersonate the
 * bot's own headings.
 *
 * <p>There are three places where escaping is wrong rather than optional: inside a code fence,
 * inside a link label, and in anything Discord does not format at all, which means embed titles,
 * footers and autocomplete choices. Use {@link #plain} for those.
 */
public final class Names {

    private static final String NEEDS_ESCAPING = "\\*_~`>|[]()";

    private Names() {
    }

    /** For text in a message or an embed description, where Discord applies formatting. */
    public static String escaped(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 8);
        for (char c : text.toCharArray()) {
            if (NEEDS_ESCAPING.indexOf(c) >= 0) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    /**
     * For embed titles, footers and autocomplete choices, where Discord formats nothing and a
     * backslash would simply be shown to the reader.
     *
     * <p>Newlines still go, because a title is one line and text pretending otherwise renders as a
     * mess Discord did not intend.
     */
    public static String plain(String text) {
        return text.replace('\n', ' ').replace('\r', ' ').strip();
    }

    /** Trims to fit a field, without cutting a word in half where it can be helped. */
    public static String fit(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        String cut = text.substring(0, limit - 1);
        int lastSpace = cut.lastIndexOf(' ');
        // Only break on a word if one is near the end; otherwise a long unbroken string would be
        // truncated to almost nothing.
        if (lastSpace > limit - 20) {
            cut = cut.substring(0, lastSpace);
        }
        return cut.strip() + "…";
    }
}
