package dev.capna.bardbot.store;

import dev.capna.bardbot.model.PathReward;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathStoreTest {

    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    @TempDir
    Path directory;

    private PathStore store() {
        return new PathStore(directory.resolve("path.json"));
    }

    @Test
    void reachingAVoucherLinePaysAVoucher() throws Exception {
        PathStore store = store();
        List<PathReward> fresh = store.grant("bard", SEPTEMBER, List.of(PathReward.VOUCHER_ONE));
        assertEquals(List.of(PathReward.VOUCHER_ONE), fresh);
        assertEquals(1, store.vouchers("bard"));
    }

    /** A correction that dips somebody below a line and back must not pay twice. */
    @Test
    void aLineIsPaidOncePerMonth() throws Exception {
        PathStore store = store();
        store.grant("bard", SEPTEMBER, List.of(PathReward.VOUCHER_ONE));
        List<PathReward> again = store.grant("bard", SEPTEMBER,
                List.of(PathReward.VOUCHER_ONE, PathReward.VOUCHER_TWO));
        assertEquals(List.of(PathReward.VOUCHER_TWO), again);
        assertEquals(2, store.vouchers("bard"));
    }

    @Test
    void theSlateIsWipedWhenTheMonthTurns() throws Exception {
        PathStore store = store();
        store.grant("bard", SEPTEMBER, List.of(PathReward.VOUCHER_ONE));
        assertTrue(store.granted("bard", OCTOBER).isEmpty());

        store.grant("bard", OCTOBER, List.of(PathReward.VOUCHER_ONE));
        assertEquals(2, store.vouchers("bard"));
    }

    @Test
    void onlyVoucherLinesPayAnything() throws Exception {
        PathStore store = store();
        List<PathReward> fresh = store.grant("bard", SEPTEMBER,
                List.of(PathReward.PREFIX, PathReward.FREE_PASSAGE));
        assertEquals(2, fresh.size());
        assertEquals(0, store.vouchers("bard"));
    }

    @Test
    void claimingSpendsOne() throws Exception {
        PathStore store = store();
        store.grant("bard", SEPTEMBER, List.of(PathReward.VOUCHER_ONE));
        store.claim("bard");
        assertEquals(0, store.vouchers("bard"));
        assertThrows(PathStore.PathRejected.class, () -> store.claim("bard"));
    }

    @Test
    void aMonthIsRolledOnce() throws Exception {
        PathStore store = store();
        assertTrue(store.lastRolled().isEmpty());
        assertFalse(store.hasRolled(SEPTEMBER));
        store.markRolled(SEPTEMBER);
        assertTrue(store.hasRolled(SEPTEMBER));
        assertFalse(store.hasRolled(OCTOBER));
    }

    /** Vouchers are what a Pilgrim takes with them when the role comes off, so they must last. */
    @Test
    void vouchersSurviveARestart() throws Exception {
        PathStore before = store();
        before.grant("bard", SEPTEMBER, List.of(PathReward.VOUCHER_ONE, PathReward.VOUCHER_TWO));
        before.claim("bard");
        before.markRolled(SEPTEMBER);

        PathStore after = store();
        assertEquals(1, after.vouchers("bard"));
        assertEquals(2, after.granted("bard", SEPTEMBER).size());
        assertTrue(after.hasRolled(SEPTEMBER));
    }
}
