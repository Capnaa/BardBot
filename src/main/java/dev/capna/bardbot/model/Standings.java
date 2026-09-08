package dev.capna.bardbot.model;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

/**
 * What the houses earned in one month, as it stood when the month ended.
 *
 * <p>Archived rather than recomputed. A month's result is a fact about that month, and an award
 * corrected in March must not quietly change who won February.
 *
 * @param month    the month these are the standings for, in the guild's own zone
 * @param places   every house that earned anything, highest first
 * @param archived when the month was rolled, which is also how a second roll knows not to
 */
public record Standings(YearMonth month, List<Place> places, Instant archived) {

    public Standings {
        places = List.copyOf(places);
    }

    /**
     * @param houseName the name the house had at the time, kept rather than looked up later so a
     *                  renamed or removed house still reads correctly in an old month's result
     */
    public record Place(String houseId, String houseName, int renown) {
    }

    /**
     * The winning houses, plural because a draw is a draw.
     *
     * <p>Empty when nothing was earned all month, which is a real outcome and not an error: a quiet
     * month has no winner rather than an arbitrary one.
     */
    public List<Place> winners() {
        if (places.isEmpty() || places.get(0).renown() <= 0) {
            return List.of();
        }
        int best = places.get(0).renown();
        return places.stream().filter(place -> place.renown() == best).toList();
    }
}
