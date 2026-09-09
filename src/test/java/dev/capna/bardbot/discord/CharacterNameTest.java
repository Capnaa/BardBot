package dev.capna.bardbot.discord;

import dev.capna.bardbot.model.Profile;
import dev.capna.bardbot.model.TitleSlot;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The joining words belong to the titles, not to the name, so every combination has to read
 * correctly without any of them being a special case.
 */
class CharacterNameTest {

    private static Profile with(String name, Map<TitleSlot, String> titles) {
        return new Profile("1", Optional.of(name), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), titles, Set.of());
    }

    private static Map<TitleSlot, String> titles(Object... pairs) {
        Map<TitleSlot, String> map = new EnumMap<>(TitleSlot.class);
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((TitleSlot) pairs[i], (String) pairs[i + 1]);
        }
        return map;
    }

    @Test
    void allThree() {
        assertEquals("Lord Capna the Veteran of The Tribunal",
                CharacterName.of(with("Capna", titles(
                        TitleSlot.NOBLE, "Lord",
                        TitleSlot.VIRTUE, "Veteran",
                        TitleSlot.GOVERNMENT, "The Tribunal")), "fallback"));
    }

    @Test
    void noneAtAll() {
        assertEquals("Capna", CharacterName.of(with("Capna", titles()), "fallback"));
    }

    @Test
    void theOnlyAppearsWithAVirtueTitle() {
        assertEquals("Lord Capna",
                CharacterName.of(with("Capna", titles(TitleSlot.NOBLE, "Lord")), "fallback"));
    }

    @Test
    void ofOnlyAppearsWithAGovernmentTitle() {
        assertEquals("Capna of The Tribunal",
                CharacterName.of(with("Capna", titles(
                        TitleSlot.GOVERNMENT, "The Tribunal")), "fallback"));
    }

    @Test
    void fallsBackWhenNoNameIsSet() {
        Profile blank = Profile.empty("1");
        assertEquals("brandon", CharacterName.of(blank, "brandon"));
    }
}
