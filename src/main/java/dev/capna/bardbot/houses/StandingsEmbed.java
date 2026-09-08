package dev.capna.bardbot.houses;

import dev.capna.bardbot.discord.Names;
import dev.capna.bardbot.model.Standings;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * A settled month, as the tribunal reads it.
 *
 * <p>Drawn from the archived table rather than from the log, so it says what was true when the
 * month closed and keeps saying it however the log is corrected afterwards.
 */
public final class StandingsEmbed {

    private static final DateTimeFormatter MONTH =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private StandingsEmbed() {
    }

    public static MessageEmbed of(Standings standings) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Renown — " + MONTH.format(standings.month()));

        if (standings.places().isEmpty()) {
            embed.setDescription("There were no houses.");
            return embed.build();
        }

        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < standings.places().size(); i++) {
            Standings.Place place = standings.places().get(i);
            rows.append(i + 1).append(". ")
                    .append(Names.escaped(place.houseName()))
                    .append(" — ").append(place.renown())
                    .append('\n');
        }
        embed.setDescription(rows.toString());

        List<Standings.Place> winners = standings.winners();
        if (winners.isEmpty()) {
            // A month where nothing was earned has no winner. That is a real outcome, and naming
            // an arbitrary house as one would be worse than saying so.
            embed.addField("Winner", "No renown was earned.", false);
        } else {
            embed.addField(winners.size() == 1 ? "Winner" : "Winners",
                    String.join(" and ", winners.stream()
                            .map(place -> Names.escaped(place.houseName())).toList()), false);
        }
        return embed.build();
    }
}
