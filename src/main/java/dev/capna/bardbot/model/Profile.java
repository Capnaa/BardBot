package dev.capna.bardbot.model;

import java.util.Map;
import java.util.Optional;

/**
 * One Bard's character, as they have described it.
 *
 * <p>One per Discord user, created on first interaction rather than registered, so nobody has to
 * sign up before they can be awarded anything. Every field is optional: a profile that has never
 * been edited is still a real profile with real virtue on it.
 *
 * <p>Virtue is deliberately not here. Scores are a sum over the award log, so a profile cannot
 * carry a number that disagrees with the awards that produced it.
 *
 * @param userId       the Discord user this belongs to, and the only field that is never blank
 * @param name         the character's name, which is not the Discord display name
 * @param imageUrl     an image the character is drawn as, hosted elsewhere. Held as a URL because
 *                     Discord fetches it to render the embed; the bot never retrieves it.
 * @param equipped     which title the Bard has chosen in each slot, absent for an empty slot
 * @param governmentTitles titles the tribunal has granted this Bard. Noble grants live with the
 *                     house that made them, and virtue titles are earned rather than granted, so
 *                     this is the only grant a profile carries.
 */
public record Profile(String userId,
                      Optional<String> name,
                      Optional<String> gender,
                      Optional<String> age,
                      Optional<String> description,
                      Optional<String> familyTree,
                      Optional<String> imageUrl,
                      Optional<String> wikiUrl,
                      Map<TitleSlot, String> equipped,
                      java.util.Set<String> governmentTitles) {

    /**
     * Field lengths.
     *
     * <p>Chosen so that a profile with every field filled to the limit still fits one embed without
     * Discord truncating it, which it does silently and mid-word. Enforced here as well as in the
     * modal that collects them, because a modal is a client and clients can be lied to.
     */
    public static final int MAX_NAME = 32;
    public static final int MAX_GENDER = 20;
    public static final int MAX_AGE = 12;
    public static final int MAX_DESCRIPTION = 900;
    public static final int MAX_FAMILY_TREE = 400;
    public static final int MAX_URL = 300;

    public Profile {
        equipped = Map.copyOf(equipped);
        governmentTitles = java.util.Set.copyOf(governmentTitles);
    }

    /** A profile for someone who has never edited one. */
    public static Profile empty(String userId) {
        return new Profile(userId,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Map.of(), java.util.Set.of());
    }

    public Optional<String> title(TitleSlot slot) {
        return Optional.ofNullable(equipped.get(slot));
    }
}
