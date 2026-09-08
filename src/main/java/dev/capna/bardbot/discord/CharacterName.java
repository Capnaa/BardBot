package dev.capna.bardbot.discord;

import dev.capna.bardbot.model.Profile;
import dev.capna.bardbot.model.TitleSlot;

/**
 * A Bard's name with their titles worked into it.
 *
 * <p>The three titles read as one line, {@code Lord Prophet Azurov the Veteran of The Tribunal} 
 * and the words between them belong to the titles rather than to the name. "the" appears only with
 * a virtue title and "of" only with a government one, so a Bard holding none, one, two or all three
 * reads correctly without any of them being a special case.
 */
public final class CharacterName {

    private CharacterName() {
    }

    /**
     * @param fallback shown when the Bard has never set a name, normally their Discord display
     *                 name, so a profile always has something at the top of it
     */
    public static String of(Profile profile, String fallback) {
        String name = profile.name().orElse(fallback);

        StringBuilder line = new StringBuilder();
        profile.title(TitleSlot.NOBLE).ifPresent(noble -> line.append(noble).append(' '));
        line.append(name);
        profile.title(TitleSlot.VIRTUE).ifPresent(virtue ->
                line.append(" the ").append(virtue));
        profile.title(TitleSlot.GOVERNMENT).ifPresent(government ->
                line.append(" of ").append(government));

        // An embed title is not formatted by Discord, so it is cleaned rather than escaped: a
        // backslash there would simply be shown to the reader.
        return Names.plain(line.toString());
    }
}
