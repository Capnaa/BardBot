package dev.capna.bardbot.discord.commands;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading the Bards out of whatever somebody pasted.
 *
 * <p>The rest of the command is Discord and the award log, both covered elsewhere. This is the
 * part that decides who gets virtue, from a string a person typed, and getting it wrong means
 * either missing somebody or awarding a stranger.
 */
class MassAwardCommandTest {

    @Test
    void mentionsAreRead() {
        assertEquals(List.of("123456789012345678", "234567890123456789"),
                MassAwardCommand.ids("<@123456789012345678> <@234567890123456789>"));
    }

    /** Older clients still send the exclamation mark for a member with a nickname. */
    @Test
    void theOlderMentionFormIsRead() {
        assertEquals(List.of("123456789012345678"),
                MassAwardCommand.ids("<@!123456789012345678>"));
    }

    /** Pasting IDs out of the developer menu is the other way people build a list. */
    @Test
    void bareIdsAreRead() {
        assertEquals(List.of("123456789012345678", "234567890123456789"),
                MassAwardCommand.ids("123456789012345678, 234567890123456789"));
    }

    @Test
    void mentionsAndBareIdsMix() {
        assertEquals(List.of("123456789012345678", "234567890123456789"),
                MassAwardCommand.ids("<@123456789012345678> and 234567890123456789"));
    }

    /**
     * The same Bard twice is one award, not two.
     *
     * <p>Worth pinning down, because the obvious way to write a mass award list is to paste a
     * roster and then add somebody who was missed, who is often already in it.
     */
    @Test
    void theSameBardTwiceIsAwardedOnce() {
        assertEquals(List.of("123456789012345678"),
                MassAwardCommand.ids("<@123456789012345678> <@123456789012345678>"));
        assertEquals(List.of("123456789012345678"),
                MassAwardCommand.ids("<@123456789012345678> 123456789012345678"));
    }

    /** Prose around the mentions is normal, since people type a sentence and paste into it. */
    @Test
    void surroundingTextIsIgnored() {
        assertEquals(List.of("123456789012345678"),
                MassAwardCommand.ids("everyone who came: <@123456789012345678> thanks all"));
        assertTrue(MassAwardCommand.ids("nobody at all").isEmpty());
        assertTrue(MassAwardCommand.ids("").isEmpty());
    }

    /**
     * A channel or role mention is not a Bard.
     *
     * <p>They are easy to paste by accident, and an ID is an ID: without this, awarding "everyone
     * in #war-room" would try to award the channel.
     */
    @Test
    void channelsAndRolesAreNotBards() {
        assertTrue(MassAwardCommand.ids("<#123456789012345678>").isEmpty());
        assertTrue(MassAwardCommand.ids("<@&123456789012345678>").isEmpty());
    }

    /** Short numbers are amounts, dates and typos, never Discord IDs. */
    @Test
    void shortNumbersAreNotIds() {
        assertTrue(MassAwardCommand.ids("5").isEmpty());
        assertTrue(MassAwardCommand.ids("awarded 10 for the war on 2026-10-05").isEmpty());
    }
}
