package dev.capna.bardbot.path;

import dev.capna.bardbot.model.PathReward;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the figure stands.
 *
 * <p>The drawing itself is a matter of taste and is checked by looking at it. This is the part that
 * can be wrong without anybody noticing: a Bard standing in the wrong place, or standing still
 * while their virtue climbs.
 */
class PathCardTest {

    @Test
    void nothingEarnedStandsAtTheStart() {
        assertEquals(72, PathCard.standing(0)[0]);
        assertEquals(72, PathCard.standing(-5)[0], "a negative month is still the start");
    }

    /** Reaching a line puts the Bard on that marker, which is what makes the card readable. */
    @Test
    void aThresholdStandsOnItsMarker() {
        assertEquals(225, PathCard.standing(10)[0]);
        assertEquals(420, PathCard.standing(35)[0]);
        assertEquals(715, PathCard.standing(70)[0]);
    }

    /**
     * The whole reason for drawing this rather than printing a table: an award that unlocks
     * nothing still moves you.
     */
    @Test
    void scoresBetweenLinesStandBetweenMarkers() {
        int[] at35 = PathCard.standing(35);
        int[] at44 = PathCard.standing(44);
        int[] at50 = PathCard.standing(50);

        assertTrue(at44[0] > at35[0], "44 is further along than 35");
        assertTrue(at44[0] < at50[0], "44 has not reached 50");
        assertTrue(at44[1] < at35[1], "the ground rises from 35 to 50");
    }

    @Test
    void everyStepForwardMovesTheBard() {
        for (int earned = 1; earned < PathReward.FREE_PASSAGE.threshold(); earned++) {
            assertTrue(PathCard.standing(earned)[0] >= PathCard.standing(earned - 1)[0],
                    "virtue " + earned + " must not walk backwards");
        }
    }

    /** Past the top there is nowhere further to go, and the end card is drawn instead. */
    @Test
    void theTopIsTheEndOfTheTrack() {
        assertEquals(852, PathCard.standing(80)[0]);
        assertEquals(852, PathCard.standing(500)[0]);
    }
}
