package dev.capna.bardbot.discord;

import dev.capna.bardbot.model.House;
import dev.capna.bardbot.model.Profile;
import dev.capna.bardbot.model.Virtue;
import dev.capna.bardbot.rank.Ranks;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;

import java.util.Map;
import java.util.Optional;

/**
 * A Bard's profile, drawn.
 *
 * <p>Laid out in the order the Bardonian profile sheet uses: the name and its titles, then the four
 * virtues, then who they are, their lore, their wiki page, their house and their family tree.
 *
 * <p>The family tree is the house they belong to, drawn from its membership rather than typed. A
 * Bard cannot claim a lineage they do not have, and nobody has to edit their profile when somebody
 * else joins or leaves.
 *
 * <p>Empty fields are left out rather than shown as blanks. A profile with six "not set" rows looks
 * broken; one with three real fields looks deliberate.
 */
public final class ProfileEmbed {

    /**
     * The character image sits top right rather than across the bottom.
     *
     * <p>Discord offers exactly two places for a picture, and the full-width one renders below
     * every field, which would put the character underneath their own family tree.
     */
    private ProfileEmbed() {
    }

    /**
     * @param scores     the Bard's four virtues
     * @param positions  each virtue's standings, plus the total under {@code Optional.empty()},
     *                   used to print the rank beside each score
     * @param familyTree the house's head and members, already resolved to names, absent for a Bard
     *                   in no house
     */
    public static MessageEmbed of(Profile profile,
                                  User user,
                                  Map<Virtue, Integer> scores,
                                  Map<Optional<Virtue>, Map<String, Integer>> positions,
                                  Optional<House> house,
                                  Optional<String> familyTree) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(CharacterName.of(profile, user.getEffectiveName()))
                .setThumbnail(profile.imageUrl().orElse(user.getEffectiveAvatarUrl()))
                // A Bard's sheet is drawn in their house's color wherever it appears, and grey
                // when they belong to no house, which is the same grey a house with no color set
                // is given.
                .setColor(house.map(held -> held.color().rgb())
                        .orElse(dev.capna.bardbot.model.HouseColor.fallback().rgb()))
                .setFooter("@" + user.getName(), null);

        embed.addField("Virtue", virtues(profile.userId(), scores, positions), false);

        profile.gender().ifPresent(gender ->
                embed.addField("Gender", Names.escaped(gender), true));
        profile.age().ifPresent(age ->
                embed.addField("Age", Names.escaped(age), true));

        profile.description().ifPresent(description ->
                embed.addField("Brief Lore", Names.escaped(description), false));

        profile.wikiUrl().ifPresent(wiki ->
                // The label is not escaped: Discord formats nothing inside a link label, so a
                // backslash there would be shown to the reader rather than hiding anything.
                embed.addField("Wiki Page", "[View page](" + wiki + ")", false));

        embed.addField("Noble House",
                house.map(h -> Names.escaped(h.name())).orElse("None"), false);

        familyTree.ifPresent(tree -> embed.addField("Family Tree", tree, false));

        return embed.build();
    }

    /**
     * The four virtues and their total, with each one's position.
     *
     * <p>Drawn in a code block because Discord does not align text: without one the numbers wander
     * with the width of the words beside them and the column stops being readable at a glance.
     * Nothing inside is escaped, since a code block formats nothing.
     */
    private static String virtues(String userId,
                                  Map<Virtue, Integer> scores,
                                  Map<Optional<Virtue>, Map<String, Integer>> positions) {
        StringBuilder block = new StringBuilder(Ansi.FENCE);
        int total = 0;
        for (Virtue virtue : Virtue.values()) {
            int score = scores.getOrDefault(virtue, 0);
            total += score;
            block.append(row(Ansi.virtue(virtue, pad(virtue.display())), score,
                    Ranks.of(positions.getOrDefault(Optional.of(virtue), Map.of()), userId)));
        }
        block.append(row(Ansi.white(pad("Total")), total,
                Ranks.of(positions.getOrDefault(Optional.<Virtue>empty(), Map.of()), userId)));
        return block.append("```").toString();
    }

    /**
     * Padded before it is colored.
     *
     * <p>An escape sequence counts toward a format width but occupies no space on screen, so
     * padding an already colored string misaligns the column.
     */
    private static String pad(String label) {
        return String.format("%-6s", label);
    }

    /** A rank of nothing is blank. Last place among everyone on zero is not worth printing. */
    private static String row(String label, int score, Optional<Integer> rank) {
        return label + " " + Ansi.number(String.format("%5d", score)) + "   "
                + rank.map(place -> "#" + place).orElse("") + "\n";
    }
}
