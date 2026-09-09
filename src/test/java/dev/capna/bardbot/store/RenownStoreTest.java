package dev.capna.bardbot.store;

import dev.capna.bardbot.model.Standings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenownStoreTest {

    @TempDir
    Path directory;

    private RenownStore store() {
        return new RenownStore(directory.resolve("renown.json"));
    }

    private static Standings month(int year, int month, int valeRenown) {
        return new Standings(YearMonth.of(year, month),
                List.of(new Standings.Place("vale", "House Vale", valeRenown)),
                Instant.parse("2026-06-01T05:00:00Z"));
    }

    /**
     * The property the whole roll depends on. A restart on the first of the month must not settle
     * the same month twice, or February's result is reposted every day.
     */
    @Test
    void aMonthIsSettledOnlyOnce() throws IOException {
        RenownStore store = store();
        store.archive(month(2026, 5, 100));
        store.archive(month(2026, 5, 999));

        assertTrue(store.hasRolled(YearMonth.of(2026, 5)));
        assertEquals(100, store.forMonth(YearMonth.of(2026, 5))
                .orElseThrow().places().get(0).renown());
    }

    @Test
    void latestIsTheMostRecentMonth() throws IOException {
        RenownStore store = store();
        store.archive(month(2026, 4, 10));
        store.archive(month(2026, 5, 20));
        assertEquals(YearMonth.of(2026, 5), store.latest().orElseThrow().month());
    }

    @Test
    void survivesARestart() throws IOException {
        store().archive(month(2026, 5, 100));
        assertTrue(new RenownStore(directory.resolve("renown.json"))
                .hasRolled(YearMonth.of(2026, 5)));
    }

    @Test
    void aDrawHasTwoWinners() {
        Standings tied = new Standings(YearMonth.of(2026, 5), List.of(
                new Standings.Place("vale", "House Vale", 50),
                new Standings.Place("kalt", "House Kalt", 50),
                new Standings.Place("rhun", "House Rhun", 10)),
                Instant.now());
        assertEquals(2, tied.winners().size());
    }

    /** A quiet month has no winner. Naming one arbitrarily would be worse than saying so. */
    @Test
    void aMonthWithNoRenownHasNoWinner() {
        Standings quiet = new Standings(YearMonth.of(2026, 5),
                List.of(new Standings.Place("vale", "House Vale", 0)), Instant.now());
        assertTrue(quiet.winners().isEmpty());
        assertFalse(quiet.places().isEmpty());
    }
}
