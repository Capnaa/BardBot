package dev.capna.bardbot.rank;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turning scores into positions.
 *
 * <p>Ties share a position and the next one is skipped, so two Bards on the same score are both
 * second and the one behind them is fourth. The alternative, breaking ties on something arbitrary
 * like who was awarded first, produces an order nobody can explain and that changes when an old
 * award is corrected.
 */
public final class Ranks {

    private Ranks() {
    }

    /**
     * Where one Bard stands.
     *
     * @return their position counting from one, or empty when they have nothing to rank. A score of
     *         zero or less is deliberately unranked: being last among everyone who has never been
     *         awarded anything is not a position worth printing.
     */
    public static Optional<Integer> of(Map<String, Integer> standings, String userId) {
        Integer score = standings.get(userId);
        if (score == null || score <= 0) {
            return Optional.empty();
        }
        long ahead = standings.values().stream().filter(other -> other > score).count();
        return Optional.of((int) ahead + 1);
    }

    /** Everyone with a score above zero, highest first, for a leaderboard page. */
    public static List<Map.Entry<String, Integer>> ordered(Map<String, Integer> standings) {
        return standings.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .toList();
    }
}
