package dev.capna.bardbot.discord;

import dev.capna.bardbot.model.Profile;
import dev.capna.bardbot.virtue.Awarding;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;

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
