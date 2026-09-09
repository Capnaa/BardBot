package dev.capna.bardbot.model;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalTest {

    private static final Goal AT_FORTY =
            new Goal(Optional.of(Virtue.HONOR), 40, Optional.of("the Veteran"), Optional.empty());

    @Test
    void metOnTheThresholdItself() {
        assertTrue(AT_FORTY.metBy(40));
        assertFalse(AT_FORTY.metBy(39));
    }

    @Test
    void crossingIsGoingPastIt() {
        assertTrue(AT_FORTY.crossedBy(39, 42));
    }

    /** Announcing a goal somebody was already past would make the announcements worthless. */
    @Test
    void stayingPastItIsNotACrossing() {
        assertFalse(AT_FORTY.crossedBy(41, 45));
    }

    /** There is no announcement for losing a title, so falling back is not a crossing either. */
    @Test
    void fallingBackIsNotACrossing() {
        assertFalse(AT_FORTY.crossedBy(45, 20));
    }
}
