package dev.capna.bardbot.discord.commands;

import dev.capna.bardbot.discord.AwardEmbed;
import dev.capna.bardbot.discord.LiveBoards;
import dev.capna.bardbot.discord.Replies;
import dev.capna.bardbot.discord.SlashCommand;
import dev.capna.bardbot.discord.Tribunal;
import dev.capna.bardbot.model.Award;
import dev.capna.bardbot.model.Virtue;
import dev.capna.bardbot.ops.ChannelRole;
import dev.capna.bardbot.ops.Feature;
import dev.capna.bardbot.ops.SettingsStore;
import dev.capna.bardbot.path.PathOfVirtue;
import dev.capna.bardbot.virtue.Awarding;
import dev.capna.bardbot.virtue.Unlocks;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One award, to everybody who earned it.
 *
 * <p>A war, a build day or an event is one decision about twenty people, and making it twenty
 * times is both tedious and the way somebody gets missed. The Bards are picked one by one from
 * Discord's own chooser, so each is confirmed as a real person as it is picked rather than at the
 * moment the awards are being written.
 *
 * <p>There is also a box to paste into, for a list that came from somewhere else. It takes
 * mentions or bare IDs, mixed with whatever words are around them, which is what a roster copied
 * out of a channel actually looks like.
 *
 * <p>Every award is recorded individually through the same {@link Awarding} as everything else, so
 * nothing here is a special kind of award: each Bard's history shows a normal entry, renown lands
 * on whichever house held them, and a correction works the usual way. Only the announcement is
 * pooled, because fifteen embeds about one decision is a channel nobody reads.
 *
 * <p>There is no confirmation step, for the same reason the rest of the bot has none: the award
 * log is append only, and the correction for a mistake is the opposite award. Running it again
 * with the same Bards and a negative amount undoes it exactly, and leaves both halves on the
 * record.
 */
public final class MassAwardCommand implements SlashCommand {

    private static final Logger LOG = LoggerFactory.getLogger(MassAwardCommand.class);

    private static final String NAME = "massaward";

    /**
     * How many Bards can be picked from the chooser in one go.
     *
     * <p>Discord allows twenty-five options on a command and four of them are already spoken for.
     * Twelve is well inside that and still longer than most events, and anything longer belongs in
     * the paste box rather than in twelve more dropdowns nobody scrolls past.
     */
    static final int PICKERS = 12;

    /**
     * How many Bards one command may award.
     *
     * <p>Not a performance limit. It is the difference between a slip that awards a dozen people
     * by mistake, which is a minute's work to undo, and one that awards the whole server.
     */
    static final int MAX_RECIPIENTS = 50;

    /** A mention as Discord writes it, with the optional {@code !} older clients still send. */
    private static final Pattern MENTION = Pattern.compile("<@!?(\\d{17,20})>");

    /** A raw ID, for anybody who pastes from the developer menu rather than typing an @. */
    private static final Pattern RAW_ID = Pattern.compile("(?<![\\d<@!])(\\d{17,20})(?![\\d>])");

    private final Tribunal tribunal;
    private final Awarding awarding;
    private final Unlocks unlocks;
    private final SettingsStore settings;
    private final LiveBoards boards;
    private final PathOfVirtue path;

    public MassAwardCommand(Tribunal tribunal, Awarding awarding, Unlocks unlocks,
                            SettingsStore settings, LiveBoards boards, PathOfVirtue path) {
        this.tribunal = Objects.requireNonNull(tribunal, "tribunal");
        this.awarding = Objects.requireNonNull(awarding, "awarding");
        this.unlocks = Objects.requireNonNull(unlocks, "unlocks");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.boards = Objects.requireNonNull(boards, "boards");
        this.path = Objects.requireNonNull(path, "path");
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

        SlashCommandData command = Commands.slash(NAME,
                        "Award the same virtue to several Bards at once")
                .addOptions(virtue)
                .addOption(OptionType.INTEGER, "amount",
                        "How much, each. Negative takes it back.", true)
                .addOption(OptionType.USER, "bard1", "Who to award", true);

        // The rest optional, so the command is usable with two Bards without filling in ten
        // blanks. Discord shows them in this order, which is why they are numbered.
        for (int slot = 2; slot <= PICKERS; slot++) {
            command.addOption(OptionType.USER, "bard" + slot, "Another Bard", false);
        }

        return command
                .addOption(OptionType.STRING, "more",
                        "More Bards, pasted as mentions or IDs. For a list too long to pick.",
                        false)
                .addOption(OptionType.STRING, "reason", "What it is for", false);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) throws Exception {
        if (!tribunal.check(event)) {
            return;
        }

        int amount = event.getOption("amount", 0, OptionMapping::getAsInt);
        if (amount == 0 || Math.abs(amount) > Awarding.MAX_AMOUNT) {
            Replies.problem(event, "The amount has to be a whole number between -"
                    + Awarding.MAX_AMOUNT + " and " + Awarding.MAX_AMOUNT + ", and not zero.");
            return;
        }

        // Looking up a pasted ID can take a round trip, and writing the awards certainly does.
        Replies.defer(event, NAME);

        Map<String, String> recipients = gather(event);
        if (recipients.isEmpty()) {
            event.getHook().sendMessage("Nobody to award. Virtue can only go to Bards, not bots.")
                    .setEphemeral(true).queue();
            return;
        }
        if (recipients.size() > MAX_RECIPIENTS) {
            event.getHook().sendMessage("That would award " + recipients.size()
                            + " Bards, and one command stops at " + MAX_RECIPIENTS
                            + ". Do it in batches.")
                    .setEphemeral(true).queue();
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

        award(event, virtue, amount, reason, recipients);
    }

    /**
     * Records every award, then announces them together.
     *
     * <p>One Bard failing does not stop the rest. A half-finished mass award is worse than one
     * with a gap in it, because the gap is named in the reply and can be filled with a single
     * command, where the half-finished one leaves nobody sure how far it got.
     */
    private void award(SlashCommandInteractionEvent event, Virtue virtue, int amount,
                       Optional<String> reason, Map<String, String> recipients) {
        List<String> awarded = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        recipients.forEach((userId, name) -> {
            try {
                Awarding.Result result = awarding.apply(userId, event.getUser().getId(),
                        virtue, amount, reason);
                awarded.add(userId);
                names.add(name);
                unlocks.announce(event.getJDA(), userId, result.crossed());
                path.advance(event.getJDA(), userId);
            } catch (Exception e) {
                // Named in the reply rather than only logged, since the tribunal member standing
                // there is the only one who can do anything about it.
                LOG.error("Could not award {} in a mass award", userId, e);
                failed.add(name);
            }
        });

        if (awarded.isEmpty()) {
            event.getHook().sendMessage("Nothing was awarded. It has been logged.")
                    .setEphemeral(true).queue();
            return;
        }

        LOG.info("{} mass awarded {} {} to {} Bard(s)",
                event.getUser().getId(), amount, virtue.key(), awarded.size());

        MessageChannel destination = settings.channel(ChannelRole.AWARDS)
                .map(id -> (MessageChannel) event.getJDA().getTextChannelById(id))
                .filter(Objects::nonNull)
                .orElse(event.getChannel());

        var embed = AwardEmbed.bulk(virtue, amount, awarded, names, reason, event.getUser());
        String note = failed.isEmpty()
                ? ""
                : "\n\n" + failed.size() + " could not be awarded: " + String.join(", ", failed);

        if (destination.getId().equals(event.getChannelId())) {
            event.getHook().sendMessageEmbeds(embed).queue();
            if (!failed.isEmpty()) {
                event.getHook().sendMessage(note.strip()).setEphemeral(true).queue();
            }
        } else {
            destination.sendMessageEmbeds(embed).queue();
            event.getHook().sendMessage("Awarded " + awarded.size()
                            + ". Posted in <#" + destination.getId() + ">." + note)
                    .setEphemeral(true).queue();
        }

        boards.refreshSoon(event.getJDA());
    }

    /**
     * Who is being awarded: everyone picked, then everyone pasted.
     *
     * <p>Kept in a map keyed by ID so the same Bard picked twice, or picked and then pasted, is
     * awarded once. Ordered, so the announcement reads the way it was asked for. Bots are dropped
     * silently, since nobody means to award one.
     */
    private Map<String, String> gather(SlashCommandInteractionEvent event) {
        Map<String, String> found = new LinkedHashMap<>();

        for (int slot = 1; slot <= PICKERS; slot++) {
            User picked = event.getOption("bard" + slot, OptionMapping::getAsUser);
            if (picked != null && !picked.isBot()) {
                found.putIfAbsent(picked.getId(), picked.getEffectiveName());
            }
        }

        for (String id : ids(event.getOption("more", "", OptionMapping::getAsString))) {
            if (found.containsKey(id)) {
                continue;
            }
            Member member = event.getGuild() == null ? null : event.getGuild().getMemberById(id);
            if (member != null) {
                if (!member.getUser().isBot()) {
                    found.put(id, member.getEffectiveName());
                }
                continue;
            }
            try {
                var user = event.getJDA().retrieveUserById(id).complete();
                if (!user.isBot()) {
                    found.put(id, user.getEffectiveName());
                }
            } catch (RuntimeException e) {
                LOG.warn("No such Bard in a mass award: {}", id);
            }
        }
        return found;
    }

    /** Every Bard named in a pasted string, whether mentioned or pasted as a bare ID. */
    static List<String> ids(String pasted) {
        List<String> ids = new ArrayList<>();
        Matcher mentions = MENTION.matcher(pasted);
        while (mentions.find()) {
            if (!ids.contains(mentions.group(1))) {
                ids.add(mentions.group(1));
            }
        }
        Matcher raw = RAW_ID.matcher(MENTION.matcher(pasted).replaceAll(" "));
        while (raw.find()) {
            if (!ids.contains(raw.group(1))) {
                ids.add(raw.group(1));
            }
        }
        return ids;
    }
}
