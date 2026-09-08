package dev.capna.bardbot.discord.commands;

import dev.capna.bardbot.discord.AwardEmbed;
import dev.capna.bardbot.discord.LiveBoards;
import dev.capna.bardbot.discord.Replies;
import dev.capna.bardbot.discord.SlashCommand;
import dev.capna.bardbot.discord.Tribunal;
import dev.capna.bardbot.model.Award;
import dev.capna.bardbot.model.Virtue;
import dev.capna.bardbot.ops.Feature;
import dev.capna.bardbot.store.ProfileStore;
import dev.capna.bardbot.virtue.Awarding;
import dev.capna.bardbot.virtue.Unlocks;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Awarding virtue by pointing at the message that earned it.
 *
 * <p>This is the gesture the bot was asked for: read something good, award the Bard who wrote it,
 * without typing their name. A slash command cannot be used as a reply to a message, so it is a
 * message command instead, right-click the message, Apps, Award Virtue.
 *
 * <p>Three steps, because Discord will not put a dropdown inside a form. The menu identifies the
 * Bard, four buttons pick the virtue, and the form takes the amount and the reason. Everything
 * needed is carried in the button and form IDs, so nothing has to be remembered between clicks and
 * a half-finished award cannot be left lying about in memory.
 */
public final class AwardVirtueCommand implements SlashCommand {

    /** Shown in Discord's Apps menu, which is the only place this name appears. */
    private static final String NAME = "Award Virtue";

    private final Tribunal tribunal;
    private final Awarding awarding;
    private final Unlocks unlocks;
    private final ProfileStore profiles;
    private final LiveBoards boards;

    public AwardVirtueCommand(Tribunal tribunal, Awarding awarding, Unlocks unlocks,
                              ProfileStore profiles, LiveBoards boards) {
        this.tribunal = Objects.requireNonNull(tribunal, "tribunal");
        this.awarding = Objects.requireNonNull(awarding, "awarding");
        this.unlocks = Objects.requireNonNull(unlocks, "unlocks");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.boards = Objects.requireNonNull(boards, "boards");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<Feature> feature() {
        return Optional.of(Feature.VIRTUE);
    }

    @Override
    public CommandData definition() {
        return Commands.message(NAME);
    }

    @Override
    public void messageContext(MessageContextInteractionEvent event) {
        if (!tribunal.check(event)) {
            return;
        }
        User author = event.getTarget().getAuthor();
        if (author.isBot()) {
            Replies.problem(event, "That message was posted by a bot.");
            return;
        }
        // Ephemeral: picking a virtue is a step on the way to an award, not the award itself, and
        // the channel does not need to watch somebody make up their mind.
        event.reply("Awarding " + author.getEffectiveName() + ". Which virtue?")
                .addComponents(ActionRow.of(Arrays.stream(Virtue.values())
                        .map(virtue -> Button.secondary(
                                id(virtue, event.getTarget().getId(), author.getId()),
                                virtue.display()))
                        .toList()))
                .setEphemeral(true)
                .queue();
    }

    @Override
    public void button(ButtonInteractionEvent event) {
        if (!tribunal.check(event)) {
            return;
        }
        String[] parts = event.getComponentId().split(":");
        Virtue virtue = Virtue.byKey(parts[1]).orElseThrow();

        event.replyModal(Modal.create(event.getComponentId(), "Award " + virtue.display())
                .addComponents(
                        ActionRow.of(TextInput.create("amount", "How much", TextInputStyle.SHORT)
                                .setPlaceholder("3, or -3 to take some back")
                                .setRequired(true)
                                .setMaxLength(5)
                                .build()),
                        ActionRow.of(TextInput.create("reason", "What for", TextInputStyle.PARAGRAPH)
                                .setRequired(false)
                                .setMaxLength(Award.MAX_REASON)
                                .build()))
                .build()).queue();
    }

    @Override
    public void modal(ModalInteractionEvent event) throws Exception {
        if (!tribunal.check(event)) {
            return;
        }
        String[] parts = event.getModalId().split(":");
        Virtue virtue = Virtue.byKey(parts[1]).orElseThrow();
        String messageId = parts[2];
        String recipientId = parts[3];

        Optional<Integer> amount = amount(event);
        if (amount.isEmpty()) {
            Replies.problem(event, "The amount has to be a whole number between -"
                    + Awarding.MAX_AMOUNT + " and " + Awarding.MAX_AMOUNT + ".");
            return;
        }

        ModalMapping reasonField = event.getValue("reason");
        Optional<String> reason = reasonField == null || reasonField.getAsString().isBlank()
                ? Optional.empty()
                : Optional.of(reasonField.getAsString().strip());

        Awarding.Result result = awarding.apply(recipientId, event.getUser().getId(),
                virtue, amount.get(), reason);

        User recipient = event.getJDA().retrieveUserById(recipientId).complete();
        MessageCreateData message = MessageCreateData.fromEmbeds(AwardEmbed.of(
                result, profiles.get(recipientId), reason, recipient, event.getUser()));

        // Posted as a reply to the message that earned it, so the award and the thing it was for
        // stay together in the channel where everybody already is.
        event.getChannel().asTextChannel()
                .sendMessage(message)
                .setMessageReference(messageId)
                .failOnInvalidReply(false)
                .queue();

        unlocks.announce(event.getJDA(), recipientId, result.crossed());
        boards.refreshSoon(event.getJDA());
        Replies.quietly(event, "Awarded.");
    }

    /**
     * Everything the next step needs, carried in the component's own ID.
     *
     * <p>A click may arrive minutes later, after a restart, from a message nothing is holding in
     * memory. Putting the message and the recipient in the ID means the award can still be made.
     */
    private static String id(Virtue virtue, String messageId, String recipientId) {
        return NAME + ":" + virtue.key() + ":" + messageId + ":" + recipientId;
    }

    private static Optional<Integer> amount(ModalInteractionEvent event) {
        ModalMapping field = event.getValue("amount");
        if (field == null) {
            return Optional.empty();
        }
        try {
            int parsed = Integer.parseInt(field.getAsString().strip());
            boolean sane = parsed != 0
                    && Math.abs(parsed) <= Awarding.MAX_AMOUNT;
            return sane ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
