package dev.capna.bardbot.store;

import dev.capna.bardbot.model.Award;
import dev.capna.bardbot.model.Virtue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwardLogTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @TempDir
    Path directory;

    private AwardLog log() {
        return new AwardLog(directory.resolve("awards.json"));
    }

    private static Award award(String recipient, Virtue virtue, int amount, String house,
                               String when) {
        return new Award(recipient, "tribunal", virtue, amount, Optional.empty(),
                Optional.ofNullable(house), Instant.parse(when));
    }

    @Test
    void scoresAreSummedAndTheTotalAgrees() throws IOException {
        AwardLog log = log();
        log.append(award("bard", Virtue.HONOR, 10, null, "2026-05-01T12:00:00Z"));
        log.append(award("bard", Virtue.HONOR, 5, null, "2026-05-02T12:00:00Z"));
        log.append(award("bard", Virtue.GLORY, 3, null, "2026-05-03T12:00:00Z"));

        assertEquals(15, log.score("bard", Virtue.HONOR));
        assertEquals(3, log.score("bard", Virtue.GLORY));
        assertEquals(18, log.total("bard"));
    }

    /** Corrections are made by awarding the opposite, so negatives have to sum like anything else. */
    @Test
    void negativeAwardsTakeItBack() throws IOException {
        AwardLog log = log();
        log.append(award("bard", Virtue.HONOR, 10, null, "2026-05-01T12:00:00Z"));
        log.append(award("bard", Virtue.HONOR, -4, null, "2026-05-02T12:00:00Z"));
        assertEquals(6, log.total("bard"));
    }

    @Test
    void survivesARestart() throws IOException {
        AwardLog first = log();
        first.append(award("bard", Virtue.MERIT, 7, "vale", "2026-05-01T12:00:00Z"));

        AwardLog reopened = log();
        assertEquals(7, reopened.total("bard"));
        assertEquals(7, reopened.renownAllTime().get("vale"));
    }

    /**
     * The month is the guild's, not UTC's. An award at half past eight on the evening of the 31st
     * in New York is already the next month in UTC, and counting it there would move renown into a
     * month the guild has not started yet.
     */
    @Test
    void monthBoundariesFollowTheGuildsZone() throws IOException {
        AwardLog log = log();
        log.append(award("bard", Virtue.HONOR, 5, "vale", "2026-06-01T02:00:00Z"));

        assertEquals(5, log.renown(YearMonth.of(2026, 5), NEW_YORK).getOrDefault("vale", 0));
        assertEquals(0, log.renown(YearMonth.of(2026, 6), NEW_YORK).getOrDefault("vale", 0));
        assertEquals(5, log.renown(YearMonth.of(2026, 6), ZoneId.of("UTC")).getOrDefault("vale", 0));
    }

    /**
     * Renown belongs to the house that held the Bard when it was earned. This is the whole reason
     * the house is written onto the award instead of being looked up later.
     */
    @Test
    void renownStaysWithTheHouseThatEarnedIt() throws IOException {
        AwardLog log = log();
        log.append(award("bard", Virtue.HONOR, 10, "vale", "2026-05-10T12:00:00Z"));
        log.append(award("bard", Virtue.HONOR, 4, "kalt", "2026-05-20T12:00:00Z"));

        assertEquals(10, log.renown(YearMonth.of(2026, 5), NEW_YORK).get("vale"));
        assertEquals(4, log.renown(YearMonth.of(2026, 5), NEW_YORK).get("kalt"));
        assertEquals(14, log.total("bard"));
    }

    @Test
    void awardsWithNoHouseCountForNobody() throws IOException {
        AwardLog log = log();
        log.append(award("bard", Virtue.FAME, 9, null, "2026-05-10T12:00:00Z"));
        assertTrue(log.renownAllTime().isEmpty());
        assertEquals(9, log.total("bard"));
    }

    @Test
    void standingsCoverOnlyBardsWithARecord() throws IOException {
        AwardLog log = log();
        log.append(award("bard", Virtue.HONOR, 3, null, "2026-05-10T12:00:00Z"));
        assertEquals(1, log.standings(Optional.of(Virtue.HONOR)).size());
        assertTrue(log.standings(Optional.of(Virtue.GLORY)).isEmpty());
    }

    @Test
    void historyIsNewestFirst() throws IOException {
        AwardLog log = log();
        log.append(award("bard", Virtue.HONOR, 1, null, "2026-05-01T12:00:00Z"));
        log.append(award("bard", Virtue.HONOR, 2, null, "2026-05-09T12:00:00Z"));
        assertEquals(2, log.history("bard", 10).get(0).amount());
    }
}
