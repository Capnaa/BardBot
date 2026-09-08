package dev.capna.bardbot.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A noble house, its people, and the titles it can hand out.
 *
 * <p>Renown is not a field. It is the virtue a house's members earned within a window, summed from
 * the award log, so correcting an award corrects every house figure it touched without anybody
 * having to remember to. It also settles what happens when a Bard changes house mid-month: each
 * award records the house that held them at the time, so what they earned stays where it was
 * earned.
 *
 * @param id           a stable slug, kept separate from the name so a house can be renamed without
 *                     orphaning the awards and memberships that point at it
 * @param headIds      who may edit this house and grant its titles. A list because a house may be
 *                     led by more than one person, and stored here rather than as a Discord role so
 *                     the house's leadership and its record cannot disagree.
 * @param invitedIds   Bards who have been invited and have not yet answered
 * @param nobleTitles  the titles this house has defined, in the order the head wrote them
 * @param nobleGrants  which of those titles each member has been given
 */
public record House(String id,
                    String name,
                    Optional<String> motto,
                    Optional<String> description,
                    Optional<String> crestUrl,
                    List<String> headIds,
                    Set<String> memberIds,
                    Set<String> invitedIds,
                    List<String> nobleTitles,
                    Map<String, Set<String>> nobleGrants) {

    public static final int MAX_NAME = 40;
    public static final int MAX_MOTTO = 100;
    public static final int MAX_DESCRIPTION = 700;
    public static final int MAX_TITLE = 32;

    public House {
        headIds = List.copyOf(headIds);
        memberIds = Set.copyOf(memberIds);
        invitedIds = Set.copyOf(invitedIds);
        nobleTitles = List.copyOf(nobleTitles);
        nobleGrants = Map.copyOf(nobleGrants);
    }

    public boolean isHead(String userId) {
        return headIds.contains(userId);
    }

    /**
     * Heads are members. Stating it once here keeps every caller from having to remember it, and
     * keeps a head from being missing from their own house's member count.
     */
    public boolean holds(String userId) {
        return memberIds.contains(userId) || headIds.contains(userId);
    }

    public Set<String> titlesGrantedTo(String userId) {
        return nobleGrants.getOrDefault(userId, Set.of());
    }
}
