package dev.capna.bardbot.discord.commands;

import dev.capna.bardbot.discord.Names;
import dev.capna.bardbot.discord.Replies;
import dev.capna.bardbot.discord.SlashCommand;
import dev.capna.bardbot.discord.Tribunal;
import dev.capna.bardbot.model.Goal;
import dev.capna.bardbot.model.House;
import dev.capna.bardbot.model.Profile;
import dev.capna.bardbot.model.Virtue;
import dev.capna.bardbot.discord.LiveBoards;
import dev.capna.bardbot.ops.BoardKind;
import dev.capna.bardbot.ops.ChannelRole;
import dev.capna.bardbot.ops.SettingsStore;
import dev.capna.bardbot.store.CatalogueStore;
import dev.capna.bardbot.store.HouseStore;
import dev.capna.bardbot.store.ProfileStore;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Everything the tribunal does that is not awarding virtue.
 *
 * <p>Gathered under one command so that what is privileged is obvious from the name rather than
 * from reading each command's permission check. Awarding is the deliberate exception: it is the
 * most used command on the bot and does not deserve to be four words deep.
 */
public final class AdminCommand implements SlashCommand {

    private static final String NAME = "admin";

    private final Tribunal tribunal;
    private final SettingsStore settings;
    private final CatalogueStore catalogue;
    private final HouseStore houses;
    private final ProfileStore profiles;
    private final LiveBoards boards;

    public AdminCommand(Tribunal tribunal, SettingsStore settings, CatalogueStore catalogue,
                        HouseStore houses, ProfileStore profiles, LiveBoards boards) {
        this.tribunal = Objects.requireNonNull(tribunal, "tribunal");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.houses = Objects.requireNonNull(houses, "houses");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.boards = Objects.requireNonNull(boards, "boards");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public CommandData definition() {
        OptionData role = new OptionData(OptionType.STRING, "which", "Which messages", true);
        Arrays.stream(ChannelRole.values())
                .forEach(value -> role.addChoice(value.display(), value.key()));

        OptionData virtue = new OptionData(OptionType.STRING, "virtue",
                "Which virtue, or the total of all four", true);
        Arrays.stream(Virtue.values())
                .forEach(value -> virtue.addChoice(value.display(), value.key()));
        virtue.addChoice("Total", "total");

        return Commands.slash(NAME, "Tribunal business")
                .addSubcommands(new SubcommandData("help",
                        "How to award virtue and run the bot"))
                .addSubcommandGroups(
                        new SubcommandGroupData("channel", "Where the bot posts")
                                .addSubcommands(new SubcommandData("set",
                                        "Send these messages to this channel")
                                        .addOptions(role)),
                        new SubcommandGroupData("goal", "Virtue goals and the titles behind them")
                                .addSubcommands(
                                        new SubcommandData("add", "Add or rename a goal")
                                                .addOptions(virtue)
                                                .addOption(OptionType.INTEGER, "threshold",
                                                        "The score that meets it", true)
                                                .addOption(OptionType.STRING, "title",
                                                        "The virtue title it unlocks", false)
                                                .addOption(OptionType.STRING, "note",
                                                        "What it means, shown beside it", false),
                                        new SubcommandData("remove", "Remove a goal")
                                                .addOptions(virtue)
                                                .addOption(OptionType.INTEGER, "threshold",
                                                        "Which one", true),
                                        new SubcommandData("list", "Every goal that is set")),
                        new SubcommandGroupData("house", "Create houses and appoint their heads")
                                .addSubcommands(
                                        new SubcommandData("add", "Found a house under a head")
                                                .addOption(OptionType.STRING, "name", "Its name", true)
                                                .addOption(OptionType.USER, "head", "Who leads it", true),
                                        new SubcommandData("remove", "Dissolve a house")
                                                .addOption(OptionType.STRING, "name", "Which house",
                                                        true, true),
                                        new SubcommandData("addhead", "Appoint another head")
                                                .addOption(OptionType.STRING, "name", "Which house",
                                                        true, true)
                                                .addOption(OptionType.USER, "bard", "Who", true),
                                        new SubcommandData("removehead", "Stand a head down")
                                                .addOption(OptionType.STRING, "name", "Which house",
                                                        true, true)
                                                .addOption(OptionType.USER, "bard", "Who", true)),
                        new SubcommandGroupData("leaderboard",
                                "Standing boards that keep themselves up to date")
                                .addSubcommands(
                                        new SubcommandData("virtue",
                                                "Put the total virtue board in this channel"),
                                        new SubcommandData("house",
                                                "Put the house renown board in this channel")),
                        new SubcommandGroupData("title", "Government titles")
                                .addSubcommands(
                                        new SubcommandData("grant", "Give a Bard a government title")
                                                .addOption(OptionType.USER, "bard", "Who", true)
                                                .addOption(OptionType.STRING, "title",
                                                        "Written as it should read", true),
                                        new SubcommandData("revoke", "Take one back")
                                                .addOption(OptionType.USER, "bard", "Who", true)
                                                .addOption(OptionType.STRING, "title", "Which one",
                                                        true, true)));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) throws Exception {
        if (!tribunal.check(event)) {
            return;
        }
        String subcommand = Objects.requireNonNull(event.getSubcommandName());
        String group = event.getSubcommandGroup();
        if (group == null) {
            // The only subcommand that is not in a group.
            if ("help".equals(subcommand)) {
                event.replyEmbeds(help()).setEphemeral(true).queue();
            } else {
                Replies.problem(event, "That is not something this command does.");
            }
            return;
        }

        switch (group) {
            case "channel" -> channel(event);
            case "goal" -> goal(event, subcommand);
            case "house" -> house(event, subcommand);
            case "title" -> title(event, subcommand);
            case "leaderboard" -> leaderboard(event, subcommand);
            default -> Replies.problem(event, "That is not something this command does.");
        }
    }

    /**
     * Plants a board in this channel.
     *
     * <p>Posted once and then edited forever. The message ID is remembered so a restart keeps
     * updating the same message rather than leaving it stale and posting another.
     */
    private void leaderboard(SlashCommandInteractionEvent event, String subcommand) {
        BoardKind kind = BoardKind.byKey(subcommand).orElseThrow();
        event.getChannel().sendMessageEmbeds(boards.render(kind)).queue(message -> {
            try {
                settings.setBoard(kind, event.getChannelId(), message.getId());
                Replies.quietly(event, kind.display()
                        + " will be kept up to date here from now on.");
            } catch (Exception e) {
                Replies.failed(event, "admin", e);
            }
        });
    }

    private void channel(SlashCommandInteractionEvent event) throws Exception {
        ChannelRole role = ChannelRole.byKey(
                event.getOption("which", "", OptionMapping::getAsString)).orElseThrow();
        settings.setChannel(role, event.getChannelId());
        Replies.quietly(event, role.display() + " messages will be posted here from now on.");
    }

    private void goal(SlashCommandInteractionEvent event, String subcommand) throws Exception {
        if ("list".equals(subcommand)) {
            List<Goal> goals = catalogue.current().goals();
            if (goals.isEmpty()) {
                Replies.quietly(event, "No goals have been set yet.");
                return;
            }
            StringBuilder rows = new StringBuilder();
            for (Goal goal : goals) {
                rows.append(goal.virtue().map(Virtue::display).orElse("Total"))
                        .append(' ').append(goal.threshold())
                        .append(": ").append(goal.title().orElse("(no title)"))
                        .append('\n');
            }
            Replies.quietly(event, rows.toString());
            return;
        }

        Optional<Virtue> virtue = Virtue.byKey(
                event.getOption("virtue", "", OptionMapping::getAsString));
        int threshold = event.getOption("threshold", 0, OptionMapping::getAsInt);
        if (threshold <= 0) {
            Replies.problem(event, "A goal has to be a score above zero.");
            return;
        }

        if ("add".equals(subcommand)) {
            catalogue.addGoal(new Goal(virtue, threshold,
                    optional(event, "title"), optional(event, "note")));
            Replies.quietly(event, "Goal set.");
        } else {
            boolean removed = catalogue.removeGoal(virtue, threshold);
            Replies.quietly(event, removed
                    ? "Goal removed."
                    : "There is no goal at that score.");
        }
    }

    private void house(SlashCommandInteractionEvent event, String subcommand) throws Exception {
        try {
            switch (subcommand) {
                case "add" -> {
                    User head = event.getOption("head", OptionMapping::getAsUser);
                    House created = houses.add(
                            event.getOption("name", "", OptionMapping::getAsString),
                            Objects.requireNonNull(head).getId());
                    Replies.quietly(event, created.name() + " has been founded under "
                            + head.getEffectiveName() + ".");
                }
                case "remove" -> {
                    Optional<House> house = named(event);
                    if (house.isEmpty()) {
                        Replies.problem(event, "There is no house by that name.");
                        return;
                    }
                    houses.remove(house.get().id());
                    Replies.quietly(event, house.get().name() + " has been dissolved. "
                            + "Its renown stays on record.");
                }
                case "addhead", "removehead" -> {
                    Optional<House> house = named(event);
                    if (house.isEmpty()) {
                        Replies.problem(event, "There is no house by that name.");
                        return;
                    }
                    User bard = Objects.requireNonNull(event.getOption("bard", OptionMapping::getAsUser));
                    if ("addhead".equals(subcommand)) {
                        houses.addHead(house.get().id(), bard.getId());
                        Replies.quietly(event, bard.getEffectiveName() + " now heads "
                                + house.get().name() + ".");
                    } else {
                        houses.removeHead(house.get().id(), bard.getId());
                        Replies.quietly(event, bard.getEffectiveName() + " no longer heads "
                                + house.get().name() + ".");
                    }
                }
                default -> Replies.problem(event, "That is not something this command does.");
            }
        } catch (HouseStore.HouseRejected rejected) {
            // A refusal somebody should read, not a fault worth a stack trace.
            Replies.problem(event, rejected.getMessage());
        }
    }

    private void title(SlashCommandInteractionEvent event, String subcommand) throws Exception {
        User bard = Objects.requireNonNull(event.getOption("bard", OptionMapping::getAsUser));
        String title = event.getOption("title", "", OptionMapping::getAsString).strip();
        if (title.isEmpty() || title.length() > House.MAX_TITLE) {
            Replies.problem(event, "A title has to be between 1 and "
                    + House.MAX_TITLE + " characters.");
            return;
        }

        if ("grant".equals(subcommand)) {
            profiles.update(bard.getId(), current -> withGovernment(current, title, true));
            Replies.quietly(event, bard.getEffectiveName() + " is now " + title + ".");
        } else {
            profiles.update(bard.getId(), current -> withGovernment(current, title, false));
            Replies.quietly(event, bard.getEffectiveName() + " no longer holds " + title + ".");
        }
    }

    /**
     * Suggestions for the options that name something already stored.
     *
     * <p>House names and granted titles are both written by people, so offering them is the
     * difference between a command that works and one that fails on capitalisation.
     */
    @Override
    public void autocomplete(CommandAutoCompleteInteractionEvent event) {
        String typed = event.getFocusedOption().getValue().toLowerCase(Locale.ROOT);
        List<Command.Choice> choices = new ArrayList<>();

        if ("name".equals(event.getFocusedOption().getName())) {
            houses.all().stream()
                    .filter(house -> house.name().toLowerCase(Locale.ROOT).contains(typed))
                    .limit(25)
                    .forEach(house -> choices.add(
                            new Command.Choice(Names.plain(house.name()), house.name())));
        } else if ("title".equals(event.getFocusedOption().getName())) {
            User bard = event.getOption("bard", OptionMapping::getAsUser);
            if (bard != null) {
                profiles.get(bard.getId()).governmentTitles().stream()
                        .filter(title -> title.toLowerCase(Locale.ROOT).contains(typed))
                        .limit(25)
                        .forEach(title -> choices.add(
                                new Command.Choice(Names.plain(title), title)));
            }
        }
        event.replyChoices(choices).queue();
    }

    private Optional<House> named(SlashCommandInteractionEvent event) {
        return houses.byName(event.getOption("name", "", OptionMapping::getAsString));
    }

    private static Profile withGovernment(Profile profile, String title, boolean granted) {
        Set<String> held = new LinkedHashSet<>(profile.governmentTitles());
        if (granted) {
            held.add(title);
        } else {
            held.removeIf(existing -> existing.equalsIgnoreCase(title));
        }
        return new Profile(profile.userId(), profile.name(), profile.gender(), profile.age(),
                profile.description(), profile.imageUrl(), profile.wikiUrl(),
                profile.equipped(), held);
    }

    private static Optional<String> optional(SlashCommandInteractionEvent event, String name) {
        return Optional.ofNullable(event.getOption(name, OptionMapping::getAsString))
                .map(String::strip)
                .filter(value -> !value.isEmpty());
    }

    private MessageEmbed help() {
        return new EmbedBuilder()
                .setTitle("Tribunal")
                .setDescription("""
                        You can award virtue and configure the bot. Nobody else can.""")
                .addField("Awarding for something somebody posted",
                        """
                        **Hover the message** and click the `⋯` on the right, then **Apps**, \
                        then **Award Virtue**. On a phone, press and hold the message and pick \
                        **Apps**.

                        Four buttons appear, Honor, Merit, Glory, Fame. Click one. A box opens \
                        for the amount and the reason. Press Submit.

                        The bot replies to that message with the award and the Bard's new \
                        scores.""", false)
                .addField("Awarding without a message",
                        """
                        Run `/award`, pick the Bard, the virtue and the amount, and optionally a \
                        reason.

                        **To take an award back, award a negative amount**: for example `-3`. \
                        Awards are never deleted, so the record shows what happened and the \
                        correction alongside it.""", false)
                .addField("Setting up channels",
                        """
                        Go to the channel you want, then run `/admin channel set` and pick which \
                        messages belong there:

                        **Awards**: every award. **Unlocks**: only when somebody reaches a \
                        goal. **Renown**: the monthly house results; use a channel only the \
                        tribunal can read. **Console**: the bot's own errors.

                        You can point more than one at the same channel.""", false)
                .addField("Goals and titles",
                        """
                        `/admin goal add`: set a score that unlocks a virtue title. Adding one \
                        at a score that already has a goal replaces it.
                        `/admin goal remove`, `/admin goal list`: the rest.
                        `/admin title grant`: give a Bard a government title. Type it exactly \
                        as it should read, for example `of The Tribunal`.
                        `/admin title revoke`: take one back.""", false)
                .addField("Houses",
                        """
                        `/admin house add`: found a house. You must name a head at the same \
                        time, because a house without one cannot do anything.
                        `/admin house addhead` / `removehead`: change who leads it.
                        `/admin house remove`: dissolve it. Its past renown stays on record.""",
                        false)
                .addField("Standing leaderboards",
                        """
                        Go to a channel and run `/admin leaderboard virtue` or \
                        `/admin leaderboard house`. The bot posts one message there and keeps \
                        editing it as awards come in. Do not delete that message, if you do, \
                        run the command again to make a new one.""", false)
                .build();
    }
}
