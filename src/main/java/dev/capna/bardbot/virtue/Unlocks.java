package dev.capna.bardbot.virtue;

import dev.capna.bardbot.model.Goal;
import dev.capna.bardbot.ops.ChannelRole;
import dev.capna.bardbot.ops.SettingsStore;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Telling a Bard what they have just unlocked.
 *
 * <p>Announced only on the crossing. An award that leaves somebody already past a goal still past
 * it has unlocked nothing, and a channel that says so every time would be a channel people mute.
 * Falling back below a goal is silent for the same reason it is not a punishment: awards can be
 * negative, and the bot should not publish that somebody has lost a title.
 */
public final class Unlocks {

    private static final Logger LOG = LoggerFactory.getLogger(Unlocks.class);

    private final SettingsStore settings;

    public Unlocks(SettingsStore settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Posts one message for everything an award unlocked.
     *
     * <p>One message however many goals were crossed: a large award can carry a Bard through three
     * tiers at once, and three separate pings for one award reads like a malfunction.
     *
     * <p>Does nothing when no unlocks channel has been set. Announcing is optional, and a guild
     * that has not chosen a channel should not have the bot picking one.
     */
    public void announce(JDA jda, String recipientId, List<Goal> crossed) {
        if (crossed.isEmpty()) {
            return;
        }
        settings.channel(ChannelRole.UNLOCKS).ifPresent(channelId -> {
            TextChannel channel = jda.getTextChannelById(channelId);
            if (channel == null) {
                LOG.warn("The unlocks channel {} is gone; nothing was announced", channelId);
                return;
            }
            channel.sendMessage(message(recipientId, crossed)).queue(null, error ->
                    LOG.warn("Could not announce an unlock: {}", error.getMessage()));
        });
    }

    private static String message(String recipientId, List<Goal> crossed) {
        StringBuilder text = new StringBuilder("<@").append(recipientId).append("> has reached ");

        text.append(String.join(", and ", crossed.stream().map(Unlocks::reached).toList()))
                .append('.');

        List<String> titles = crossed.stream().flatMap(goal -> goal.title().stream()).toList();
        if (!titles.isEmpty()) {
            text.append("\n\nYou have unlocked the virtue title")
                    .append(titles.size() == 1 ? " " : "s ")
                    .append(String.join(", ", titles.stream().map(t -> "\"" + t + "\"").toList()))
                    .append(".\nEquip ")
                    .append(titles.size() == 1 ? "it" : "one")
                    .append(" with /title set virtue and pick from the list.");
        }
        crossed.stream().flatMap(goal -> goal.note().stream()).findFirst()
                .ifPresent(note -> text.append("\n\n").append(note));
        return text.toString();
    }

    /** {@code 40 Honor}, or {@code 100 total virtue} for a goal measured against the total. */
    private static String reached(Goal goal) {
        return goal.threshold() + " "
                + goal.virtue().map(virtue -> virtue.display()).orElse("total virtue");
    }
}
