package dev.capna.bardbot.discord.commands;

import dev.capna.bardbot.discord.HouseEmbed;
import dev.capna.bardbot.discord.Names;
import dev.capna.bardbot.discord.Replies;
import dev.capna.bardbot.discord.SlashCommand;
import dev.capna.bardbot.houses.Renown;
import dev.capna.bardbot.model.House;
import dev.capna.bardbot.model.Standings;
import dev.capna.bardbot.ops.Feature;
import dev.capna.bardbot.store.HouseStore;
import dev.capna.bardbot.store.ProfileStore;
import dev.capna.bardbot.store.RenownStore;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The noble houses: reading them, running them, and joining them.
 *
 * <p>Founding a house and appointing its heads is tribunal business and lives under {@code /admin}.
 * Everything here is either public, or something a head does to their own house.
 */
public final class HouseCommand implements SlashCommand {

    private static final String NAME = "house";
    private static final String EDIT_MODAL = NAME + ":edit";

    private final HouseStore houses;
    private final ProfileStore profiles;
    private final Renown renown;
    private final RenownStore archive;

    public HouseCommand(HouseStore houses, ProfileStore profiles, Renown renown,
                        RenownStore archive) {
        this.houses = Objects.requireNonNull(houses, "houses");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.renown = Objects.requireNonNull(renown, "renown");
        this.archive = Objects.requireNonNull(archive, "archive");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<Feature> feature() {
        return Optional.of(Feature.HOUSES);
    }

    @Override
    public CommandData definition() {
        return Commands.slash(NAME, "Noble houses, their renown and their people")
                .addSubcommands(
                        new SubcommandData("view", "A house's profile")
                                .addOption(OptionType.STRING, "name",
                                        "Which house. Yours if left out.", false, true),
                        new SubcommandData("list", "Every house, with its renown and size"),
                        new SubcommandData("leaderboard", "Renown earned this month")
                                .addOption(OptionType.BOOLEAN, "all",
                                        "Renown earned ever, instead of this month"),
                        new SubcommandData("winner", "Who won last month"),
                        new SubcommandData("edit", "Change your house's motto, lore and crest"),
                        new SubcommandData("invite", "Ask a Bard to join your house")
                                .addOption(OptionType.USER, "bard", "Who", true),
                        new SubcommandData("accept", "Take up an invitation")
                                .addOption(OptionType.STRING, "name", "Which house",
                                        true, true),
                        new SubcommandData("decline", "Turn down an invitation")
                                .addOption(OptionType.STRING, "name", "Which house",
                                        true, true),
                        new SubcommandData("leave", "Leave your house"),
                        new SubcommandData("expel", "Turn a member out of your house")
                                .addOption(OptionType.USER, "bard", "Who", true))
                .addSubcommandGroups(new SubcommandGroupData("title", "Your house's own titles")
                        .addSubcommands(
                                new SubcommandData("add", "Define a title your house can grant")
                                        .addOption(OptionType.STRING, "title", "Its name", true),
                                new SubcommandData("remove", "Retire one")
                                        .addOption(OptionType.STRING, "title", "Which one",
                                                true, true),
                                new SubcommandData("grant", "Let a member wear one")
                                        .addOption(OptionType.USER, "bard", "Who", true)
                                        .addOption(OptionType.STRING, "title", "Which one",
                                                true, true),
                                new SubcommandData("revoke", "Take one back")
                                        .addOption(OptionType.USER, "bard", "Who", true)
                                        .addOption(OptionType.STRING, "title", "Which one",
                                                true, true)));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) throws Exception {
        String subcommand = Objects.requireNonNull(event.getSubcommandName());
        try {
            if ("title".equals(event.getSubcommandGroup())) {
                title(event, subcommand);
                return;
            }
            switch (subcommand) {
                case "view" -> view(event);
                case "list" -> list(event);
                case "leaderboard" -> leaderboard(event);
                case "winner" -> winner(event);
                case "edit" -> edit(event);
                case "invite" -> invite(event);
                case "accept" -> accept(event);
                case "decline" -> decline(event);
                case "leave" -> leave(event);
                case "expel" -> expel(event);
                default -> Replies.problem(event, "That is not something this command does.");
            }
        } catch (HouseStore.HouseRejected rejected) {
            Replies.problem(event, rejected.getMessage());
        }
    }

    private void view(SlashCommandInteractionEvent event) {
        Optional<House> house = event.getOption("name") == null
                ? houses.holding(event.getUser().getId())
                : houses.byName(event.getOption("name", "", OptionMapping::getAsString));

        if (house.isEmpty()) {
            Replies.problem(event, event.getOption("name") == null
                    ? "You are not in a house."
                    : "There is no house by that name.");
            return;
        }
        House held = house.get();
        event.replyEmbeds(HouseEmbed.of(held, profiles,
                renown.thisMonthFor(held.id()), renown.allTimeFor(held.id()),
                renown.placeThisMonth(held.id()))).queue();
    }

    private void list(SlashCommandInteractionEvent event) {
        List<House> all = houses.all();
        if (all.isEmpty()) {
            Replies.problem(event, "There are no houses yet.");
            return;
        }
        StringBuilder rows = new StringBuilder();
        for (House house : all) {
            rows.append(Names.escaped(house.name()))
                    .append(" — ").append(renown.allTimeFor(house.id())).append(" renown, ")
                    .append(house.everyone().size())
                    .append(house.everyone().size() == 1 ? " member" : " members")
                    .append('\n');
        }
        event.replyEmbeds(new net.dv8tion.jda.api.EmbedBuilder()
                .setTitle("The noble houses")
                .setDescription(rows.toString())
                .build()).queue();
    }

    private void leaderboard(SlashCommandInteractionEvent event) {
        boolean allTime = event.getOption("all", false, OptionMapping::getAsBoolean);
        event.replyEmbeds(HouseEmbed.leaderboard(
                allTime ? "Renown, all time" : "Renown this month",
                allTime ? renown.allTimeTable() : renown.thisMonthTable())).queue();
    }

    /**
     * Last month's result, read from the archive rather than recomputed.
     *
     * <p>A month that has been settled does not change. An award corrected in March must not
     * quietly rewrite who won February.
     */
    private void winner(SlashCommandInteractionEvent event) {
        Optional<Standings> latest = archive.latest();
        if (latest.isEmpty()) {
            Replies.problem(event, "No month has been settled yet.");
            return;
        }
        List<Standings.Place> winners = latest.get().winners();
        if (winners.isEmpty()) {
            Replies.quietly(event, "No renown was earned in " + latest.get().month() + ".");
            return;
        }
        String names = String.join(" and ", winners.stream()
                .map(place -> Names.escaped(place.houseName())).toList());
        event.reply(winners.size() == 1
                ? names + " won " + latest.get().month() + " with "
                        + winners.get(0).renown() + " renown."
                : names + " tied " + latest.get().month() + " on "
                        + winners.get(0).renown() + " renown.").queue();
    }

    private void edit(SlashCommandInteractionEvent event) {
        Optional<House> house = headOf(event);
        if (house.isEmpty()) {
            return;
        }
        House held = house.get();
        event.replyModal(Modal.create(EDIT_MODAL, Names.plain(held.name()))
                .addComponents(
                        ActionRow.of(TextInput.create("motto", "Motto", TextInputStyle.SHORT)
                                .setRequired(false).setMaxLength(House.MAX_MOTTO)
                                .setValue(held.motto().orElse(null)).build()),
                        ActionRow.of(TextInput.create("description", "Lore",
                                        TextInputStyle.PARAGRAPH)
                                .setRequired(false).setMaxLength(House.MAX_DESCRIPTION)
                                .setValue(held.description().orElse(null)).build()),
                        ActionRow.of(TextInput.create("crest", "Crest image link",
                                        TextInputStyle.SHORT)
                                .setRequired(false).setMaxLength(300)
                                .setValue(held.crestUrl().orElse(null)).build()))
                .build()).queue();
    }

    @Override
    public void modal(ModalInteractionEvent event) throws Exception {
        if (!EDIT_MODAL.equals(event.getModalId())) {
            Replies.problem(event, "That form is no longer open.");
            return;
        }
        Optional<House> house = houses.holding(event.getUser().getId());
        if (house.isEmpty() || !house.get().isHead(event.getUser().getId())) {
            Replies.problem(event, "Only a head can change their house.");
            return;
        }
        houses.edit(house.get().id(), value(event, "motto"), value(event, "description"),
                value(event, "crest"));
        Replies.quietly(event, "Your house has been updated.");
    }

    private void invite(SlashCommandInteractionEvent event) throws Exception {
        Optional<House> house = headOf(event);
        if (house.isEmpty()) {
            return;
        }
        User bard = Objects.requireNonNull(event.getOption("bard", OptionMapping::getAsUser));
        if (bard.isBot()) {
            Replies.problem(event, "A bot cannot join a house.");
            return;
        }
        houses.invite(house.get().id(), bard.getId());
        event.reply("<@" + bard.getId() + "> — " + Names.escaped(house.get().name())
                + " has invited you. Accept with /house accept, or turn it down with "
                + "/house decline.").queue();
    }

    private void accept(SlashCommandInteractionEvent event) throws Exception {
        Optional<House> house = houses.byName(event.getOption("name", "", OptionMapping::getAsString));
        if (house.isEmpty()) {
            Replies.problem(event, "There is no house by that name.");
            return;
        }
        House joined = houses.accept(house.get().id(), event.getUser().getId());
        event.reply(event.getUser().getEffectiveName() + " has joined "
                + Names.escaped(joined.name()) + ".").queue();
    }

    private void decline(SlashCommandInteractionEvent event) throws Exception {
        Optional<House> house = houses.byName(event.getOption("name", "", OptionMapping::getAsString));
        if (house.isPresent()) {
            houses.decline(house.get().id(), event.getUser().getId());
        }
        Replies.quietly(event, "Invitation declined.");
    }

    private void leave(SlashCommandInteractionEvent event) throws Exception {
        Optional<House> left = houses.leave(event.getUser().getId());
        Replies.quietly(event, left
                .map(house -> "You have left " + house.name()
                        + ". Its titles are no longer yours.")
                .orElse("You are not in a house."));
    }

    private void expel(SlashCommandInteractionEvent event) throws Exception {
        Optional<House> house = headOf(event);
        if (house.isEmpty()) {
            return;
        }
        User bard = Objects.requireNonNull(event.getOption("bard", OptionMapping::getAsUser));
        houses.expel(house.get().id(), bard.getId());
        Replies.quietly(event, bard.getEffectiveName() + " is no longer in "
                + house.get().name() + ".");
    }

    private void title(SlashCommandInteractionEvent event, String subcommand) throws Exception {
        Optional<House> house = headOf(event);
        if (house.isEmpty()) {
            return;
        }
        String id = house.get().id();
        String title = event.getOption("title", "", OptionMapping::getAsString).strip();

        switch (subcommand) {
            case "add" -> {
                houses.addTitle(id, title);
                Replies.quietly(event, house.get().name() + " may now grant " + title + ".");
            }
            case "remove" -> {
                houses.removeTitle(id, title);
                Replies.quietly(event, title + " is no longer one of "
                        + house.get().name() + "'s titles.");
            }
            case "grant" -> {
                User bard = Objects.requireNonNull(event.getOption("bard", OptionMapping::getAsUser));
                houses.grantTitle(id, bard.getId(), title);
                Replies.quietly(event, bard.getEffectiveName() + " may now wear " + title + ".");
            }
            case "revoke" -> {
                User bard = Objects.requireNonNull(event.getOption("bard", OptionMapping::getAsUser));
                houses.revokeTitle(id, bard.getId(), title);
                Replies.quietly(event, bard.getEffectiveName() + " may no longer wear "
                        + title + ".");
            }
            default -> Replies.problem(event, "That is not something this command does.");
        }
    }

    /**
     * The house this Bard heads, or nothing, having already said why.
     *
     * <p>Refusing here rather than in each command keeps the two failures — not in a house, in one
     * but not leading it — worded the same wherever they happen.
     */
    private Optional<House> headOf(SlashCommandInteractionEvent event) {
        Optional<House> house = houses.holding(event.getUser().getId());
        if (house.isEmpty()) {
            Replies.problem(event, "You are not in a house.");
            return Optional.empty();
        }
        if (!house.get().isHead(event.getUser().getId())) {
            Replies.problem(event, "Only a head can do that.");
            return Optional.empty();
        }
        return house;
    }

    @Override
    public void autocomplete(CommandAutoCompleteInteractionEvent event) {
        String option = event.getFocusedOption().getName();
        String typed = event.getFocusedOption().getValue().toLowerCase(Locale.ROOT);
        List<Command.Choice> choices = new ArrayList<>();

        if ("name".equals(option)) {
            // On accept and decline, only the houses that have actually asked for them.
            String subcommand = event.getSubcommandName();
            List<House> candidates = "accept".equals(subcommand) || "decline".equals(subcommand)
                    ? houses.invitationsFor(event.getUser().getId())
                    : houses.all();
            candidates.stream()
                    .filter(house -> house.name().toLowerCase(Locale.ROOT).contains(typed))
                    .limit(25)
                    .forEach(house -> choices.add(
                            new Command.Choice(Names.plain(house.name()), house.name())));
        } else if ("title".equals(option)) {
            houses.holding(event.getUser().getId()).ifPresent(house -> house.nobleTitles().stream()
                    .filter(title -> title.toLowerCase(Locale.ROOT).contains(typed))
                    .limit(25)
                    .forEach(title -> choices.add(
                            new Command.Choice(Names.plain(title), title))));
        }
        event.replyChoices(choices).queue();
    }

    private static Optional<String> value(ModalInteractionEvent event, String id) {
        ModalMapping mapping = event.getValue(id);
        if (mapping == null) {
            return Optional.empty();
        }
        String value = mapping.getAsString().strip();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
