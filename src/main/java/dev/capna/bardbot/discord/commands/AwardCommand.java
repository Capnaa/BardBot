package dev.capna.bardbot.discord.commands;

import dev.capna.bardbot.discord.AwardEmbed;
import dev.capna.bardbot.discord.Replies;
import dev.capna.bardbot.discord.SlashCommand;
import dev.capna.bardbot.discord.Tribunal;
import dev.capna.bardbot.model.Award;
import dev.capna.bardbot.model.Virtue;
import dev.capna.bardbot.ops.ChannelRole;
import dev.capna.bardbot.ops.Feature;
import dev.capna.bardbot.ops.SettingsStore;
import dev.capna.bardbot.store.ProfileStore;
import dev.capna.bardbot.virtue.Awarding;
import dev.capna.bardbot.virtue.Unlocks;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Awarding virtue without a message to point at.
 *
 * <p>The right-click menu covers most awards, since most of them are for something somebody wrote.
 * This is for the rest: something done in a voice call, on the game server, or an award being
 * corrected after the fact.
 *
 * <p>Both routes run the same {@link Awarding}, so an award made here is indistinguishable from one
 * made there once it is recorded.
 */
public final class AwardCommand implements SlashCommand {

    private static final String NAME = "award";

    private final Tribunal tribunal;
    private final Awarding awarding;
    private final Unlocks unlocks;
    private final ProfileStore profiles;
    private final SettingsStore settings;

    public AwardCommand(Tribunal tribunal, Awarding awarding, Unlocks unlocks,
                        ProfileStore profiles, SettingsStore settings) {
        this.tribunal = Objects.requireNonNull(tribunal, "tribunal");
        this.awarding = Objects.requireNonNull(awarding, "awarding");
        this.unlocks = Objects.requireNonNull(unlocks, "unlocks");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.settings = Objects.requireNonNull(settings, "settings");
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
        OptionData virtue = new OptionData(OptionType.STRING, "virtue", "Which virtue", true);
        Arrays.stream(Virtue.values())
                .forEach(value -> virtue.addChoice(value.display(), value.key()));

        return Commands.slash(NAME, "Award virtue to a Bard")
                .addOption(OptionType.USER, "bard", "Who to award", true)
                .addOptions(virtue)
                .addOption(OptionType.INTEGER, "amount",
                        "How much. Negative takes it back.", true)
                .addOption(OptionType.STRING, "reason", "What it is for", false);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) throws Exception {
        if (!tribunal.check(event)) {
            return;
        }
        User recipient = event.getOption("bard", OptionMapping::getAsUser);
        if (recipient == null || recipient.isBot()) {
            Replies.problem(event, "Virtue can only go to a Bard, not a bot.");
            return;
        }

        int amount = event.getOption("amount", 0, OptionMapping::getAsInt);
        if (amount == 0 || Math.abs(amount) > Awarding.MAX_AMOUNT) {
            Replies.problem(event, "The amount has to be a whole number between -"
                    + Awarding.MAX_AMOUNT + " and " + Awarding.MAX_AMOUNT + ", and not zero.");
            return;
        }

        Virtue virtue = Virtue.byKey(event.getOption("virtue", "", OptionMapping::getAsString))
                .orElseThrow();
        Optional<String> reason = Optional
                .ofNullable(event.getOption("reason", OptionMapping::getAsString))
                .map(String::strip)
                .filter(text -> !text.isEmpty())
                .map(text -> text.length() > Award.MAX_REASON
                        ? text.substring(0, Award.MAX_REASON)
                        : text);

        Awarding.Result result = awarding.apply(recipient.getId(), event.getUser().getId(),
                virtue, amount, reason);

        MessageCreateData message = MessageCreateData.fromEmbeds(AwardEmbed.of(
                result, profiles.get(recipient.getId()), reason, recipient, event.getUser()));

        MessageChannel destination = awardsChannel(event);
        if (destination.getId().equals(event.getChannelId())) {
            // Announced where it was asked for, so the answer is the announcement.
            event.replyEmbeds(message.getEmbeds()).queue();
        } else {
            destination.sendMessage(message).queue();
            Replies.quietly(event, "Awarded. Posted in <#" + destination.getId() + ">.");
        }

        unlocks.announce(event.getJDA(), recipient.getId(), result.crossed());
    }

    /**
     * Where the award is posted.
     *
     * <p>The awards channel when one is set, so every award ends up in one readable ledger however
     * it was made. Otherwise here, because an award nobody sees defeats the point of a public one.
     */
    private MessageChannel awardsChannel(SlashCommandInteractionEvent event) {
        return settings.channel(ChannelRole.AWARDS)
                .map(id -> (MessageChannel) event.getJDA().getTextChannelById(id))
                .filter(Objects::nonNull)
                .orElse(event.getChannel());
    }
}
