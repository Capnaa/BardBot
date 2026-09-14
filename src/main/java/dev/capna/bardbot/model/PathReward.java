package dev.capna.bardbot.model;

import java.util.Arrays;
import java.util.List;

/**
 * The Path of Virtue: what a Pilgrim unlocks as the month's virtue climbs.
 *
 * <p>Code rather than data, unlike goals, because the ladder is the product being sold and moving
 * a line on it is a decision for the people selling it, not a command. If it changes, it changes
 * in a deploy that says so.
 *
 * <p>Only {@link #VOUCHER_ONE} and {@link #VOUCHER_TWO} are handed out by the bot. Everything else
 * happens off the bot, and the bot's part is to say who earned it on the first of the month.
 */
public enum PathReward {

    VOUCHER_ONE(10, "Virtue Voucher"),
    VOUCHER_TWO(25, "Virtue Voucher"),
    PREFIX(35, "Monthly Prefix"),
    LOTTERY_ONE(50, "Gift Card Lottery Entry"),
    LOTTERY_TWO(60, "Second Lottery Entry"),
    BONUS_TASK(70, "Bonus Task Next Month"),
    FREE_PASSAGE(80, "Free Passage Next Month");

    /** What a voucher is worth when it is claimed. */
    public static final int VOUCHER_VIRTUE = 5;

    private final int threshold;
    private final String display;

    PathReward(int threshold, String display) {
        this.threshold = threshold;
        this.display = display;
    }

    public int threshold() {
        return threshold;
    }

    public String display() {
        return display;
    }

    /** Whether this one puts a voucher in somebody's hand, as opposed to a name on a list. */
    public boolean isVoucher() {
        return this == VOUCHER_ONE || this == VOUCHER_TWO;
    }

    /** Whether the month's virtue has reached this line. Stated once, like {@link Goal#metBy}. */
    public boolean metBy(int earned) {
        return earned >= threshold;
    }

    /** Whether an award moved somebody across this line. Crossing, not meeting, as with goals. */
    public boolean crossedBy(int before, int after) {
        return !metBy(before) && metBy(after);
    }

    /** Every line the month's virtue has reached, lowest first. */
    public static List<PathReward> reachedBy(int earned) {
        return Arrays.stream(values()).filter(reward -> reward.metBy(earned)).toList();
    }
}
