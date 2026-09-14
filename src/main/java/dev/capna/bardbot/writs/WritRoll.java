package dev.capna.bardbot.writs;

import dev.capna.bardbot.store.WritStore;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * Handing out the month's writ.
 *
 * <p>Runs on the same daily check as the house roll, and like it, is safe to run as often as you
 * like: the store remembers which month has been granted, so a restart on the first costs one
 * comparison and does nothing.
 *
 * <p>Who is on the tribunal is read from Discord at the moment of the grant, not from anything
 * stored. A member who joined the tribunal in the middle of a month gets their first writ on the
 * next first, and one who left stops getting them, without anybody having to tell the bot.
 *
 * <p>Unlike the house roll this does not catch up on months it missed. A writ is a monthly
 * allowance, not a record, and handing out three at once to make up for a long outage would
 * blow straight through the cap and be lost anyway.
 */
public final class WritRoll {

    private static final Logger LOG = LoggerFactory.getLogger(WritRoll.class);

    private final WritStore writs;
    private final String guildId;
    private final List<String> tribunalRoleIds;
    private final ZoneId zone;

    public WritRoll(WritStore writs, String guildId, List<String> tribunalRoleIds, ZoneId zone) {
        this.writs = Objects.requireNonNull(writs, "writs");
        this.guildId = Objects.requireNonNull(guildId, "guildId");
        this.tribunalRoleIds = List.copyOf(Objects.requireNonNull(tribunalRoleIds, "tribunalRoleIds"));
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /** Grants this month's writ if it has not been granted yet. */
    public void grantDue(JDA jda) {
        YearMonth month = YearMonth.from(Instant.now().atZone(zone));
        if (writs.hasGranted(month)) {
            return;
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            LOG.warn("The guild is gone; the writ for {} was not granted", month);
            return;
        }
        List<Role> roles = tribunalRoleIds.stream()
                .map(guild::getRoleById)
                .filter(Objects::nonNull)
                .toList();
        if (roles.isEmpty()) {
            LOG.warn("None of the tribunal roles exist in {}; the writ for {} was not granted",
                    guild.getName(), month);
            return;
        }

        // Fetched rather than read from the cache, so a member the bot has not seen since it
        // started still gets theirs. Blocking is fine: this runs on the schedule thread.
        List<String> members;
        try {
            members = guild.findMembersWithRoles(roles).get().stream()
                    .map(Member::getId)
                    .toList();
        } catch (RuntimeException e) {
            LOG.error("Could not look up the tribunal; the writ for {} will be tried again",
                    month, e);
            return;
        }

        try {
            writs.grantMonthly(month, members);
        } catch (IOException e) {
            LOG.error("Could not grant the writ for {}; it will be tried again", month, e);
        }
    }
}
