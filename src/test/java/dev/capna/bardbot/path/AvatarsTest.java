package dev.capna.bardbot.path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules that make one fetching class safe to have.
 *
 * <p>A character image is a URL a Bard typed in, so this is the boundary between "the bot draws
 * your picture on a card" and "anybody with a profile can make the bot fetch an address inside the
 * network it runs on". Tested on the decision alone, with nothing fetched, which is the only way
 * to test it without the network the rest of the suite refuses to touch.
 */
class AvatarsTest {

    @Test
    void discordAndTheHostsWeTellPeopleToUseArePermitted() {
        assertTrue(Avatars.permitted(
                "https://cdn.discordapp.com/avatars/1/abc.png?size=256").isPresent());
        assertTrue(Avatars.permitted("https://media.discordapp.net/attachments/1/2/a.png").isPresent());
        assertTrue(Avatars.permitted("https://i.imgur.com/abc123.png").isPresent());
    }

    /** The bot is not a browser. Anywhere else, Discord does the fetching and this does not. */
    @Test
    void anywhereElseIsRefused() {
        assertFalse(Avatars.permitted("https://example.com/a.png").isPresent());
        assertFalse(Avatars.permitted("https://cdn.discordapp.com.evil.test/a.png").isPresent());
    }

    /**
     * The whole reason the rules exist.
     *
     * <p>Every one of these is reachable by typing it into {@code /profile edit identity}, and
     * every one of them would be a request made from inside the network the bot runs in.
     */
    @Test
    void nothingPrivateIsReachable() {
        assertFalse(Avatars.permitted("http://169.254.169.254/latest/meta-data/").isPresent());
        assertFalse(Avatars.permitted("https://127.0.0.1/admin").isPresent());
        assertFalse(Avatars.permitted("https://localhost:8080/").isPresent());
        assertFalse(Avatars.permitted("https://10.0.0.5/").isPresent());
        assertFalse(Avatars.permitted("https://192.168.1.1/").isPresent());
        assertFalse(Avatars.permitted("file:///etc/passwd").isPresent());
        assertFalse(Avatars.permitted("gopher://cdn.discordapp.com/").isPresent());
    }

    /** Plain http would also be a downgrade anybody on the path could read and rewrite. */
    @Test
    void plainHttpIsRefusedEvenOnAPermittedHost() {
        assertFalse(Avatars.permitted("http://cdn.discordapp.com/avatars/1/abc.png").isPresent());
    }

    /**
     * Two older tricks for making a URL look like it points somewhere it does not: credentials
     * before the host, and a port that is not the one https lives on.
     */
    @Test
    void credentialsAndOddPortsAreRefused() {
        assertFalse(Avatars.permitted(
                "https://cdn.discordapp.com@evil.test/a.png").isPresent());
        assertFalse(Avatars.permitted("https://cdn.discordapp.com:22/a.png").isPresent());
    }

    @Test
    void rubbishIsRefusedRatherThanThrown() {
        assertFalse(Avatars.permitted("not a url at all").isPresent());
        assertFalse(Avatars.permitted("").isPresent());
    }
}
