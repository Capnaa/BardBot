package dev.capna.bardbot.rank;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RanksTest {

    private static Map<String, Integer> standings(Object... pairs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return map;
    }

    @Test
    void ranksFromOne() {
        Map<String, Integer> standings = standings("a", 10, "b", 5);
        assertEquals(1, Ranks.of(standings, "a").orElseThrow());
        assertEquals(2, Ranks.of(standings, "b").orElseThrow());
    }

    /**
     * The behaviour worth pinning: a draw is a draw, and the place after it is skipped. Breaking
     * ties on anything else produces an order that shuffles when an old award is corrected.
     */
    @Test
    void tiesShareAPlaceAndSkipTheNext() {
        Map<String, Integer> standings = standings("a", 10, "b", 10, "c", 4);
        assertEquals(1, Ranks.of(standings, "a").orElseThrow());
        assertEquals(1, Ranks.of(standings, "b").orElseThrow());
        assertEquals(3, Ranks.of(standings, "c").orElseThrow());
    }

    @Test
    void zeroAndBelowAreUnranked() {
        Map<String, Integer> standings = standings("a", 10, "b", 0, "c", -5);
        assertTrue(Ranks.of(standings, "b").isEmpty());
        assertTrue(Ranks.of(standings, "c").isEmpty());
        assertTrue(Ranks.of(standings, "nobody").isEmpty());
    }

    @Test
    void orderedDropsEveryoneOnZero() {
        assertEquals(1, Ranks.ordered(standings("a", 3, "b", 0, "c", -1)).size());
    }
}
