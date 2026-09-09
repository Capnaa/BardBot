package dev.capna.bardbot.discord;

import dev.capna.bardbot.model.House;
import dev.capna.bardbot.model.HouseColor;
import dev.capna.bardbot.model.Standings;
import dev.capna.bardbot.store.ProfileStore;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.util.List;
import java.util.Optional;

/**
 * A house, drawn.
 *
 * <p>Two renown figures, deliberately together: this month is the one that can still be changed and
 * all time is the one that says what the house has been. Showing only the first makes an old house
 * look like a new one every month; showing only the second makes this month pointless.
 */
public final class HouseEmbed {

    /** Enough to see who a house is without the roster becoming the whole message. */
    private static final int MEMBERS_SHOWN = 20;

    private HouseEmbed() {
    }

    public static MessageEmbed of(House house, ProfileStore profiles,
                                  int thisMonth, int allTime, Optional<Integer> place) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(Names.plain(house.name()))
                // The stripe down the side is the one place a house's color is visible without
                // reading anything, so it is set even when the house has chosen nothing.
                .setColor(house.color().rgb());
        house.crestUrl().ifPresent(embed::setThumbnail);

        StringBuilder description = new StringBuilder();
        // The motto is italicised because it is the house speaking rather than the bot.
        house.motto().ifPresent(motto -> description.append('*')
                .append(Names.escaped(motto)).append("*\n\n"));
        house.description().ifPresent(text -> description.append(Names.escaped(text)));
        if (!description.isEmpty()) {
            embed.setDescription(description.toString());
        }

        embed.addField(house.headIds().size() == 1 ? "Head" : "Heads",
                names(house.headIds(), profiles), true);
        embed.addField("Members", String.valueOf(house.everyone().size()), true);

        embed.addField("Renown", Ansi.FENCE
                + Ansi.white(String.format("%-11s", "This month")) + " "
                + Ansi.number(String.format("%6d", thisMonth))
                + place.map(rank -> "   #" + rank).orElse("") + "\n"
                + Ansi.white(String.format("%-11s", "All time")) + " "
                + Ansi.number(String.format("%6d", allTime)) + "\n"
                + "```", false);

        if (!house.nobleTitles().isEmpty()) {
            embed.addField("Titles", String.join(", ", house.nobleTitles().stream()
                    .map(Names::escaped).toList()), false);
        }

        List<String> members = house.everyone().stream()
                .filter(id -> !house.isHead(id))
                .toList();
        if (!members.isEmpty()) {
            embed.addField("Roll", names(members.size() > MEMBERS_SHOWN
                    ? members.subList(0, MEMBERS_SHOWN)
                    : members, profiles)
                    + (members.size() > MEMBERS_SHOWN
                            ? " and " + (members.size() - MEMBERS_SHOWN) + " more"
                            : ""), false);
        }

        return embed.build();
    }

    /** One row of a house leaderboard. */
    public static MessageEmbed leaderboard(String title, List<Standings.Place> places,
                                           java.util.function.Function<String, HouseColor> colors) {
        EmbedBuilder embed = new EmbedBuilder().setTitle(title);
        if (places.isEmpty()) {
            embed.setDescription("There are no houses yet.");
            return embed.build();
        }
        StringBuilder rows = new StringBuilder(Ansi.FENCE);
        for (int i = 0; i < places.size(); i++) {
            Standings.Place place = places.get(i);
            rows.append(Ansi.white(String.format("%2d.", i + 1)))
                    .append(" ")
                    .append(colors.apply(place.houseId())
                            .paint(String.format("%-24s", Ansi.inFence(place.houseName()))))
                    .append(Ansi.number(String.valueOf(place.renown())))
                    .append('\n');
        }
        rows.append("```");
        embed.setDescription(rows.toString());
        return embed.build();
    }

    /** Character names where they have been written, mentions otherwise. */
    private static String names(List<String> userIds, ProfileStore profiles) {
        if (userIds.isEmpty()) {
            return "None";
        }
        return String.join(", ", userIds.stream()
                .map(id -> profiles.get(id).name()
                        .map(Names::escaped)
                        .orElse("<@" + id + ">"))
                .toList());
    }
}
