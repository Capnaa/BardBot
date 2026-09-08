package dev.capna.bardbot.discord;

import dev.capna.bardbot.ops.Feature;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;

import java.util.Optional;

/**
 * One command.
 *
 * <p>Commands declare themselves rather than being listed somewhere central, so adding one is a
 * single file and registration cannot drift out of step with what is actually handled.
 */
public interface SlashCommand {

    /** The name Discord routes on, without the leading slash. */
    String name();

    /** What Discord is told this command looks like, including its options. */
    CommandData definition();

    /**
     * The feature this belongs to, or empty for a command that is always available.
     *
     * <p>A disabled feature's commands are never registered, and are refused if one survives in
     * Discord from before the toggle was flipped.
     */
    default Optional<Feature> feature() {
        return Optional.empty();
    }

    /**
     * Runs the command.
     *
     * <p>Called on a JDA event thread. Anything slow must acknowledge first with
     * {@code event.deferReply()}, because Discord discards an interaction that is not answered
     * within three seconds and the user sees a failure whatever the bot does afterwards.
     */
    void handle(SlashCommandInteractionEvent event) throws Exception;

    /**
     * Runs the command when it was invoked from a message's right-click menu rather than typed.
     *
     * <p>Only one command is reached this way. Awarding virtue is meant to be done by pointing at
     * the message that earned it, and a slash command cannot be used as a reply to a message, so
     * that gesture is a context menu entry. It registers, routes and is toggled exactly like the
     * rest, which is why it lives on this interface rather than in plumbing of its own.
     */
    default void messageContext(MessageContextInteractionEvent event) throws Exception {
        Replies.problem(event, "That is not something this command does.");
    }

    /**
     * Offers suggestions as the user types.
     *
     * <p>Discord allows three seconds and no deferring, so this must answer from memory. Anything
     * that reads a file or waits on a lock belongs in {@link #handle} instead.
     */
    default void autocomplete(CommandAutoCompleteInteractionEvent event) {
        event.replyChoices().queue();
    }

    /**
     * Handles a click on a button this command put on one of its own messages.
     *
     * <p>Routed by the {@code command:argument} prefix in the custom ID, so a command only ever
     * sees its own buttons. The click carries nothing but that ID: the message may be days old and
     * whatever was in memory when it was sent is gone, so the argument has to be enough on its own.
     */
    default void button(ButtonInteractionEvent event) throws Exception {
        Replies.problem(event, "That button no longer does anything.");
    }

    /** A choice from a select menu, routed by the same prefix buttons use. */
    default void select(StringSelectInteractionEvent event) throws Exception {
        Replies.problem(event, "That menu no longer does anything.");
    }

    /**
     * A submitted modal, routed by the same prefix.
     *
     * <p>Modals are how every long piece of text reaches the bot. A modal cannot be opened in reply
     * to a deferred interaction, so a command that opens one must not defer first.
     */
    default void modal(ModalInteractionEvent event) throws Exception {
        Replies.problem(event, "That form is no longer open.");
    }
}
