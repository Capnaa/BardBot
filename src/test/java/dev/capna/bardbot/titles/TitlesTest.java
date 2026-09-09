package dev.capna.bardbot.titles;

import dev.capna.bardbot.model.Award;
import dev.capna.bardbot.model.House;
import dev.capna.bardbot.model.Profile;
import dev.capna.bardbot.model.TitleSlot;
import dev.capna.bardbot.model.Virtue;
import dev.capna.bardbot.store.AwardLog;
import dev.capna.bardbot.store.CatalogueStore;
import dev.capna.bardbot.store.HouseStore;
import dev.capna.bardbot.store.ProfileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitlesTest {

    @TempDir
    Path directory;

    private ProfileStore profiles;
    private HouseStore houses;
    private AwardLog awards;
    private Titles titles;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(directory.resolve("titles.json"), """
                {"goals": [
                  {"virtue": "honor", "threshold": 40, "title": "Veteran"},
                  {"virtue": "total", "threshold": 100, "title": "Renowned"}
                ]}""", StandardCharsets.UTF_8);

        profiles = new ProfileStore(directory.resolve("profiles.json"));
        houses = new HouseStore(directory.resolve("houses.json"));
        awards = new AwardLog(directory.resolve("awards.json"));
        titles = new Titles(profiles, houses, awards,
                new CatalogueStore(directory.resolve("titles.json")));
    }

    private void award(String userId, Virtue virtue, int amount) throws IOException {
        awards.append(new Award(userId, "tribunal", virtue, amount,
                Optional.empty(), Optional.empty(), Instant.now()));
    }

    @Test
    void aVirtueTitleAppearsOnceTheScoreDoes() throws IOException {
        assertTrue(titles.available("bard", TitleSlot.VIRTUE).isEmpty());
        award("bard", Virtue.HONOR, 40);
        assertEquals(1, titles.available("bard", TitleSlot.VIRTUE).size());
        assertTrue(titles.holds("bard", TitleSlot.VIRTUE, "Veteran"));
    }

    /** Held for exactly as long as the score behind it, so a correction takes it away. */
    @Test
    void aCorrectionTakesTheVirtueTitleBack() throws IOException {
        award("bard", Virtue.HONOR, 40);
        award("bard", Virtue.HONOR, -5);
        assertFalse(titles.holds("bard", TitleSlot.VIRTUE, "Veteran"));
    }

    @Test
    void goalsAgainstTheTotalCountEveryVirtue() throws IOException {
        award("bard", Virtue.HONOR, 30);
        award("bard", Virtue.GLORY, 30);
        award("bard", Virtue.FAME, 40);
        assertTrue(titles.holds("bard", TitleSlot.VIRTUE, "Renowned"));
    }

    @Test
    void nobleTitlesNeedBothAGrantAndTheHouseToStillDefineThem() throws Exception {
        House vale = houses.add("House Vale", "head");
        houses.addTitle(vale.id(), "Ser");
        assertTrue(titles.available("head", TitleSlot.NOBLE).isEmpty());

        houses.grantTitle(vale.id(), "head", "Ser");
        assertTrue(titles.holds("head", TitleSlot.NOBLE, "Ser"));

        houses.removeTitle(vale.id(), "Ser");
        assertFalse(titles.holds("head", TitleSlot.NOBLE, "Ser"));
    }

    /**
     * The cleanup that means nothing has to hunt down every profile wearing a lost title.
     */
    @Test
    void pruningDropsWhatIsNoLongerHeld() throws IOException {
        Map<TitleSlot, String> worn = new EnumMap<>(TitleSlot.class);
        worn.put(TitleSlot.VIRTUE, "Veteran");
        worn.put(TitleSlot.GOVERNMENT, "The Tribunal");

        Profile wearing = new Profile("bard", Optional.of("Capna"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                worn, Set.of("The Tribunal"));

        Profile pruned = titles.pruned(wearing);
        assertTrue(pruned.title(TitleSlot.VIRTUE).isEmpty());
        assertTrue(pruned.title(TitleSlot.GOVERNMENT).isPresent());
    }

    @Test
    void pruningLeavesAValidProfileAlone() throws IOException {
        award("bard", Virtue.HONOR, 40);
        Map<TitleSlot, String> worn = new EnumMap<>(TitleSlot.class);
        worn.put(TitleSlot.VIRTUE, "Veteran");
        Profile wearing = new Profile("bard", Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                worn, Set.of());
        assertEquals(wearing, titles.pruned(wearing));
    }
}
