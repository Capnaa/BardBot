package dev.capna.bardbot.discord.commands;

import dev.capna.bardbot.discord.Names;
import dev.capna.bardbot.discord.Replies;
import dev.capna.bardbot.discord.SlashCommand;
import dev.capna.bardbot.model.Profile;
import dev.capna.bardbot.model.TitleSlot;
import dev.capna.bardbot.ops.Feature;
import dev.capna.bardbot.store.ProfileStore;
import dev.capna.bardbot.titles.Titles;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Choosing which titles a Bard wears.
 *
 * <p>Nothing is typed. The title option autocompletes from what that Bard actually holds in that
 * slot, so a title they have not been granted never appears, and the name is never spelled wrong.
 */
public final class TitleCommand implements SlashCommand {

    private static final String NAME = "title";

    /** Clears a slot. Not a title anybody holds, so it is offered rather than stored. */
    private static final String NONE = "None";

    private final Titles titles;
    private final ProfileStore profiles;

    public TitleCommand(Titles titles, ProfileStore profiles) {
        this.titles = Objects.requireNonNull(titles, "titles");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<Feature> feature() {
        return Optional.of(Feature.TITLES);
    }

    @Override
    public CommandData definition() {
        OptionData slot = new OptionData(OptionType.STRING, "slot", "Which title", true);
        Arrays.stream(TitleSlot.values())
                .forEach(value -> slot.addChoice(value.display(), value.key()));

        return Commands.slash(NAME, "The titles you wear")
                .addSubcommands(
                        new SubcommandData("set", "Wear a title, or clear the slot")
                                .addOptions(slot)
                                .addOption(OptionType.STRING, "title",
                                        "Which one. Pick from the list.", true, true),
                        new SubcommandData("list", "Every title a Bard holds")
                                .addOption(OptionType.USER, "bard",
                                        "Whose titles. Yours if left out."),
                        new SubcommandData("roster", "Every Bard holding a government title"));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) throws Exception {
        switch (Objects.requireNonNull(event.getSubcommandName())) {
            case "set" -> set(event);
            case "list" -> {
                User subject = Optional.ofNullable(event.getOption("bard", OptionMapping::getAsUser))
                        .orElse(event.getUser());
                event.replyEmbeds(list(subject)).queue();
            }
            case "roster" -> event.replyEmbeds(roster(event)).queue();
            default -> Replies.problem(event, "That is not something this command does.");
        }
    }

    private void set(SlashCommandInteractionEvent event) throws Exception {
        String userId = event.getUser().getId();
        TitleSlot slot = TitleSlot.byKey(event.getOption("slot", "", OptionMapping::getAsString))
                .orElseThrow();
        String choice = event.getOption("title", "", OptionMapping::getAsString).strip();

        if (choice.equalsIgnoreCase(NONE) || choice.isEmpty()) {
            profiles.update(userId, current -> withTitle(current, slot, Optional.empty()));
            Replies.quietly(event, "Your " + slot.display().toLowerCase(Locale.ROOT)
                    + " title has been cleared.");
            return;
        }

        // Matched against what they hold rather than trusted, because the option is only
        // autocompleted: anybody can type whatever they like into it.
        Optional<String> held = titles.available(userId, slot).stream()
                .filter(title -> title.equalsIgnoreCase(choice))
                .findFirst();
        if (held.isEmpty()) {
            Replies.problem(event, "You do not hold that title.");
            return;
        }

        profiles.update(userId, current -> withTitle(current, slot, held));
        Replies.quietly(event, "You are now " + held.get() + ".");
    }

    @Override
    public void autocomplete(CommandAutoCompleteInteractionEvent event) {
        if (!"title".equals(event.getFocusedOption().getName())) {
            event.replyChoices().queue();
            return;
        }
        Optional<TitleSlot> slot = TitleSlot.byKey(
                event.getOption("slot", "", OptionMapping::getAsString));
        if (slot.isEmpty()) {
            event.replyChoices().queue();
            return;
        }

        String typed = event.getFocusedOption().getValue().toLowerCase(Locale.ROOT);
        List<Command.Choice> choices = new ArrayList<>();
        // Offered first, so clearing a slot never means scrolling past everything you own.
        choices.add(new Command.Choice(NONE, NONE));
        titles.available(event.getUser().getId(), slot.get()).stream()
                .filter(title -> title.toLowerCase(Locale.ROOT).contains(typed))
                .limit(24)
                .forEach(title -> choices.add(new Command.Choice(Names.plain(title), title)));

        event.replyChoices(choices).queue();
    }

    private MessageEmbed list(User subject) {
        Profile profile = titles.pruned(profiles.get(subject.getId()));
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Titles held by " + Names.plain(subject.getEffectiveName()));

        Map<TitleSlot, List<String>> held = new EnumMap<>(TitleSlot.class);
        Arrays.stream(TitleSlot.values())
                .forEach(slot -> held.put(slot, titles.available(subject.getId(), slot)));

        if (held.values().stream().allMatch(List::isEmpty)) {
            embed.setDescription("They hold no titles yet.");
            return embed.build();
        }

        held.forEach((slot, available) -> {
            if (available.isEmpty()) {
                return;
            }
            String worn = profile.title(slot).orElse("");
            // The equipped one is ticked rather than listed separately, so the slot reads as one
            // set of options with one of them chosen.
            String rows = String.join("\n", available.stream()
                    .map(title -> (title.equalsIgnoreCase(worn) ? "✓ " : "  ") + Names.escaped(title))
                    .toList());
            embed.addField(slot.display(), rows, false);
        });
        return embed.build();
    }

    /**
     * Everyone holding a government title.
     *
     * <p>Government titles are free text, so this is also where a typo is noticed. There is no
     * equivalent for the other two slots: virtue titles are a function of the leaderboards, and a
     * house's titles are the house's own business.
     */
    private MessageEmbed roster(SlashCommandInteractionEvent event) {
        EmbedBuilder embed = new EmbedBuilder().setTitle("Government titles");

        StringBuilder rows = new StringBuilder();
        profiles.all().stream()
                .filter(profile -> !profile.governmentTitles().isEmpty())
                .forEach(profile -> rows
                        .append("<@").append(profile.userId()).append(">: ")
                        .append(String.join(", ", profile.governmentTitles().stream()
                                .map(Names::escaped).toList()))
                        .append('\n'));

        embed.setDescription(rows.isEmpty()
                ? "Nobody holds a government title yet."
                : rows.toString());
        return embed.build();
    }

    private static Profile withTitle(Profile profile, TitleSlot slot, Optional<String> title) {
        Map<TitleSlot, String> equipped = new EnumMap<>(TitleSlot.class);
        equipped.putAll(profile.equipped());
        title.ifPresentOrElse(value -> equipped.put(slot, value), () -> equipped.remove(slot));
        return new Profile(profile.userId(), profile.name(), profile.gender(), profile.age(),
                profile.description(), profile.imageUrl(), profile.wikiUrl(),
                equipped, profile.governmentTitles());
    }
}
