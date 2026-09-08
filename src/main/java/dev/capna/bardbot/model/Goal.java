package dev.capna.bardbot.model;

import java.util.Optional;

/**
 * A score a Bard is aiming at, and what reaching it gives them.
 *
 * <p>Goals are data rather than code: the government will move them, add tiers and rename the
 * titles behind them, and none of that should need a deploy.
 *
 * @param virtue    which virtue is measured, or empty to measure the total of all four
 * @param threshold the score at which the goal is met
 * @param title     the virtue title unlocked, absent for a goal that is only a milestone
 * @param note      what the goal means, shown beside it in the list
 */
public record Goal(Optional<Virtue> virtue,
                   int threshold,
                   Optional<String> title,
                   Optional<String> note) {

    /** Whether a score has met this goal. Stated once so no caller reinvents the boundary. */
    public boolean metBy(int score) {
        return score >= threshold;
    }

    /**
     * Whether an award moved a Bard across this goal.
     *
     * <p>Crossing, not meeting: an award that leaves someone already past a goal still past it has
     * not unlocked anything, and announcing it again would make the announcements worthless. A
     * negative award that drops someone back below is likewise not a crossing, and is deliberately
     * silent — there is no announcement for losing a title.
     */
    public boolean crossedBy(int before, int after) {
        return !metBy(before) && metBy(after);
    }
}
