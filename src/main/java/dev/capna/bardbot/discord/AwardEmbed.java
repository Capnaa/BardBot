package dev.capna.bardbot.discord;

import dev.capna.bardbot.model.Profile;
import dev.capna.bardbot.model.Virtue;
import dev.capna.bardbot.virtue.Awarding;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;

import java.util.List;
import java.util.Optional;

/**
 * An award, as everyone sees it.
 *
 * <p>Both sides of every number are shown. A score arriving at 42 says nothing about what happened;
 * 39 becoming 42 says what was given, and lets the Bard check that it landed on the right person
 * for the right reason.
 */
public final class AwardEmbed {

    private AwardEmbed() {
    }

    public static MessageEmbed of(Awarding.Result result, Profile profile, Optional<String> reason,
                                  User recipient, User granter) {
        String headline = signed(result.amount()) + " " + result.virtue().display();
        // The reason is the one part of an award a person wrote, so it is the one part escaped.
        String description = reason
                .map(text -> headline + "\nFor: " + Names.escaped(text))
                .orElse(headline);

        return new EmbedBuilder()
                .setTitle(result.virtue().display() + " awarded to "
                        + CharacterName.of(profile, recipient.getEffectiveName()))
                .setThumbnail(profile.imageUrl().orElse(recipient.getEffectiveAvatarUrl()))
                .setDescription(description)
                // The stripe carries which virtue this was, so a channel of awards is readable
                // while scrolling past it.
                .setColor(Ansi.rgb(result.virtue()))
                .addField("Change", Ansi.FENCE
                        + row(Ansi.virtue(result.virtue(), pad(result.virtue().display())),
                                result.scoreBefore(), result.scoreAfter())
                        + row(Ansi.white(pad("Total")), result.totalBefore(), result.totalAfter())
                        + "```", false)
                .setFooter("Awarded by @" + granter.getName(), null)
                .build();
    }

    /**
     * One award made to many Bards at once.
     *
     * <p>A single embed rather than one per Bard. Fifteen embeds for one decision is a channel
     * nobody reads afterwards, and the thing worth recording here is that the same award went to
     * this list of people for this reason.
     *
     * <p>Every recipient is named, and mentioned, so anybody can check they are on the list. That
     * is also what makes a mistake correctable: the list is right there to be awarded the opposite
     * amount.
     *
     * @param names who received it, already in the order they were awarded
     */
    public static MessageEmbed bulk(Virtue virtue, int amount, List<String> recipientIds,
                                    List<String> names, Optional<String> reason, User granter) {
        String headline = signed(amount) + " " + virtue.display() + " to "
                + recipientIds.size() + (recipientIds.size() == 1 ? " Bard" : " Bards");

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(virtue.display() + " awarded")
                .setDescription(reason
                        .map(text -> headline + "\nFor: " + Names.escaped(text))
                        .orElse(headline))
                .setColor(Ansi.rgb(virtue))
                .setFooter("Awarded by @" + granter.getName(), null);

        // Mentions, so each Bard is pinged once and can see the award landed on them. Trimmed to
        // what an embed field will hold, with the rest counted rather than silently dropped.
        StringBuilder listed = new StringBuilder();
        int shown = 0;
        for (String id : recipientIds) {
            String mention = "<@" + id + ">";
            if (listed.length() + mention.length() + 2 > MentionLimit.FIELD) {
                break;
            }
            if (shown > 0) {
                listed.append(", ");
            }
            listed.append(mention);
            shown++;
        }
        if (shown < recipientIds.size()) {
            listed.append(" and ").append(recipientIds.size() - shown).append(" more");
        }
        embed.addField(shown == recipientIds.size() ? "Who" : "Who (first " + shown + ")",
                listed.toString(), false);
        return embed.build();
    }

    /** Discord refuses an embed field over this, and a refused embed is an award nobody saw. */
    private static final class MentionLimit {
        static final int FIELD = 900;
    }

    /** A positive award reads {@code +3}, so it is never mistaken for the score itself. */
    private static String signed(int amount) {
        return amount > 0 ? "+" + amount : String.valueOf(amount);
    }

    /** Padded before coloring, for the same reason the profile pads before coloring. */
    private static String pad(String label) {
        return String.format("%-8s", label);
    }

    private static String row(String label, int before, int after) {
        return label + " " + Ansi.number(String.format("%5d", before))
                + " to " + Ansi.number(String.valueOf(after)) + "\n";
    }
}
