package dev.capna.bardbot.houses;

import dev.capna.bardbot.model.House;
import dev.capna.bardbot.model.Standings;
import dev.capna.bardbot.store.AwardLog;
import dev.capna.bardbot.store.HouseStore;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What the houses have earned.
 *
 * <p>Renown is the virtue a house's members were awarded, summed from the award log rather than
 * counted as it happens. Nothing is wiped at the end of a month: the month is simply a window over
 * the same list, which is why a correction made in March fixes February's figure too, and why
 * moving house does not take last month's renown along.
 */
public final class Renown {

    private final AwardLog awards;
    private final HouseStore houses;
    private final ZoneId zone;

    public Renown(AwardLog awards, HouseStore houses, ZoneId zone) {
        this.awards = Objects.requireNonNull(awards, "awards");
        this.houses = Objects.requireNonNull(houses, "houses");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /** The month as the guild reckons it, which is not necessarily the month UTC is in. */
    public YearMonth thisMonth() {
        return YearMonth.from(Instant.now().atZone(zone));
    }

    public int thisMonthFor(String houseId) {
        return awards.renown(thisMonth(), zone).getOrDefault(houseId, 0);
    }

    public int allTimeFor(String houseId) {
        return awards.renownAllTime().getOrDefault(houseId, 0);
    }

    /** This month's table, highest first. */
    public List<Standings.Place> thisMonthTable() {
        return table(awards.renown(thisMonth(), zone));
    }

    /** A finished month's table, for settling it. */
    public List<Standings.Place> tableFor(YearMonth month) {
        return table(awards.renown(month, zone));
    }

    /** Every house's whole existence. */
    public List<Standings.Place> allTimeTable() {
        return table(awards.renownAllTime());
    }

    /**
     * Turns summed renown into a table.
     *
     * <p>Houses that earned nothing are still listed, at zero: a house missing from its own
     * leaderboard reads as a bug rather than as a quiet month. Houses that no longer exist are
     * dropped, since their awards remain but there is nothing left to name.
     */
    private List<Standings.Place> table(Map<String, Integer> byHouse) {
        return houses.all().stream()
                .map(house -> new Standings.Place(house.id(), house.name(),
                        byHouse.getOrDefault(house.id(), 0)))
                .sorted(Comparator.comparingInt(Standings.Place::renown).reversed()
                        .thenComparing(Standings.Place::houseName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Where one house stands this month, counting from one. */
    public Optional<Integer> placeThisMonth(String houseId) {
        List<Standings.Place> table = thisMonthTable();
        for (int i = 0; i < table.size(); i++) {
            if (table.get(i).houseId().equals(houseId)) {
                return Optional.of(i + 1);
            }
        }
        return Optional.empty();
    }

    public Optional<House> houseOf(String userId) {
        return houses.holding(userId);
    }
}
