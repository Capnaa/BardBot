package dev.capna.bardbot.discord.commands;

import dev.capna.bardbot.discord.LeaderboardEmbed;
import dev.capna.bardbot.discord.Names;
import dev.capna.bardbot.discord.Replies;
import dev.capna.bardbot.discord.SlashCommand;
import dev.capna.bardbot.model.Award;
import dev.capna.bardbot.model.Goal;
import dev.capna.bardbot.model.Virtue;
import dev.capna.bardbot.ops.Feature;
import dev.capna.bardbot.store.AwardLog;
import dev.capna.bardbot.store.CatalogueStore;
import dev.capna.bardbot.store.ProfileStore;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reading the virtue everyone has been awarded.
 *
 * <p>Every figure here is a sum over the award log rather than a stored counter, so a leaderboard
 * cannot disagree with the profile of anyone on it.
 */
public final class VirtueCommand implements SlashCommand {

    private static final String NAME = "virtue";

    /** Enough to settle an argument about a score without reprinting somebody's whole year. */
    private static final int HISTORY_SHOWN = 15;

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final AwardLog awards;
    private final CatalogueStore catalogue;
    private final ProfileStore profiles;
    private final ZoneId zone;

    public VirtueCommand(AwardLog awards, CatalogueStore catalogue, ProfileStore profiles,
                         ZoneId zone) {
        this.awards = Objects.requireNonNull(awards, "awards");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.zone = Objects.requireNonNull(zone, "zone");
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
        return Commands.slash(NAME, "Virtue leaderboards, goals and history")
                .addSubcommands(
                        new SubcommandData("leaderboard", "Who stands where")
                                .addOptions(virtueOption("Which board. The total of all four if left out.")),
                        new SubcommandData("goals", "What each virtue unlocks, and how far along you are")
                                .addOptions(virtueOption("Which virtue's goals. All of them if left out.")),
                        new SubcommandData("history", "Every award a Bard has been given")
                                .addOption(OptionType.USER, "bard", "Whose history. Yours if left out."));
    }

    private static OptionData virtueOption(String description) {
        OptionData option = new OptionData(OptionType.STRING, "virtue", description, false);
        Arrays.stream(Virtue.values())
                .forEach(virtue -> option.addChoice(virtue.display(), virtue.key()));
        return option;
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        Optional<Virtue> virtue = Virtue.byKey(
                event.getOption("virtue", "", OptionMapping::getAsString));

        switch (Objects.requireNonNull(event.getSubcommandName())) {
            case "leaderboard" -> {
                MessageEmbed embed = LeaderboardEmbed.of(virtue, awards.standings(virtue),
                        profiles, 1, event.getUser().getId());
                event.replyEmbeds(embed).addComponents(pager(virtue, 1)).queue();
            }
            case "goals" -> event.replyEmbeds(goals(virtue, event.getUser().getId())).queue();
            case "history" -> {
                User subject = Optional.ofNullable(event.getOption("bard", OptionMapping::getAsUser))
                        .orElse(event.getUser());
                event.replyEmbeds(history(subject)).queue();
            }
            default -> Replies.problem(event, "That is not something this command does.");
        }
    }

    /**
     * Turning a page.
     *
     * <p>The board is rebuilt from the log rather than from anything held since the message was
     * sent, so a page turned an hour later shows the standings as they are now, not as they were.
     */
    @Override
    public void button(ButtonInteractionEvent event) {
        String[] parts = event.getComponentId().split(":");
        Optional<Virtue> virtue = Virtue.byKey(parts[2]);
        int page = Integer.parseInt(parts[3]);

        MessageEmbed embed = LeaderboardEmbed.of(virtue, awards.standings(virtue),
                profiles, page, event.getUser().getId());
        event.editMessageEmbeds(embed).setComponents(pager(virtue, page)).queue();
    }

    /**
     * The page buttons.
     *
     * <p>Both are always shown and simply do nothing useful at the ends, rather than disappearing:
     * buttons that move about as you page are harder to click than buttons that do not.
     */
    private ActionRow pager(Optional<Virtue> virtue, int page) {
        String key = virtue.map(Virtue::key).orElse("total");
        return ActionRow.of(
                Button.secondary(NAME + ":page:" + key + ":" + Math.max(1, page - 1), "Previous"),
                Button.secondary(NAME + ":page:" + key + ":" + (page + 1), "Next"));
    }

    private MessageEmbed goals(Optional<Virtue> only, String viewer) {
        EmbedBuilder embed = new EmbedBuilder().setTitle("Goals");
        Map<Virtue, Integer> scores = awards.scores(viewer);
        int total = scores.values().stream().mapToInt(Integer::intValue).sum();

        List<Optional<Virtue>> sections = new ArrayList<>();
        if (only.isPresent()) {
            sections.add(only);
        } else {
            Arrays.stream(Virtue.values()).forEach(virtue -> sections.add(Optional.of(virtue)));
            sections.add(Optional.empty());
        }

        boolean any = false;
        for (Optional<Virtue> section : sections) {
            List<Goal> goals = catalogue.goals(section);
            if (goals.isEmpty()) {
                continue;
            }
            any = true;
            int score = section.map(scores::get).orElse(total);

            StringBuilder rows = new StringBuilder("```\n");
            for (Goal goal : goals) {
                // A met goal is ticked rather than hidden, so the list reads as progress rather
                // than as a set of things still to do.
                rows.append(goal.metBy(score) ? "✓ " : "  ")
                        .append(String.format("%5d  ", goal.threshold()))
                        .append(goal.title().orElse(goal.note().orElse("")))
                        .append('\n');
            }
            rows.append("```");

            embed.addField(section.map(Virtue::display).orElse("Total virtue")
                    + "  (you have " + score + ")", rows.toString(), false);
        }

        if (!any) {
            embed.setDescription("No goals have been set yet.");
        }
        return embed.build();
    }

    private MessageEmbed history(User subject) {
        List<Award> history = awards.history(subject.getId(), HISTORY_SHOWN);
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Awards to " + Names.plain(subject.getEffectiveName()));

        if (history.isEmpty()) {
            embed.setDescription("Nothing has been awarded to them yet.");
            return embed.build();
        }

        StringBuilder rows = new StringBuilder();
        for (Award award : history) {
            rows.append(award.amount() > 0 ? "+" : "").append(award.amount())
                    .append(' ').append(award.virtue().display())
                    .append(" — ").append(WHEN.format(award.at().atZone(zone)));
            award.reason().ifPresent(reason ->
                    rows.append("\n  ").append(Names.escaped(reason)));
            rows.append('\n');
        }
        embed.setDescription(rows.toString());
        embed.setFooter("The " + Math.min(history.size(), HISTORY_SHOWN) + " most recent", null);
        return embed.build();
    }
}
