package dev.capna.bardbot.discord.commands;

import dev.capna.bardbot.discord.ProfileEmbed;
import dev.capna.bardbot.discord.Replies;
import dev.capna.bardbot.discord.SlashCommand;
import dev.capna.bardbot.model.House;
import dev.capna.bardbot.model.Profile;
import dev.capna.bardbot.model.Virtue;
import dev.capna.bardbot.ops.Feature;
import dev.capna.bardbot.store.AwardLog;
import dev.capna.bardbot.store.HouseStore;
import dev.capna.bardbot.store.ProfileStore;
import dev.capna.bardbot.titles.Titles;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Shows a Bard's character, and lets them write it.
 *
 * <p>Editing is two modals rather than a command with seven options. A modal gives a real
 * multi-line box for lore and a family tree, prefills what is already there so an edit is a change
 * rather than a retype, and enforces lengths in the client before a round trip. Discord allows five
 * fields in one, which is what decides where the split falls.
 */
public final class ProfileCommand implements SlashCommand {

    private static final String NAME = "profile";
    private static final String IDENTITY_MODAL = NAME + ":identity";
    private static final String LORE_MODAL = NAME + ":lore";

    /** Enough to see who a house is without the roster crowding out the rest of the profile. */
    private static final int MAX_MEMBERS_SHOWN = 15;

    private final ProfileStore profiles;
    private final HouseStore houses;
    private final AwardLog awards;
    private final Titles titles;

    public ProfileCommand(ProfileStore profiles, HouseStore houses, AwardLog awards, Titles titles) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.houses = Objects.requireNonNull(houses, "houses");
        this.awards = Objects.requireNonNull(awards, "awards");
        this.titles = Objects.requireNonNull(titles, "titles");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<Feature> feature() {
        return Optional.of(Feature.PROFILES);
    }

    @Override
    public CommandData definition() {
        return Commands.slash(NAME, "A Bard's character, their virtues and their house")
                .addSubcommands(new SubcommandData("view", "Show a Bard's profile")
                        .addOption(OptionType.USER, "bard", "Whose profile to show. Yours if left out."))
                .addSubcommandGroups(new SubcommandGroupData("edit", "Change your own profile")
                        .addSubcommands(
                                new SubcommandData("identity", "Your name, gender, age, image and wiki page"),
                                new SubcommandData("lore", "Your lore and your family tree")));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        if ("edit".equals(event.getSubcommandGroup())) {
            Profile profile = profiles.get(event.getUser().getId());
            event.replyModal("identity".equals(event.getSubcommandName())
                    ? identityModal(profile)
                    : loreModal(profile)).queue();
            return;
        }

        User subject = Optional.ofNullable(event.getOption("bard"))
                .map(option -> option.getAsUser())
                .orElse(event.getUser());
        event.replyEmbeds(render(subject)).queue();
    }

    @Override
    public void modal(ModalInteractionEvent event) throws Exception {
        String userId = event.getUser().getId();

        if (IDENTITY_MODAL.equals(event.getModalId())) {
            Optional<String> image = value(event, "image");
            Optional<String> wiki = value(event, "wiki");
            if (image.isPresent() && !isImageLink(image.get())) {
                Replies.problem(event, "That image link needs to start with http and end in .png, "
                        + ".jpg, .gif or .webp. It has to be hosted somewhere Discord can fetch it, "
                        + "rather than uploaded here.");
                return;
            }
            if (wiki.isPresent() && !isLink(wiki.get())) {
                Replies.problem(event, "That wiki link needs to start with http or https.");
                return;
            }
            profiles.update(userId, current -> new Profile(userId,
                    value(event, "name"), value(event, "gender"), value(event, "age"),
                    current.description(),
                    image, wiki,
                    current.equipped(), current.governmentTitles()));
        } else if (LORE_MODAL.equals(event.getModalId())) {
            profiles.update(userId, current -> new Profile(userId,
                    current.name(), current.gender(), current.age(),
                    value(event, "description"),
                    current.imageUrl(), current.wikiUrl(),
                    current.equipped(), current.governmentTitles()));
        } else {
            Replies.problem(event, "That form is no longer open.");
            return;
        }

        event.replyEmbeds(render(event.getUser())).setEphemeral(true).queue();
    }

    /**
     * Builds the embed, including the standings every rank on it is read from.
     *
     * <p>All five are computed together because they are five passes over the same list, and doing
     * them separately would read the whole award log five times to draw one profile.
     */
    private net.dv8tion.jda.api.entities.MessageEmbed render(User subject) {
        // Pruned as it is drawn, so a title lost with a house or a correction stops being worn
        // without anything having to go and find every profile that was wearing it.
        Profile profile = titles.pruned(profiles.get(subject.getId()));
        Map<Virtue, Integer> scores = awards.scores(subject.getId());

        Map<Optional<Virtue>, Map<String, Integer>> positions = new HashMap<>();
        for (Virtue virtue : Virtue.values()) {
            positions.put(Optional.of(virtue), awards.standings(Optional.of(virtue)));
        }
        positions.put(Optional.empty(), awards.standings(Optional.empty()));

        Optional<House> house = houses.holding(subject.getId());
        return ProfileEmbed.of(profile, subject, scores, positions, house, familyTree(house));
    }

    private Modal identityModal(Profile profile) {
        return Modal.create(IDENTITY_MODAL, "Your character")
                .addComponents(
                        row("name", "Name", TextInputStyle.SHORT, Profile.MAX_NAME,
                                profile.name(), "Azurov"),
                        row("gender", "Gender", TextInputStyle.SHORT, Profile.MAX_GENDER,
                                profile.gender(), null),
                        row("age", "Age", TextInputStyle.SHORT, Profile.MAX_AGE,
                                profile.age(), null),
                        row("image", "Character image link", TextInputStyle.SHORT, Profile.MAX_URL,
                                profile.imageUrl(), "https://…/azurov.png"),
                        row("wiki", "Wiki page link", TextInputStyle.SHORT, Profile.MAX_URL,
                                profile.wikiUrl(), null))
                .build();
    }

    private Modal loreModal(Profile profile) {
        return Modal.create(LORE_MODAL, "Your lore")
                .addComponents(
                        row("description", "Brief lore", TextInputStyle.PARAGRAPH,
                                Profile.MAX_DESCRIPTION, profile.description(), null))
                .build();
    }

    /**
     * A house's head and the rest of its members, as the profile's family tree.
     *
     * <p>Members are shown by their character name where they have set one, since this is a
     * roleplay sheet and the Discord name is usually not the character's. The list is capped: a
     * large house would otherwise fill the whole profile with names.
     *
     * @return absent when the Bard is in no house, so the field is left off entirely
     */
    private Optional<String> familyTree(Optional<House> house) {
        if (house.isEmpty()) {
            return Optional.empty();
        }
        House held = house.get();

        String heads = held.headIds().stream()
                .map(this::characterName)
                .collect(java.util.stream.Collectors.joining(", "));

        java.util.List<String> members = held.memberIds().stream()
                .filter(id -> !held.isHead(id))
                .map(this::characterName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        StringBuilder tree = new StringBuilder();
        tree.append(held.headIds().size() == 1 ? "Head" : "Heads").append(": ")
                .append(heads.isEmpty() ? "None" : heads);
        if (!members.isEmpty()) {
            java.util.List<String> shown = members.size() > MAX_MEMBERS_SHOWN
                    ? members.subList(0, MAX_MEMBERS_SHOWN)
                    : members;
            tree.append("\nMembers: ").append(String.join(", ", shown));
            if (members.size() > shown.size()) {
                tree.append(" and ").append(members.size() - shown.size()).append(" more");
            }
        }
        return Optional.of(tree.toString());
    }

    /** Their character's name if they have written one, otherwise a mention that will resolve. */
    private String characterName(String userId) {
        return profiles.get(userId).name()
                .map(dev.capna.bardbot.discord.Names::escaped)
                .orElse("<@" + userId + ">");
    }

    /**
     * One field of a modal.
     *
     * <p>Never required. A profile is written a piece at a time, and a form that refuses to save
     * because somebody has not decided their character's age yet is a form people stop opening.
     * Clearing a field is how something is removed, so an empty box has to be a valid answer.
     */
    private static net.dv8tion.jda.api.interactions.components.ActionRow row(
            String id, String label, TextInputStyle style, int max,
            Optional<String> current, String placeholder) {
        TextInput.Builder input = TextInput.create(id, label, style)
                .setRequired(false)
                .setMaxLength(max);
        current.ifPresent(input::setValue);
        if (placeholder != null) {
            input.setPlaceholder(placeholder);
        }
        return net.dv8tion.jda.api.interactions.components.ActionRow.of(input.build());
    }

    private static Optional<String> value(ModalInteractionEvent event, String id) {
        ModalMapping mapping = event.getValue(id);
        if (mapping == null) {
            return Optional.empty();
        }
        String value = mapping.getAsString().strip();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    private static boolean isLink(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * Whether Discord will be able to draw this as a picture.
     *
     * <p>Checked by extension rather than by fetching it, because the bot deliberately makes no
     * requests of its own. The query string is dropped first: image hosts routinely append one, and
     * a link that ends in {@code .png?width=600} is still a png.
     */
    private static boolean isImageLink(String value) {
        if (!isLink(value)) {
            return false;
        }
        String path = value.toLowerCase(Locale.ROOT).split("[?#]", 2)[0];
        return path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg")
                || path.endsWith(".gif") || path.endsWith(".webp");
    }
}
