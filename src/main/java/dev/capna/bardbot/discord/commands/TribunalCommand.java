package dev.capna.bardbot.discord.commands;

import dev.capna.bardbot.discord.Emperor;
import dev.capna.bardbot.discord.Names;
import dev.capna.bardbot.discord.Replies;
import dev.capna.bardbot.discord.SlashCommand;
import dev.capna.bardbot.discord.Tribunal;
import dev.capna.bardbot.model.Virtue;
import dev.capna.bardbot.model.WritTask;
import dev.capna.bardbot.ops.ChannelRole;
import dev.capna.bardbot.ops.Feature;
import dev.capna.bardbot.ops.SettingsStore;
import dev.capna.bardbot.store.WritStore;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
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
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Imperial writs: spending them, and what happens to the tasks they are spent on.
 *
 * <p>Everything here is tribunal only, which is why it lives under {@code /tribunal} rather than
 * beside {@code /house}: the name says who it is for. Striking a task is narrower still and is
 * gated on the Emperor as well.
 *
 * <p>Three of the subcommands open a form, because the task, the coords and the link are the kind
 * of text nobody wants to type into a slash command option. A form cannot be opened after
 * deferring, so those three do their checking first and defer nothing.
 */
public final class TribunalCommand implements SlashCommand {

    private static final Logger LOG = LoggerFactory.getLogger(TribunalCommand.class);

    private static final String NAME = "tribunal";
    private static final String TASK_MODAL = NAME + ":task";
    private static final String USE_MODAL = NAME + ":use";
    private static final String COMPLETE_MODAL = NAME + ":complete";

    /** Discord caps a forum post's name at a hundred; this leaves room for the prefix. */
    private static final int MAX_TITLE = 80;
    private static final int MAX_DESCRIPTION = 1500;

    private final Tribunal tribunal;
    private final Emperor emperor;
    private final WritStore writs;
    private final SettingsStore settings;

    public TribunalCommand(Tribunal tribunal, Emperor emperor, WritStore writs,
                           SettingsStore settings) {
        this.tribunal = Objects.requireNonNull(tribunal, "tribunal");
        this.emperor = Objects.requireNonNull(emperor, "emperor");
        this.writs = Objects.requireNonNull(writs, "writs");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Optional<Feature> feature() {
        return Optional.of(Feature.WRITS);
    }

    @Override
    public CommandData definition() {
        return Commands.slash(NAME, "Tribunal business")
                .addSubcommandGroups(new SubcommandGroupData("writ", "Imperial writs")
                        .addSubcommands(
                                new SubcommandData("view",
                                        "Your writs, the tasks on you, and the tasks you have served"),
                                new SubcommandData("task",
                                        "Spend a writ on a public task in The Virtue Board"),
                                new SubcommandData("use",
                                        "Spend a writ on a task for another tribunal member")
                                        .addOption(OptionType.USER, "member", "Who", true),
                                new SubcommandData("complete", "Mark a task on you as done")
                                        .addOption(OptionType.INTEGER, "number",
                                                "Its number in /tribunal writ view", true),
                                new SubcommandData("cancel",
                                        "Take back a task you served that has not been done")
                                        .addOption(OptionType.INTEGER, "number",
                                                "Its number in /tribunal writ view", true),
                                new SubcommandData("strike",
                                        "Throw out an unreasonable task. Emperor only.")
                                        .addOption(OptionType.USER, "member",
                                                "Who the task is on", true)
                                        .addOption(OptionType.INTEGER, "number",
                                                "Its number in their list", true)));
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) throws Exception {
        if (!tribunal.check(event)) {
            return;
        }
        String subcommand = Objects.requireNonNull(event.getSubcommandName());
        try {
            switch (subcommand) {
                case "view" -> view(event);
                case "task" -> task(event);
                case "use" -> use(event);
                case "complete" -> complete(event);
                case "cancel" -> cancel(event);
                case "strike" -> strike(event);
                default -> Replies.problem(event, "That is not something this command does.");
            }
        } catch (WritStore.WritRejected rejected) {
            Replies.problem(event, rejected.getMessage());
        }
    }

    private void view(SlashCommandInteractionEvent event) {
        String userId = event.getUser().getId();
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Your writs")
                .setDescription("You are holding **" + writs.held(userId) + "** of "
                        + WritStore.MAX_HELD + ".");

        List<WritTask> onYou = writs.openOn(userId);
        embed.addField("On you", onYou.isEmpty() ? "Nothing." : rows(onYou, true), false);

        List<WritTask> byYou = writs.openBy(userId);
        embed.addField("Served by you, still open",
                byYou.isEmpty() ? "Nothing." : rows(byYou, false), false);

        event.replyEmbeds(embed.build()).setEphemeral(true).queue();
    }

    /**
     * One line per task, numbered the way the store numbers them.
     *
     * <p>Outside a code fence, so the mention renders as a name. The task itself is player
     * written and escaped for the same reason every house motto is.
     */
    private static String rows(List<WritTask> tasks, boolean showServer) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < tasks.size(); i++) {
            WritTask task = tasks.get(i);
            rows.append("**").append(i + 1).append(".** ")
                    .append(Names.escaped(Names.fit(task.task(), 120)))
                    .append(", due ").append(task.dueDate())
                    .append(showServer ? ", served by <@" : ", on <@")
                    .append(showServer ? task.servedBy() : task.servedOn())
                    .append(">\n");
        }
        return rows.toString();
    }

    private void task(SlashCommandInteractionEvent event) {
        if (!hasWrits(event)) {
            return;
        }
        if (settings.channel(ChannelRole.VIRTUE_BOARD).isEmpty()) {
            Replies.problem(event, "No Virtue Board has been set. Run /admin channel set in "
                    + "the forum first.");
            return;
        }
        event.replyModal(Modal.create(TASK_MODAL, "A public task")
                .addComponents(
                        ActionRow.of(TextInput.create("title", "Title", TextInputStyle.SHORT)
                                .setRequired(true).setMaxLength(MAX_TITLE).build()),
                        ActionRow.of(TextInput.create("description", "Description",
                                        TextInputStyle.PARAGRAPH)
                                .setRequired(true).setMaxLength(MAX_DESCRIPTION).build()),
                        ActionRow.of(dueDate()),
                        ActionRow.of(TextInput.create("virtue", "Virtue", TextInputStyle.SHORT)
                                .setPlaceholder("Honor, Merit, Glory or Fame")
                                .setRequired(true).setMaxLength(10).build()))
                .build()).queue();
    }

    private void use(SlashCommandInteractionEvent event) {
        Member member = event.getOption("member", OptionMapping::getAsMember);
        if (member == null || !tribunal.holds(member)) {
            Replies.problem(event, "A writ can only be used on a tribunal member.");
            return;
        }
        if (member.getId().equals(event.getUser().getId())) {
            Replies.problem(event, "You cannot serve a writ on yourself.");
            return;
        }
        if (!hasWrits(event)) {
            return;
        }
        event.replyModal(Modal.create(USE_MODAL + ":" + member.getId(),
                        "A writ for " + Names.plain(member.getEffectiveName()))
                .addComponents(
                        ActionRow.of(TextInput.create("task", "The task", TextInputStyle.PARAGRAPH)
                                .setRequired(true).setMaxLength(WritTask.MAX_TASK).build()),
                        ActionRow.of(dueDate()))
                .build()).queue();
    }

    private void complete(SlashCommandInteractionEvent event) throws WritStore.WritRejected {
        int number = event.getOption("number", 0, OptionMapping::getAsInt);
        List<WritTask> open = writs.openOn(event.getUser().getId());
        if (number < 1 || number > open.size()) {
            throw new WritStore.WritRejected(open.isEmpty()
                    ? "There are no writs on you."
                    : "There is no writ " + number + ". Yours go up to " + open.size() + ".");
        }
        // The form carries the task's own id rather than its number, because another task can
        // close while the form is open and shift every number under it.
        WritTask task = open.get(number - 1);
        event.replyModal(Modal.create(COMPLETE_MODAL + ":" + task.id(),
                        "Writ " + number + " done")
                .addComponents(
                        ActionRow.of(TextInput.create("coords", "Coords", TextInputStyle.SHORT)
                                .setPlaceholder("If something was built or gathered")
                                .setRequired(false).setMaxLength(WritTask.MAX_SUBMISSION).build()),
                        ActionRow.of(TextInput.create("link", "Link", TextInputStyle.SHORT)
                                .setPlaceholder("If it was a document or a video")
                                .setRequired(false).setMaxLength(WritTask.MAX_SUBMISSION).build()))
                .build()).queue();
    }

    private void cancel(SlashCommandInteractionEvent event) throws Exception {
        int number = event.getOption("number", 0, OptionMapping::getAsInt);
        WritTask cancelled = writs.cancel(event.getUser().getId(), number);
        post(event.getJDA(), "<@" + cancelled.servedOn() + ">, the writ on you for \""
                + Names.escaped(Names.fit(cancelled.task(), 120)) + "\" has been taken back by <@"
                + cancelled.servedBy() + ">.");
        Replies.quietly(event, "Taken back. You now hold "
                + writs.held(event.getUser().getId()) + " of " + WritStore.MAX_HELD + ".");
    }

    private void strike(SlashCommandInteractionEvent event) throws Exception {
        if (!emperor.check(event)) {
            return;
        }
        Member member = Objects.requireNonNull(event.getOption("member", OptionMapping::getAsMember));
        int number = event.getOption("number", 0, OptionMapping::getAsInt);
        WritTask struck = writs.strike(event.getUser().getId(), member.getId(), number);
        post(event.getJDA(), "<@" + struck.servedOn() + "> <@" + struck.servedBy()
                + ">: the writ for \"" + Names.escaped(Names.fit(struck.task(), 120))
                + "\" has been struck by <@" + event.getUser().getId()
                + ">. The writ goes back to whoever served it.");
        Replies.quietly(event, "Struck.");
    }

    @Override
    public void modal(ModalInteractionEvent event) throws Exception {
        if (!tribunal.check(event)) {
            return;
        }
        String[] parts = event.getModalId().split(":");
        try {
            switch (parts[1]) {
                case "task" -> taskSubmitted(event);
                case "use" -> useSubmitted(event, parts[2]);
                case "complete" -> completeSubmitted(event, Integer.parseInt(parts[2]));
                default -> Replies.problem(event, "That form is no longer open.");
            }
        } catch (WritStore.WritRejected rejected) {
            Replies.problem(event, rejected.getMessage());
        }
    }

    /**
     * Spends the writ, then makes the post.
     *
     * <p>That order, because the post is the thing that cannot be taken back. If it fails the
     * writ is refunded and the member is told; the other way round would leave a task in the
     * forum that nobody paid for.
     */
    private void taskSubmitted(ModalInteractionEvent event) throws Exception {
        String userId = event.getUser().getId();
        Optional<LocalDate> due = dueDate(event);
        if (due.isEmpty()) {
            return;
        }
        String typed = value(event, "virtue").orElse("");
        Optional<Virtue> virtue = Virtue.byKey(typed);
        if (virtue.isEmpty()) {
            Replies.problem(event, "The virtue has to be Honor, Merit, Glory or Fame, not \""
                    + Names.escaped(typed) + "\".");
            return;
        }
        Optional<ForumChannel> board = settings.channel(ChannelRole.VIRTUE_BOARD)
                .map(event.getJDA()::getForumChannelById);
        if (board.isEmpty()) {
            Replies.problem(event, "The Virtue Board is gone. Set it again with "
                    + "/admin channel set.");
            return;
        }
        if (writs.held(userId) <= 0) {
            outOfWrits(event);
            return;
        }

        String title = value(event, "title").orElse("");
        String body = value(event, "description").orElse("")
                + "\n\n**Due:** " + due.get()
                + "\n**Virtue:** " + virtue.get().display()
                + "\n**Posted by:** <@" + userId + ">";

        writs.spendOnPublicTask(userId);
        try {
            board.get().createForumPost("Tribunal: " + Names.plain(title),
                    MessageCreateData.fromContent(body)).complete();
        } catch (RuntimeException e) {
            writs.adjust(userId, 1);
            LOG.error("Could not post a tribunal task; the writ was refunded", e);
            Replies.problem(event, "The post could not be made, so your writ was not spent. "
                    + "It has been logged.");
            return;
        }
        Replies.quietly(event, "Posted to The Virtue Board. You now hold "
                + writs.held(userId) + " of " + WritStore.MAX_HELD + ".");
    }

    private void useSubmitted(ModalInteractionEvent event, String servedOn) throws Exception {
        Optional<LocalDate> due = dueDate(event);
        if (due.isEmpty()) {
            return;
        }
        if (writs.held(event.getUser().getId()) <= 0) {
            outOfWrits(event);
            return;
        }
        WritTask served = writs.serve(event.getUser().getId(), servedOn,
                value(event, "task").orElse(""), due.get());
        post(event.getJDA(), "<@" + servedOn + ">, you just got served! An Imperial Writ was "
                + "used!\n\n**Task:** " + Names.escaped(served.task())
                + "\n**Due:** " + served.dueDate()
                + "\n**Served by:** <@" + served.servedBy() + ">");
        Replies.quietly(event, "Served. You now hold " + writs.held(event.getUser().getId())
                + " of " + WritStore.MAX_HELD + ".");
    }

    private void completeSubmitted(ModalInteractionEvent event, int taskId) throws Exception {
        String userId = event.getUser().getId();
        List<WritTask> open = writs.openOn(userId);
        int number = 0;
        for (int i = 0; i < open.size(); i++) {
            if (open.get(i).id() == taskId) {
                number = i + 1;
            }
        }
        if (number == 0) {
            Replies.problem(event, "That writ is no longer open.");
            return;
        }
        WritTask done = writs.complete(userId, number, value(event, "coords"),
                value(event, "link"));
        StringBuilder notice = new StringBuilder("<@").append(done.servedBy())
                .append(">, <@").append(done.servedOn()).append("> has completed the writ for \"")
                .append(Names.escaped(Names.fit(done.task(), 120))).append("\".");
        done.coords().ifPresent(coords -> notice.append("\n**Coords:** ")
                .append(Names.escaped(coords)));
        done.link().ifPresent(link -> notice.append("\n**Link:** ").append(link));
        post(event.getJDA(), notice.toString());
        Replies.quietly(event, "Done. You now hold " + writs.held(userId) + " of "
                + WritStore.MAX_HELD + ".");
    }

    /**
     * Refuses, in the words asked for, if the caller has nothing to spend.
     *
     * <p>Checked before a form opens as well as when it is submitted, so nobody fills in a task
     * and then finds out.
     */
    private boolean hasWrits(IReplyCallback event) {
        if (writs.held(event.getUser().getId()) > 0) {
            return true;
        }
        outOfWrits(event);
        return false;
    }

    private static void outOfWrits(IReplyCallback event) {
        Replies.problem(event, "Uh, uh, uh! You're out of Writs!");
    }

    private static TextInput dueDate() {
        return TextInput.create("due", "Due date", TextInputStyle.SHORT)
                .setPlaceholder("YYYY-MM-DD")
                .setRequired(true).setMinLength(10).setMaxLength(10).build();
    }

    /** The due date as typed, or empty having already said what was wrong with it. */
    private static Optional<LocalDate> dueDate(ModalInteractionEvent event) {
        String typed = value(event, "due").orElse("");
        try {
            return Optional.of(LocalDate.parse(typed));
        } catch (DateTimeParseException e) {
            Replies.problem(event, "The due date has to be written like 2026-09-20, not \""
                    + Names.escaped(typed) + "\".");
            return Optional.empty();
        }
    }

    /**
     * Posts a notice in the writ channel, if one is set.
     *
     * <p>Not posting is not a failure. The person who ran the command is told what happened in
     * their own reply either way; this is for the people being pinged.
     */
    private void post(JDA jda, String message) {
        Optional<String> channelId = settings.channel(ChannelRole.WRITS);
        if (channelId.isEmpty()) {
            LOG.info("No writ channel is set, so nothing was posted");
            return;
        }
        TextChannel channel = jda.getTextChannelById(channelId.get());
        if (channel == null) {
            LOG.warn("The writ channel {} is gone; nothing was posted", channelId.get());
            return;
        }
        channel.sendMessage(message).queue(null, error ->
                LOG.warn("Could not post a writ notice: {}", error.getMessage()));
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
