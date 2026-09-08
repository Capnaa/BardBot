package dev.capna.bardbot.titles;

import dev.capna.bardbot.model.Goal;
import dev.capna.bardbot.model.House;
import dev.capna.bardbot.model.Profile;
import dev.capna.bardbot.model.TitleSlot;
import dev.capna.bardbot.model.Virtue;
import dev.capna.bardbot.store.AwardLog;
import dev.capna.bardbot.store.CatalogueStore;
import dev.capna.bardbot.store.HouseStore;
import dev.capna.bardbot.store.ProfileStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What a Bard is allowed to wear.
 *
 * <p>The three slots are filled from three different authorities and are worked out here rather
 * than stored, so a title cannot outlive the thing that granted it. Leaving a house takes its
 * titles with you; a virtue title is held for exactly as long as the score behind it.
 *
 * <p>Every place that offers a title reads from this: the autocomplete, the list, and the check
 * made when one is equipped. If they read from anywhere else they could disagree, and a Bard would
 * be offered a title the bot then refuses.
 */
public final class Titles {

    private final ProfileStore profiles;
    private final HouseStore houses;
    private final AwardLog awards;
    private final CatalogueStore catalogue;

    public Titles(ProfileStore profiles, HouseStore houses, AwardLog awards,
                  CatalogueStore catalogue) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.houses = Objects.requireNonNull(houses, "houses");
        this.awards = Objects.requireNonNull(awards, "awards");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
    }

    /** Everything they hold in one slot, in the order it should be offered. */
    public List<String> available(String userId, TitleSlot slot) {
        return switch (slot) {
            case NOBLE -> noble(userId);
            case VIRTUE -> virtue(userId);
            case GOVERNMENT -> new ArrayList<>(profiles.get(userId).governmentTitles());
        };
    }

    /** Whether they may equip this exact title, matched as written. */
    public boolean holds(String userId, TitleSlot slot, String title) {
        return available(userId, slot).stream().anyMatch(held -> held.equalsIgnoreCase(title));
    }

    /**
     * The titles their house has granted them.
     *
     * <p>Intersected with what the house still defines, so a title the head has since deleted stops
     * being offered without anybody having to go and revoke it from every member.
     */
    private List<String> noble(String userId) {
        Optional<House> house = houses.holding(userId);
        if (house.isEmpty()) {
            return List.of();
        }
        House held = house.get();
        return held.nobleTitles().stream()
                .filter(title -> held.titlesGrantedTo(userId).stream()
                        .anyMatch(granted -> granted.equalsIgnoreCase(title)))
                .toList();
    }

    /**
     * The virtue titles their scores have unlocked.
     *
     * <p>Recomputed rather than remembered, so a title is worn for exactly as long as the score
     * behind it. A correction that takes somebody back below a threshold quietly removes the title
     * from their options, and unequips it the next time they are drawn.
     */
    private List<String> virtue(String userId) {
        Map<Virtue, Integer> scores = awards.scores(userId);
        int total = scores.values().stream().mapToInt(Integer::intValue).sum();

        // A set, since the same title may hang off more than one goal.
        LinkedHashSet<String> unlocked = new LinkedHashSet<>();
        for (Goal goal : catalogue.current().goals()) {
            int score = goal.virtue().map(scores::get).orElse(total);
            if (goal.metBy(score)) {
                goal.title().ifPresent(unlocked::add);
            }
        }
        return new ArrayList<>(unlocked);
    }

    /**
     * Drops any equipped title the Bard no longer holds.
     *
     * <p>Titles are lost as well as gained: a house is left, a grant is revoked, a correction takes
     * a score back below its threshold. Rather than hunting down every equipped copy at the moment
     * that happens, the profile is cleaned when it is read, which cannot be forgotten and cannot be
     * raced.
     *
     * @return the profile as it should be shown, unchanged when nothing was lost
     */
    public Profile pruned(Profile profile) {
        Map<TitleSlot, String> equipped = new java.util.EnumMap<>(TitleSlot.class);
        for (Map.Entry<TitleSlot, String> entry : profile.equipped().entrySet()) {
            if (holds(profile.userId(), entry.getKey(), entry.getValue())) {
                equipped.put(entry.getKey(), entry.getValue());
            }
        }
        return equipped.size() == profile.equipped().size()
                ? profile
                : new Profile(profile.userId(), profile.name(), profile.gender(), profile.age(),
                        profile.description(), profile.imageUrl(), profile.wikiUrl(),
                        equipped, profile.governmentTitles());
    }
}
