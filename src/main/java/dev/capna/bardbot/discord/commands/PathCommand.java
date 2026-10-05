package dev.capna.bardbot.discord.commands;

import dev.capna.bardbot.discord.Ansi;
import dev.capna.bardbot.discord.AwardEmbed;
import dev.capna.bardbot.discord.LiveBoards;
import dev.capna.bardbot.discord.Replies;
import dev.capna.bardbot.discord.SlashCommand;
import dev.capna.bardbot.model.PathReward;
import dev.capna.bardbot.model.Virtue;
import dev.capna.bardbot.ops.ChannelRole;
import dev.capna.bardbot.ops.Feature;
import dev.capna.bardbot.ops.SettingsStore;
import dev.capna.bardbot.path.PathCard;
import dev.capna.bardbot.path.PathOfVirtue;
import dev.capna.bardbot.store.PathStore;
import dev.capna.bardbot.store.ProfileStore;
import dev.capna.bardbot.virtue.Awarding;
import dev.capna.bardbot.virtue.Unlocks;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The Path of Virtue, from the Pilgrim's side: where they are on it, and spending what it paid.
 *
 * <p>Everything here is public in the sense that anyone can run it. A Bard without the role sees
 * the same ladder and their own month against it, and is told the role is what they are missing,
 * which is the honest answer to "what would I get".
 */
public final class PathCommand implements SlashCommand {

    private static final String NAME = "path";

    private final PathOfVirtue path;
    private final ProfileStore profiles;
    private final SettingsStore settings;
    private final Unlocks unlocks;
    private final LiveBoards boards;
    private final PathCard card;

    public PathCommand(PathOfVirtue path, ProfileStore profiles, SettingsStore settings,
                       Unlocks unlocks, LiveBoards boards, PathCard card) {
        this.path = Objects.requireNonNull(path, "path");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.unlocks = Objects.requireNonNull(unlocks, "unlocks");
        this.boards = Objects.requireNonNull(boards, "boards");
        this.card = Objects.requireNonNull(card, "card");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<Feature> feature() {
        return Optional.of(Feature.PATH);
    }

    @Override
    public CommandData definition() {
        OptionData virtue = new OptionData(OptionType.STRING, "virtue",
                "Which virtue the five go into", true);
        Arrays.stream(Virtue.values())
                .forEach(value -> virtue.addChoice(value.display(), value.key()));

        return Commands.slash(NAME, "The Path of Virtue")
                .addSubcommands(new SubcommandData("view",
                        "Where you are on the Path this month, and what is next"))
                .addSubcommandGroups(new SubcommandGroupData("voucher", "Virtue Vouchers")
                        .addSubcommands(
                                new SubcommandData("view", "How many vouchers you are holding"),
                                new SubcommandData("claim",
                                        "Spend one voucher on " + PathReward.VOUCHER_VIRTUE
                                                + " virtue")
                                        .addOptions(virtue)));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) throws Exception {
        String subcommand = Objects.requireNonNull(event.getSubcommandName());
        if ("voucher".equals(event.getSubcommandGroup())) {
            if ("claim".equals(subcommand)) {
                claim(event);
            } else {
                int held = path.vouchers(event.getUser().getId());
                Replies.quietly(event, held == 0
                        ? "You have no vouchers. Reach " + PathReward.VOUCHER_ONE.threshold()
                                + " virtue on the Path to earn one."
                        : "You are holding " + held + (held == 1 ? " voucher" : " vouchers")
                                + ", each worth " + PathReward.VOUCHER_VIRTUE
                                + " virtue. Spend one with /path voucher claim.");
            }
            return;
        }
        if ("view".equals(subcommand)) {
            view(event);
        } else {
            Replies.problem(event, "That is not something this command does.");
        }
    }

    /**
     * Where the Bard is on the Path, as a picture with the ladder under it.
     *
     * <p>Deferred, because drawing the card fetches a face from Discord's CDN and three seconds is
     * not a promise worth making over somebody else's network. The embed is sent whether or not
     * the card could be drawn, so a card that fails costs the picture and nothing else.
     *
     * <p>Shown to the channel rather than quietly. The Path is a thing people buy into and
     * compare, and a card nobody else can see is a card nobody mentions.
     */
    private void view(SlashCommandInteractionEvent event) {
        Replies.defer(event, NAME);

        int earned = path.earnedThisMonth(event.getUser().getId());
        Optional<byte[]> drawn = card.render(
                event.getMember() == null
                        ? event.getUser().getEffectiveName()
                        : event.getMember().getEffectiveName(),
                earned,
                profiles.get(event.getUser().getId()).imageUrl(),
                event.getUser().getEffectiveAvatarUrl());

        MessageEmbed embed = progress(event, earned);
        if (drawn.isEmpty()) {
            event.getHook().sendMessageEmbeds(embed).queue();
            return;
        }
        event.getHook()
                .sendMessageEmbeds(new EmbedBuilder(embed).setImage("attachment://path.png").build())
                .setFiles(FileUpload.fromData(drawn.get(), "path.png"))
                .queue();
    }

    private MessageEmbed progress(SlashCommandInteractionEvent event, int earned) {
        boolean pilgrim = path.isPilgrim(event.getMember());

        StringBuilder rows = new StringBuilder(Ansi.FENCE);
        for (PathReward reward : PathReward.values()) {
            // A met line is ticked rather than hidden, the same as a goal, so the list reads as
            // progress rather than as a set of things still to do.
            rows.append(reward.metBy(earned) ? "✓ " : "  ")
                    .append(Ansi.number(String.format("%4d", reward.threshold())))
                    .append("  ").append(Ansi.white(reward.display()))
                    .append('\n');
        }
        rows.append("```");

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("The Path of Virtue, " + path.thisMonth())
                .setDescription((pilgrim
                        ? "You are a Pilgrim this month. "
                        : "You are not on the Path this month, so nothing below is unlocked. "
                                + "The tribunal adds the Pilgrim role when you buy in. ")
                        + "You have earned **" + earned + "** virtue since the first.")
                .addField("The ladder", rows.toString(), false);

        Arrays.stream(PathReward.values())
                .filter(reward -> !reward.metBy(earned))
                .findFirst()
                .ifPresentOrElse(
                        next -> embed.setFooter((next.threshold() - earned) + " more to "
                                + next.display(), null),
                        () -> embed.setFooter("You have reached the top of the Path.", null));
        return embed.build();
    }

    /**
     * Spends a voucher.
     *
     * <p>Posted as an award, in the awards channel, because that is what it is: five virtue,
     * counted for titles and renown like any other. The only thing it does not count for is the
     * Path that paid for it.
     */
    private void claim(SlashCommandInteractionEvent event) throws Exception {
        Virtue virtue = Virtue.byKey(event.getOption("virtue", "", OptionMapping::getAsString))
                .orElseThrow();
        String userId = event.getUser().getId();

        Awarding.Result result;
        try {
            result = path.claim(userId, virtue);
        } catch (PathStore.PathRejected rejected) {
            Replies.problem(event, rejected.getMessage());
            return;
        }

        MessageCreateData message = MessageCreateData.fromEmbeds(AwardEmbed.of(
                result, profiles.get(userId), Optional.of("Virtue Voucher"),
                event.getUser(), event.getUser()));
        MessageChannel destination = settings.channel(ChannelRole.AWARDS)
                .map(id -> (MessageChannel) event.getJDA().getTextChannelById(id))
                .filter(Objects::nonNull)
                .orElse(event.getChannel());
        if (destination.getId().equals(event.getChannelId())) {
            event.replyEmbeds(message.getEmbeds()).queue();
        } else {
            destination.sendMessage(message).queue();
            Replies.quietly(event, "Claimed. Posted in <#" + destination.getId() + ">. You have "
                    + path.vouchers(userId) + " left.");
        }

        unlocks.announce(event.getJDA(), userId, result.crossed());
        boards.refreshSoon(event.getJDA());
    }
}
