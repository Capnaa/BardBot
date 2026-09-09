package dev.capna.bardbot.store;

import dev.capna.bardbot.model.House;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HouseStoreTest {

    @TempDir
    Path directory;

    private HouseStore store() {
        return new HouseStore(directory.resolve("houses.json"));
    }

    @Test
    void aHouseIsFoundedUnderItsHead() throws Exception {
        House vale = store().add("House Vale", "head");
        assertTrue(vale.isHead("head"));
        assertTrue(vale.holds("head"));
        assertEquals(1, vale.everyone().size());
    }

    @Test
    void namesAreTakenOnlyOnce() throws Exception {
        HouseStore store = store();
        store.add("House Vale", "head");
        assertThrows(HouseStore.HouseRejected.class, () -> store.add("house vale", "other"));
    }

    @Test
    void aBardBelongsToOneHouse() throws Exception {
        HouseStore store = store();
        store.add("House Vale", "head");
        assertThrows(HouseStore.HouseRejected.class, () -> store.add("House Kalt", "head"));
    }

    /** A headless house can be edited by nobody and explains itself to nobody. */
    @Test
    void theLastHeadCannotStandDown() throws Exception {
        HouseStore store = store();
        House vale = store.add("House Vale", "head");
        assertThrows(HouseStore.HouseRejected.class, () -> store.removeHead(vale.id(), "head"));
        assertThrows(HouseStore.HouseRejected.class, () -> store.leave("head"));
    }

    @Test
    void aSecondHeadMakesAHandoverPossible() throws Exception {
        HouseStore store = store();
        House vale = store.add("House Vale", "first");
        store.addHead(vale.id(), "second");
        store.removeHead(vale.id(), "first");
        assertFalse(store.byId(vale.id()).orElseThrow().isHead("first"));
    }

    @Test
    void invitingThenAcceptingJoinsThem() throws Exception {
        HouseStore store = store();
        House vale = store.add("House Vale", "head");
        store.invite(vale.id(), "bard");
        assertEquals(1, store.invitationsFor("bard").size());

        store.accept(vale.id(), "bard");
        assertTrue(store.holding("bard").isPresent());
        assertTrue(store.invitationsFor("bard").isEmpty());
    }

    /** An invitation that can no longer be taken up is only there to be refused later. */
    @Test
    void acceptingOneWithdrawsTheRest() throws Exception {
        HouseStore store = store();
        House vale = store.add("House Vale", "one");
        House kalt = store.add("House Kalt", "two");
        store.invite(vale.id(), "bard");
        store.invite(kalt.id(), "bard");

        store.accept(vale.id(), "bard");
        assertTrue(store.invitationsFor("bard").isEmpty());
    }

    /** A title is the house's, and an ex-member wearing its rank is what the grant was for. */
    @Test
    void leavingGivesUpTheHousesTitles() throws Exception {
        HouseStore store = store();
        House vale = store.add("House Vale", "head");
        store.invite(vale.id(), "bard");
        store.accept(vale.id(), "bard");
        store.addTitle(vale.id(), "Ser");
        store.grantTitle(vale.id(), "bard", "Ser");

        store.leave("bard");
        assertTrue(store.byId(vale.id()).orElseThrow().titlesGrantedTo("bard").isEmpty());
    }

    @Test
    void onlyTitlesTheHouseDefinesCanBeGranted() throws Exception {
        HouseStore store = store();
        House vale = store.add("House Vale", "head");
        assertThrows(HouseStore.HouseRejected.class,
                () -> store.grantTitle(vale.id(), "head", "Emperor"));
    }

    @Test
    void survivesARestart() throws Exception {
        House vale = store().add("House Vale", "head");
        HouseStore reopened = new HouseStore(directory.resolve("houses.json"));
        assertEquals("House Vale", reopened.byId(vale.id()).orElseThrow().name());
    }

    /** The slug is what awards and memberships point at, so it must survive a rename. */
    @Test
    void theSlugIsStable() {
        assertEquals("house-vale", HouseStore.slug("House Vale"));
        assertEquals("house-vale", HouseStore.slug("  House   Vale!  ".strip()));
    }
}
