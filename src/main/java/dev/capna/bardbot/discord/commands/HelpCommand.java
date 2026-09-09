package dev.capna.bardbot.discord.commands;

import dev.capna.bardbot.discord.SlashCommand;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

/**
 * What the bot does and how to make it do it.
 *
 * <p>Written for somebody who has never used it and is not going to read twice. Every instruction
 * names the exact command and says what happens after you run it, because "manage your titles" tells
 * a reader nothing they can act on.
 *
 * <p>Always ephemeral. Help is a conversation between one person and the bot, and a channel full of
 * other people's help messages helps nobody.
 *
 * <p>Covers only what everybody can do. Awarding virtue and running the bot are explained by
 * {@code /admin help} instead, so nobody is taught commands that will refuse to run for them.
 */
public final class HelpCommand implements SlashCommand {

    private static final String NAME = "help";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public CommandData definition() {
        OptionData topic = new OptionData(OptionType.STRING, "topic",
                "What you want explained. Leave blank for an overview.", false);
        topic.addChoice("Profiles", "profile");
        topic.addChoice("Virtue", "virtue");
        topic.addChoice("Titles", "titles");
        topic.addChoice("Houses", "houses");

        return Commands.slash(NAME, "How this bot works").addOptions(topic);
    }

    @Override
    public void handle(SlashCommandInteractionEvent event) {
        String topic = event.getOption("topic", "", OptionMapping::getAsString);
        MessageEmbed help = switch (topic) {
            case "profile" -> profiles();
            case "virtue" -> virtue();
            case "titles" -> titles();
            case "houses" -> houses();
            default -> overview();
        };
        event.replyEmbeds(help).setEphemeral(true).queue();
    }

    private MessageEmbed overview() {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("BardBot")
                .setDescription("""
                        This bot keeps track of four virtues, **Honor**, **Merit**, **Glory** \
                        and **Fame**. The tribunal awards them to you for things you do in the \
                        server.

                        Your virtue does three things. It builds your **profile**, it unlocks \
                        **titles** you can wear in your name, and it earns **renown** for your \
                        **noble house**, which competes with the other houses every month.

                        You do not need to sign up. You already have a profile.

                        Run `/help topic:` and pick a subject for step-by-step instructions.""");

        embed.addField("Your character",
                "`/profile view`: see your character sheet\n"
                + "`/profile edit identity`: set your name, gender, age and picture\n"
                + "`/profile edit lore`: write your character's story", false);

        embed.addField("Your virtue",
                "`/virtue leaderboard`: see who is ahead\n"
                + "`/virtue goals`: see what each score unlocks\n"
                + "`/virtue history`: see every award you have been given", false);

        embed.addField("Your titles",
                "`/title list`: see every title you are allowed to wear\n"
                + "`/title set`: choose which one shows on your profile", false);

        embed.addField("Your house",
                "`/house view`: see your house\n"
                + "`/house list`: see every house\n"
                + "`/house leaderboard`: see which house is winning this month\n"
                + "`/house accept`: join a house you have been invited to", false);

        return embed.build();
    }

    private MessageEmbed profiles() {
        return new EmbedBuilder()
                .setTitle("Profiles")
                .setDescription("""
                        Your profile is your character sheet. Everyone has one already, you do \
                        not create it, you just fill it in.

                        **To see it:** run `/profile view`. To see somebody else's, run \
                        `/profile view` and pick them in the `bard` box.""")
                .addField("Setting your name, gender, age and picture",
                        """
                        Run `/profile edit identity`. A box will pop up with five fields. Fill in \
                        the ones you want and leave the rest empty. Press Submit.

                        Anything you leave empty is simply left off your profile. To delete \
                        something later, open the same box, clear that field, and submit again.""",
                        false)
                .addField("The picture",
                        """
                        You cannot attach an image file to the form. Discord has to fetch your \
                        picture from the internet, so it has to be a **link**.

                        Upload the image to a picture host such as Imgur. Open the uploaded \
                        image on its own, right click it, and choose **Copy image address**. \
                        That is the link you paste into the form.

                        It has to end in `.png`, `.jpg`, `.gif` or `.webp`. If it does not, the \
                        bot tells you and nothing is saved.

                        Do not paste a link to an image posted in a Discord channel. Those stop \
                        working after about a day and your picture will disappear.

                        If you do not set one, your Discord avatar is used instead.""", false)
                .addField("Writing your lore",
                        """
                        Run `/profile edit lore`. You get one large box for your character's \
                        story, up to 900 characters.""", false)
                .addField("The family tree",
                        """
                        You do not fill this in. It shows your house's head and its members, and \
                        it updates by itself when people join or leave. If you are not in a \
                        house, it does not appear at all.""", false)
                .build();
    }

    private MessageEmbed virtue() {
        return new EmbedBuilder()
                .setTitle("Virtue")
                .setDescription("""
                        There are four virtues: **Honor**, **Merit**, **Glory** and **Fame**. \
                        Your **total** is all four added together.

                        **You cannot award virtue to yourself, and neither can anyone else \
                        except the tribunal.** There is nothing you need to do to earn it other \
                        than take part, the tribunal awards it when they see something worth \
                        rewarding.""")
                .addField("Seeing where you stand",
                        """
                        Run `/virtue leaderboard`. That shows the top ten by total virtue, and \
                        your own position underneath if you are not in the top ten. Use the \
                        Previous and Next buttons to see further down.

                        To see one virtue on its own, run `/virtue leaderboard` and pick it in \
                        the `virtue` box.""", false)
                .addField("Seeing what you are working towards",
                        """
                        Run `/virtue goals`. Each virtue has scores attached to it, and reaching \
                        one unlocks a title. A tick means you have already reached it.

                        When you reach one, the bot announces it and tells you what you \
                        unlocked. You then have to equip the title yourself, see \
                        `/help topic:Titles`.""", false)
                .addField("Checking an award",
                        """
                        Run `/virtue history` to see the last fifteen awards you were given, \
                        with the date and the reason. Pick somebody in the `bard` box to see \
                        theirs instead.

                        If a number looks wrong, this is the page to screenshot when you ask \
                        about it.""", false)
                .build();
    }

    private MessageEmbed titles() {
        return new EmbedBuilder()
                .setTitle("Titles")
                .setDescription("""
                        You have three title slots, and they show up in your name in this order:

                        > **Lord** Azurov the **Veteran** of **The Tribunal**

                        Those three titles are `Lord`, `Veteran` and `The Tribunal`. The words \
                        "the" and "of" are put in by the bot, so no title contains them. A slot \
                        you leave empty is skipped, and so is its joining word.""")
                .addField("Where each one comes from",
                        """
                        **Noble**: from your house. Your house's head decides which of the \
                        house's titles you are allowed to wear.
                        **Virtue**: earned. Reaching a score on `/virtue goals` unlocks one \
                        automatically. Nobody has to give it to you.
                        **Government**: given to you by the tribunal for a position you hold.""",
                        false)
                .addField("Seeing what you have",
                        """
                        Run `/title list`. It shows all three slots and every title you are \
                        allowed to wear in each. A tick marks the one you are currently \
                        wearing.""", false)
                .addField("Wearing one",
                        """
                        Run `/title set`. Pick which slot in the `slot` box, then click the \
                        `title` box. **A list appears of the titles you are allowed to wear.** \
                        Pick one from that list. Do not type it yourself.

                        To empty a slot, pick **None** from the same list.

                        If the title you want is not in the list, you have not been granted it \
                        yet.""", false)
                .addField("Losing one",
                        """
                        Titles are not kept forever. If you leave your house, you lose its \
                        titles. If your score drops below a goal, you lose that virtue title. \
                        This happens by itself and your profile updates on its own.""", false)
                .build();
    }

    private MessageEmbed houses() {
        return new EmbedBuilder()
                .setTitle("Noble houses")
                .setDescription("""
                        A house is a group of Bards. **You can only be in one house at a time.**

                        When you are awarded virtue, that same amount is also counted as \
                        **renown** for your house. Renown is counted per month, and the houses \
                        compete. At the start of each month the count starts again from zero, \
                        but your own virtue is never reset.""")
                .addField("Joining one",
                        """
                        You cannot join a house by yourself. A head of that house has to invite \
                        you first.

                        Once they have, run `/house accept` and pick the house from the list. \
                        To turn it down instead, run `/house decline`.

                        To leave later, run `/house leave`. You lose that house's titles when \
                        you do.""", false)
                .addField("Looking at houses",
                        """
                        `/house view`: your own house. Pick a name in the `name` box to see a \
                        different one.
                        `/house list`: every house, with its total renown and how many members \
                        it has.
                        `/house leaderboard`: renown earned this month. Add `all: True` for \
                        renown earned since the beginning.
                        `/house winner`: who won last month.""", false)
                .addField("If you are a head of a house",
                        """
                        `/house invite`: ask a Bard to join.
                        `/house expel`: remove a member.
                        `/house edit`: opens a box for your house's motto, lore and crest \
                        image. The crest is a link, the same as a profile picture.
                        `/house title add`: create a title your house can give out.
                        `/house title grant`: allow one member to wear one of those titles.
                        `/house title revoke`: take it back. `/house title remove` retires the \
                        title entirely.

                        A house always needs at least one head, so the last one cannot leave or \
                        be stood down. Ask the tribunal if a house needs dissolving.""", false)
                .build();
    }

}
