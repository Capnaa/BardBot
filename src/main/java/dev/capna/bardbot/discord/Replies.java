package dev.capna.bardbot.discord;

import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers that only the person who asked should see.
 *
 * <p>Anything that is not the thing they asked for belongs here: a house that does not exist, a
 * title they have not been granted, a feature switched off. Those are conversations between one
 * person and the bot, and posting them into a channel adds noise for everyone else while telling
 * them nothing.
 *
 * <p>The successful answer is the opposite. A profile or a leaderboard is worth showing the
 * channel, because that is usually why it was run there.
 */
public final class Replies {

    private static final Logger LOG = LoggerFactory.getLogger(Replies.class);

    private Replies() {
    }

    /**
     * Acknowledges an interaction before doing anything slow.
     *
     * <p>Discord gives three seconds and then discards the interaction, and it can be gone before
     * the acknowledgement lands: the first one after a restart pays for a connection nobody has
     * opened yet.
     *
     * <p>Losing that race is not a fault worth a stack trace. There is nothing to recover and
     * nobody to tell, since the interaction that would carry the message is the thing that expired,
     * so it is noted and dropped.
     */
    public static void defer(IReplyCallback event, String command) {
        event.deferReply().queue(null, error ->
                LOG.warn("Could not acknowledge /{} in time; it was not answered", command));
    }

    /** A refusal the person should read: something they asked for that cannot be done. */
    public static void problem(IReplyCallback event, String message) {
        event.reply(message).setEphemeral(true).queue(null, error ->
                LOG.warn("Could not deliver a reply: {}", error.getMessage()));
    }

    /** Confirmation of something that worked, shown only to whoever did it. */
    public static void quietly(IReplyCallback event, String message) {
        event.reply(message).setEphemeral(true).queue(null, error ->
                LOG.warn("Could not deliver a reply: {}", error.getMessage()));
    }

    /**
     * A fault, as opposed to a refusal.
     *
     * <p>The reader is told that it failed and nothing else. What actually broke goes to the
     * console, because an exception message in a channel is noise to everyone who sees it and an
     * invitation to whoever is looking for one.
     */
    public static void failed(IReplyCallback event, String command, Throwable cause) {
        LOG.error("/{} failed", command, cause);
        problem(event, "Something went wrong running that. It has been logged.");
    }
}
