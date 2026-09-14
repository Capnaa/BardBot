package dev.capna.bardbot.path;

import dev.capna.bardbot.model.PathReward;
import dev.capna.bardbot.ops.ChannelRole;
import dev.capna.bardbot.ops.SettingsStore;
import dev.capna.bardbot.store.AwardLog;
import dev.capna.bardbot.store.PathStore;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Ending a month on the Path.
 *
 * <p>Two things happen. The tribunal is told who earned what, since everything above a voucher is
 * handed out by a person rather than the bot. Then the Pilgrim role comes off everyone who did not
 * reach the top line, because that is what free passage means: the ones who did keep it.
 *
 * <p>Runs on the same daily check as the house roll and is safe to repeat: the store remembers
 * the month, and that is written before anything is posted or removed, so a crash halfway costs
 * an announcement rather than stripping the role from whoever bought in that morning.
 *
 * <p>The first run after the feature is switched on does nothing but remember the month. A bot
 * deployed on the fifteenth must not take the role off everyone who holds it.
 */
public final class PathRoll {

    private static final Logger LOG = LoggerFactory.getLogger(PathRoll.class);

    private final PathOfVirtue path;
    private final PathStore store;
    private final AwardLog awards;
    private final SettingsStore settings;
    private final String guildId;
    private final ZoneId zone;

    public PathRoll(PathOfVirtue path, PathStore store, AwardLog awards, SettingsStore settings,
                    String guildId, ZoneId zone) {
        this.path = Objects.requireNonNull(path, "path");
        this.store = Objects.requireNonNull(store, "store");
        this.awards = Objects.requireNonNull(awards, "awards");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.guildId = Objects.requireNonNull(guildId, "guildId");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /** Rolls the month just ended, if it has not been rolled yet. */
    public void settleDue(JDA jda) {
        YearMonth ended = path.thisMonth().minusMonths(1);
        if (store.hasRolled(ended)) {
            return;
        }
        boolean firstEver = store.lastRolled().isEmpty();
        try {
            store.markRolled(ended);
        } catch (IOException e) {
            LOG.error("Could not record the Path roll for {}; it will be tried again", ended, e);
            return;
        }
        if (firstEver) {
            LOG.info("The Path is starting with {} as its first month; nothing to roll", ended);
            return;
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            LOG.warn("The guild is gone; the Path for {} was recorded but not rolled", ended);
            return;
        }
        Role pilgrim = guild.getRoleById(path.pilgrimRoleId());
        if (pilgrim == null) {
            LOG.warn("The Pilgrim role is gone; the Path for {} was recorded but not rolled", ended);
            return;
        }

        List<Member> pilgrims;
        try {
            pilgrims = guild.findMembersWithRoles(pilgrim).get();
        } catch (RuntimeException e) {
            LOG.error("Could not look up the Pilgrims; the Path for {} was recorded but not "
                    + "rolled", ended, e);
            return;
        }

        Map<String, Integer> earned = awards.earnedIn(ended, zone);
        Map<PathReward, List<String>> byReward = new EnumMap<>(PathReward.class);
        List<Member> losingTheRole = new ArrayList<>();
        for (Member member : pilgrims) {
            int score = earned.getOrDefault(member.getId(), 0);
            for (PathReward reward : PathReward.reachedBy(score)) {
                if (!reward.isVoucher()) {
                    byReward.computeIfAbsent(reward, r -> new ArrayList<>()).add(member.getId());
                }
            }
            if (!PathReward.FREE_PASSAGE.metBy(score)) {
                losingTheRole.add(member);
            }
        }

        post(jda, ended, pilgrims.size(), byReward);

        for (Member member : losingTheRole) {
            guild.removeRoleFromMember(member, pilgrim).queue(null, error ->
                    LOG.warn("Could not take the Pilgrim role off {}: {}",
                            member.getId(), error.getMessage()));
        }
        LOG.info("Rolled the Path for {}: {} Pilgrim(s), {} kept the role",
                ended, pilgrims.size(), pilgrims.size() - losingTheRole.size());
    }

    private void post(JDA jda, YearMonth month, int pilgrims,
                      Map<PathReward, List<String>> byReward) {
        Optional<String> channelId = settings.channel(ChannelRole.RENOWN);
        if (channelId.isEmpty()) {
            LOG.info("The Path for {} rolled, but no renown channel is set, so nothing was posted",
                    month);
            return;
        }
        TextChannel channel = jda.getTextChannelById(channelId.get());
        if (channel == null) {
            LOG.warn("The renown channel {} is gone; the Path for {} was not posted",
                    channelId.get(), month);
            return;
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("The Path of Virtue, " + month)
                .setDescription(pilgrims == 0
                        ? "Nobody walked the Path this month."
                        : pilgrims + (pilgrims == 1 ? " Pilgrim walked" : " Pilgrims walked")
                                + " the Path this month. Everyone below is owed what is listed; "
                                + "everyone not under Free Passage has lost the role and has "
                                + "to buy in again.");
        for (PathReward reward : PathReward.values()) {
            if (reward.isVoucher()) {
                continue;
            }
            List<String> who = byReward.getOrDefault(reward, List.of());
            embed.addField(reward.display() + " (" + reward.threshold() + ")",
                    who.isEmpty() ? "Nobody." : String.join("\n",
                            who.stream().map(id -> "<@" + id + ">").toList()), false);
        }
        channel.sendMessageEmbeds(embed.build()).queue(null, error ->
                LOG.warn("Could not post the Path for {}: {}", month, error.getMessage()));
    }
}
