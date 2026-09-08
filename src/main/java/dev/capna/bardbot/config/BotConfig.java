package dev.capna.bardbot.config;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Everything the bot is configured with, fixed once at startup.
 *
 * <p>Immutable, and deliberately separate from {@code ops}, which holds the handful of things an
 * operator changes while the bot is running. Typing it means a missing or malformed value is a
 * startup failure with a clear message rather than a NumberFormatException in the middle of a
 * month roll.
 *
 * <p>No secrets live here. The token comes from the environment, and this object is safe to log.
 */
public record BotConfig(Discord discord,
                        Paths paths,
                        Renown renown,
                        Limits limits,
                        Features features) {

    /**
     * @param guildId             the Bardonia guild, where commands are registered
     * @param tribunalRoleIds     who may award virtue and administer houses. A list rather than one
     *                            role, because the tribunal is a body and Discord servers routinely
     *                            split a body across several roles. Empty is refused at startup:
     *                            with nobody privileged, half the bot is unreachable.
     * @param announcementChannelId where goal unlocks are posted, absent to announce nowhere
     * @param renownChannelId     where the monthly standings are posted. Tribunal-only by that
     *                            channel's own permissions, not by anything the bot enforces, so
     *                            it must be a channel the rest of the guild cannot read.
     * @param consoleChannelId    where warnings and errors are mirrored, absent to mirror nowhere
     */
    public record Discord(String guildId,
                          List<String> tribunalRoleIds,
                          Optional<String> announcementChannelId,
                          Optional<String> renownChannelId,
                          Optional<String> consoleChannelId) {
    }

    /** @param dataDir durable state the bot owns: profiles, houses, titles, awards, settings */
    public record Paths(Path dataDir) {

        public Path profiles() {
            return dataDir.resolve("profiles.json");
        }

        public Path houses() {
            return dataDir.resolve("houses.json");
        }

        public Path titles() {
            return dataDir.resolve("titles.json");
        }

        public Path awards() {
            return dataDir.resolve("awards.json");
        }

        public Path renown() {
            return dataDir.resolve("renown.json");
        }

        public Path settings() {
            return dataDir.resolve("settings.json");
        }
    }

    /**
     * @param rollAt wall-clock time on the first of the month when the standings are posted and the
     *               month rolls over
     * @param zone   the zone that time is in, which must be stated rather than inherited from
     *               whatever host the bot happens to run on. It also decides where a month begins,
     *               so an award at half past eleven on the 31st belongs to the month the guild
     *               thinks it does rather than the month UTC does.
     */
    public record Renown(LocalTime rollAt, ZoneId zone) {
    }

    /**
     * @param maxConcurrentOperations size of the command executor
     * @param commandCooldown         per-user spacing, or zero to disable
     */
    public record Limits(int maxConcurrentOperations, Duration commandCooldown) {
    }

    /**
     * Startup defaults only. Once running these live in {@code ops}, where an operator can change
     * them without a redeploy.
     */
    public record Features(boolean profiles, boolean virtue, boolean houses, boolean titles) {
    }
}
