package dev.capna.bardbot.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every name on this bot was typed by somebody. Discord reads underscores and asterisks in them as
 * formatting, so a house called {@code House_Vale} would lose its underscore and turn italic, and a
 * name written to exploit that could imitate the bot's own headings.
 */
class NamesTest {

    @Test
    void formattingCharactersAreEscaped() {
        assertEquals("House\\_Vale", Names.escaped("House_Vale"));
        assertEquals("\\*\\*Emperor\\*\\*", Names.escaped("**Emperor**"));
    }

    @Test
    void ordinaryNamesAreLeftAlone() {
        assertEquals("House Vale", Names.escaped("House Vale"));
    }

    /** Titles and footers are not formatted by Discord, so a backslash there would be shown. */
    @Test
    void plainStripsRatherThanEscapes() {
        assertFalse(Names.plain("House_Vale").contains("\\"));
        assertEquals("one line", Names.plain("one\nline"));
    }

    @Test
    void fitTrimsToTheLimit() {
        assertEquals("short", Names.fit("short", 20));
        assertTrue(Names.fit("a".repeat(50), 20).length() <= 20);
        assertTrue(Names.fit("a".repeat(50), 20).endsWith("…"));
    }
}
