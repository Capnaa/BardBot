package dev.capna.bardbot.model;

import java.time.Instant;
import java.util.Optional;

/**
 * One grant of virtue, kept forever.
 *
 * <p>The award log is the only place virtue is stored. Every score, total, leaderboard position and
 * house renown figure on the bot is a sum over these, which is what makes them impossible to
 * disagree with each other.
 *
 * <p>Nothing is ever edited or deleted. A mistake is corrected by awarding the opposite amount, so
 * the record shows what happened rather than what somebody wishes had happened. When a number is
 * eventually argued about, this is what settles it.
 *
 * @param recipientId who received it
 * @param granterId   which tribunal member gave it
 * @param amount      may be negative, which is how a mistaken award is taken back
 * @param reason      what it was for, absent if none was given
 * @param houseId     the house holding the recipient at that moment, absent if they held none.
 *                    Recorded here rather than looked up later, because renown belongs to the house
 *                    that held them when it was earned, not to whichever house holds them now.
 * @param at          when, which is also what decides the month it counts toward
 */
public record Award(String recipientId,
                    String granterId,
                    Virtue virtue,
                    int amount,
                    Optional<String> reason,
                    Optional<String> houseId,
                    Instant at) {

    /** Long enough to say what someone did, short enough to read in a list of them. */
    public static final int MAX_REASON = 200;
}
