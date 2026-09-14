package dev.capna.bardbot.store;

import dev.capna.bardbot.model.WritStatus;
import dev.capna.bardbot.model.WritTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WritStoreTest {

    private static final LocalDate DUE = LocalDate.of(2026, 9, 20);
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    @TempDir
    Path directory;

    private WritStore store() {
        return new WritStore(directory.resolve("writs.json"));
    }

    @Test
    void nobodyStartsWithAny() {
        assertEquals(0, store().held("anyone"));
    }

    @Test
    void theMonthlyGrantGoesToEveryoneNamedOnce() throws Exception {
        WritStore store = store();
        assertTrue(store.grantMonthly(SEPTEMBER, List.of("a", "b")));
        assertEquals(1, store.held("a"));
        assertEquals(1, store.held("b"));

        assertFalse(store.grantMonthly(SEPTEMBER, List.of("a", "b")));
        assertEquals(1, store.held("a"));
    }

    /** The grant is lost at the cap on purpose. That loss is the pressure to spend. */
    @Test
    void theGrantIsLostAtTheCap() throws Exception {
        WritStore store = store();
        store.adjust("a", WritStore.MAX_HELD);
        store.grantMonthly(SEPTEMBER, List.of("a"));
        assertEquals(WritStore.MAX_HELD, store.held("a"));
    }

    @Test
    void servingLocksTheWritInTheTask() throws Exception {
        WritStore store = store();
        store.adjust("server", 1);
        WritTask task = store.serve("server", "target", "Build a watchtower", DUE);

        assertEquals(0, store.held("server"));
        assertEquals(0, store.held("target"));
        assertTrue(task.isOpen());
        assertEquals(1, store.openOn("target").size());
        assertEquals(1, store.openBy("server").size());
    }

    @Test
    void completingHandsTheWritOver() throws Exception {
        WritStore store = store();
        store.adjust("server", 1);
        store.serve("server", "target", "Build a watchtower", DUE);

        WritTask done = store.complete("target", 1, Optional.of("100 64 -200"), Optional.empty());
        assertEquals(WritStatus.COMPLETED, done.status());
        assertEquals(1, store.held("target"));
        assertEquals(0, store.held("server"));
        assertTrue(store.openOn("target").isEmpty());
    }

    /** Bouncing it back would make serving somebody who is full a way to hand out free work. */
    @Test
    void completingAtTheCapBurnsTheWrit() throws Exception {
        WritStore store = store();
        store.adjust("server", 1);
        store.adjust("target", WritStore.MAX_HELD);
        store.serve("server", "target", "Build a watchtower", DUE);

        store.complete("target", 1, Optional.empty(), Optional.of("https://example.invalid"));
        assertEquals(WritStore.MAX_HELD, store.held("target"));
        assertEquals(0, store.held("server"));
    }

    @Test
    void completingNeedsSomethingToShow() throws Exception {
        WritStore store = store();
        store.adjust("server", 1);
        store.serve("server", "target", "Build a watchtower", DUE);
        assertThrows(WritStore.WritRejected.class,
                () -> store.complete("target", 1, Optional.empty(), Optional.empty()));
        assertEquals(1, store.openOn("target").size());
    }

    @Test
    void cancellingReturnsTheWrit() throws Exception {
        WritStore store = store();
        store.adjust("server", 1);
        store.serve("server", "target", "Build a watchtower", DUE);

        WritTask cancelled = store.cancel("server", 1);
        assertEquals(WritStatus.CANCELLED, cancelled.status());
        assertEquals(1, store.held("server"));
        assertTrue(store.openOn("target").isEmpty());
    }

    @Test
    void strikingReturnsTheWritToWhoeverServedIt() throws Exception {
        WritStore store = store();
        store.adjust("server", 1);
        store.serve("server", "target", "Something unreasonable", DUE);

        WritTask struck = store.strike("emperor", "target", 1);
        assertEquals(WritStatus.STRUCK, struck.status());
        assertEquals(Optional.of("emperor"), struck.struckBy());
        assertEquals(1, store.held("server"));
        assertEquals(0, store.held("target"));
    }

    @Test
    void aPublicTaskBurnsTheWrit() throws Exception {
        WritStore store = store();
        store.adjust("a", 1);
        store.spendOnPublicTask("a");
        assertEquals(0, store.held("a"));
        assertThrows(WritStore.WritRejected.class, () -> store.spendOnPublicTask("a"));
    }

    @Test
    void youCannotServeYourself() throws Exception {
        WritStore store = store();
        store.adjust("a", 1);
        assertThrows(WritStore.WritRejected.class, () -> store.serve("a", "a", "Anything", DUE));
        assertEquals(1, store.held("a"));
    }

    @Test
    void numbersFollowTheOrderTasksWereServed() throws Exception {
        WritStore store = store();
        store.adjust("server", 2);
        store.serve("server", "target", "First", DUE);
        store.serve("server", "target", "Second", DUE.plusDays(1));

        store.complete("target", 1, Optional.of("here"), Optional.empty());
        assertEquals("Second", store.openOn("target").get(0).task());
        assertThrows(WritStore.WritRejected.class, () -> store.strike("emperor", "target", 2));
    }

    @Test
    void everythingSurvivesARestart() throws Exception {
        WritStore before = store();
        before.grantMonthly(SEPTEMBER, List.of("server"));
        before.adjust("server", 1);
        before.serve("server", "target", "Build a watchtower", DUE);
        before.complete("target", 1, Optional.of("here"), Optional.empty());
        before.adjust("server", 1);
        before.serve("server", "other", "Still open", DUE);

        WritStore after = store();
        assertTrue(after.hasGranted(SEPTEMBER));
        assertEquals(1, after.held("target"));
        assertEquals(1, after.openOn("other").size());
        assertTrue(after.openOn("target").isEmpty());
    }
}
