package dev.capna.bardbot.virtue;

import dev.capna.bardbot.model.Award;
import dev.capna.bardbot.model.Goal;
import dev.capna.bardbot.model.House;
import dev.capna.bardbot.model.Virtue;
import dev.capna.bardbot.store.AwardLog;
import dev.capna.bardbot.store.CatalogueStore;
import dev.capna.bardbot.store.HouseStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Making an award, wherever it was asked for.
 *
 * <p>Two commands award virtue — the right-click menu on a message and the slash command — and they
 * must do exactly the same thing: record it against the same house, compute the same before and
 * after, and notice the same goals being crossed. Doing that in one place is the only way to be
 * sure they do not drift apart.
 */
public final class Awarding {

    private static final Logger LOG = LoggerFactory.getLogger(Awarding.class);

    /** Large enough for any real award, small enough that a slipped keyboard is caught. */
    public static final int MAX_AMOUNT = 1000;

    private final AwardLog awards;
    private final HouseStore houses;
    private final CatalogueStore catalogue;

    public Awarding(AwardLog awards, HouseStore houses, CatalogueStore catalogue) {
        this.awards = Objects.requireNonNull(awards, "awards");
        this.houses = Objects.requireNonNull(houses, "houses");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
    }

    /**
     * What an award did.
     *
     * <p>Carries both sides of the change because that is what the reply shows: a number moving is
     * more legible than a number arriving, and it is also the proof that the right Bard was
     * awarded.
     *
     * @param crossed goals this award took the Bard past, which is what gets announced
     */
    public record Result(Virtue virtue,
                         int amount,
                         int scoreBefore, int scoreAfter,
                         int totalBefore, int totalAfter,
                         Optional<House> house,
                         List<Goal> crossed) {

        public Result {
            crossed = List.copyOf(crossed);
        }
    }

    /**
     * Records an award and works out what it changed.
     *
     * <p>The house is read at this moment and stored on the award, so renown stays with the house
     * that held the Bard when they earned it rather than following them if they move later.
     */
    public Result apply(String recipientId, String granterId, Virtue virtue, int amount,
                        Optional<String> reason) throws IOException {
        int scoreBefore = awards.score(recipientId, virtue);
        int totalBefore = awards.total(recipientId);
        Optional<House> house = houses.holding(recipientId);

        awards.append(new Award(recipientId, granterId, virtue, amount, reason,
                house.map(House::id), Instant.now()));

        int scoreAfter = scoreBefore + amount;
        int totalAfter = totalBefore + amount;

        LOG.info("{} awarded {} {} to {}", granterId, amount, virtue.key(), recipientId);
        return new Result(virtue, amount, scoreBefore, scoreAfter, totalBefore, totalAfter,
                house, crossed(virtue, scoreBefore, scoreAfter, totalBefore, totalAfter));
    }

    /**
     * The goals this award took the Bard past.
     *
     * <p>Both the virtue's own goals and the goals measured against the total, since one award can
     * move both. Losing a goal is deliberately silent: an award can be negative, and announcing
     * that somebody has fallen below a title is not something the bot should do in public.
     */
    private List<Goal> crossed(Virtue virtue, int scoreBefore, int scoreAfter,
                               int totalBefore, int totalAfter) {
        List<Goal> crossed = new ArrayList<>();
        for (Goal goal : catalogue.goals(Optional.of(virtue))) {
            if (goal.crossedBy(scoreBefore, scoreAfter)) {
                crossed.add(goal);
            }
        }
        for (Goal goal : catalogue.goals(Optional.empty())) {
            if (goal.crossedBy(totalBefore, totalAfter)) {
                crossed.add(goal);
            }
        }
        return crossed;
    }
}
