package dev.capna.bardbot.discord;

import dev.capna.bardbot.model.Virtue;
import dev.capna.bardbot.rank.Ranks;
import dev.capna.bardbot.store.ProfileStore;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A page of standings.
 *
 * <p>Ten at a time. Twenty-five fits in a message and nobody reads past the top few; ten is a page
 * somebody can take in at a glance and still leaves a reason to press next.
 */
public final class LeaderboardEmbed {

    public static final int PAGE_SIZE = 10;

    private LeaderboardEmbed() {
    }

    /**
     * @param virtue which board this is, empty for the total of all four
     * @param viewer whoever ran the command, so their own position can be shown even when they are
     *               nowhere near the page being looked at. A leaderboard that cannot tell you where
     *               you stand is a leaderboard you look at once.
     */
    public static MessageEmbed of(Optional<Virtue> virtue,
                                  Map<String, Integer> standings,
                                  ProfileStore profiles,
                                  int page,
                                  String viewer) {
        List<Map.Entry<String, Integer>> ordered = Ranks.ordered(standings);
        int pages = Math.max(1, (ordered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(Math.max(page, 1), pages);

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(virtue.map(Virtue::display).orElse("Total virtue"));

        if (ordered.isEmpty()) {
            embed.setDescription("Nothing has been awarded yet.");
            return embed.build();
        }

        StringBuilder rows = new StringBuilder();
        int from = (current - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, ordered.size());
        for (int i = from; i < to; i++) {
            Map.Entry<String, Integer> entry = ordered.get(i);
            rows.append(place(standings, entry.getKey()))
                    .append(". ")
                    .append(name(profiles, entry.getKey()))
                    .append(": ")
                    .append(entry.getValue())
                    .append('\n');
        }
        embed.setDescription(rows.toString());

        // Their own standing, but only when it is not already on the page they are reading.
        Ranks.of(standings, viewer).ifPresent(rank -> {
            boolean onThisPage = rank > from && rank <= to;
            if (!onThisPage) {
                embed.addField("You", "#" + rank + ", " + standings.get(viewer), false);
            }
        });

        embed.setFooter("Page " + current + " of " + pages, null);
        return embed.build();
    }

    private static String place(Map<String, Integer> standings, String userId) {
        return Ranks.of(standings, userId).map(String::valueOf).orElse("");
    }

    /**
     * Their character's name where they have written one, otherwise a mention.
     *
     * <p>A mention resolves to whatever Discord knows them as without the bot having to fetch
     * anybody, and it never pings inside an embed.
     */
    private static String name(ProfileStore profiles, String userId) {
        return profiles.get(userId).name()
                .map(Names::escaped)
                .orElse("<@" + userId + ">");
    }
}
