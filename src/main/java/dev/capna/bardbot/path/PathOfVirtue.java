package dev.capna.bardbot.path;

import dev.capna.bardbot.model.PathReward;
import dev.capna.bardbot.model.Virtue;
import dev.capna.bardbot.ops.ChannelRole;
import dev.capna.bardbot.ops.SettingsStore;
import dev.capna.bardbot.store.AwardLog;
import dev.capna.bardbot.store.PathStore;
import dev.capna.bardbot.virtue.Awarding;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The Path of Virtue: a monthly ladder for whoever holds the Pilgrim role.
 *
 * <p>Progress is the virtue a Bard earned since the first of the month, the same window house
 * renown uses, so there is nothing to reset when the month turns. The role is a filter on who gets
 * paid, not a starting line: a Bard who buys in on the twentieth gets credit for the month they
 * already had.
 *
 * <p>Nothing else on the bot needs this. Awards, titles, houses and writs work the same whether
 * somebody is a Pilgrim or not; the only thing the role gates is the rewards on the ladder.
 */
public final class PathOfVirtue {

    private static final Logger LOG = LoggerFactory.getLogger(PathOfVirtue.class);

    private final AwardLog awards;
    private final PathStore store;
    private final Awarding awarding;
    private final SettingsStore settings;
    private final String guildId;
    private final String pilgrimRoleId;
    private final ZoneId zone;

    public PathOfVirtue(AwardLog awards, PathStore store, Awarding awarding,
                        SettingsStore settings, String guildId, String pilgrimRoleId,
                        ZoneId zone) {
        this.awards = Objects.requireNonNull(awards, "awards");
        this.store = Objects.requireNonNull(store, "store");
        this.awarding = Objects.requireNonNull(awarding, "awarding");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.guildId = Objects.requireNonNull(guildId, "guildId");
        this.pilgrimRoleId = Objects.requireNonNull(pilgrimRoleId, "pilgrimRoleId");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /** The month as the guild reckons it. */
    public YearMonth thisMonth() {
        return YearMonth.from(Instant.now().atZone(zone));
    }

    /** How far along the ladder a Bard is this month. */
    public int earnedThisMonth(String userId) {
        return awards.earnedIn(userId, thisMonth(), zone);
    }

    public int vouchers(String userId) {
        return store.vouchers(userId);
    }

    public boolean isPilgrim(Member member) {
        return member != null && member.getRoles().stream()
                .map(Role::getId)
                .anyMatch(pilgrimRoleId::equals);
    }

    public String pilgrimRoleId() {
        return pilgrimRoleId;
    }

    /**
     * Pays out whatever an award just carried a Pilgrim past.
     *
     * <p>Called after every award, and cheap when there is nothing to do: the ladder is checked
     * against the store before Discord is asked whether they hold the role, so most awards cost
     * two sums and no request.
     *
     * <p>Vouchers are the only reward handed over here. The rest are names on the first of the
     * month's list, and that list is drawn from the log, not from anything recorded now.
     */
    public void advance(JDA jda, String recipientId) {
        YearMonth month = thisMonth();
        List<PathReward> reached = PathReward.reachedBy(awards.earnedIn(recipientId, month, zone));
        Set<PathReward> already = store.granted(recipientId, month);
        if (reached.stream().allMatch(already::contains)) {
            return;
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            return;
        }
        Member member;
        try {
            member = guild.retrieveMemberById(recipientId).complete();
        } catch (RuntimeException e) {
            LOG.warn("Could not look up {} for the Path: {}", recipientId, e.getMessage());
            return;
        }
        if (!isPilgrim(member)) {
            return;
        }

        List<PathReward> fresh;
        try {
            fresh = store.grant(recipientId, month, reached);
        } catch (IOException e) {
            LOG.error("Could not record what {} reached on the Path; it will be paid on their "
                    + "next award", recipientId, e);
            return;
        }
        announce(jda, recipientId, fresh);
    }

    /**
     * Spends a voucher on five virtue in whatever the Bard picked.
     *
     * <p>The award goes through the same door as every other one, so it crosses the same goals
     * and counts for the same titles and renown. The flag on it is the only difference, and all
     * the flag does is keep it off this ladder.
     */
    public Awarding.Result claim(String userId, Virtue virtue)
            throws IOException, PathStore.PathRejected {
        store.claim(userId);
        return awarding.apply(userId, userId, virtue, PathReward.VOUCHER_VIRTUE,
                Optional.of("Virtue Voucher"), true);
    }

    /**
     * Says what was reached, in the awards channel.
     *
     * <p>The awards channel rather than unlocks, because a voucher is something you go and spend
     * and the message says how, so it belongs where the Bard is already looking.
     */
    private void announce(JDA jda, String recipientId, List<PathReward> fresh) {
        if (fresh.isEmpty()) {
            return;
        }
        Optional<String> channelId = settings.channel(ChannelRole.AWARDS);
        if (channelId.isEmpty()) {
            return;
        }
        TextChannel channel = jda.getTextChannelById(channelId.get());
        if (channel == null) {
            LOG.warn("The awards channel {} is gone; nothing was announced", channelId.get());
            return;
        }
        StringBuilder text = new StringBuilder("<@").append(recipientId)
                .append("> has reached ")
                .append(String.join(" and ", fresh.stream()
                        .map(reward -> reward.threshold() + " virtue on the Path")
                        .toList()))
                .append('.');
        long vouchers = fresh.stream().filter(PathReward::isVoucher).count();
        if (vouchers > 0) {
            text.append("\n\nThat is a Virtue Voucher")
                    .append(vouchers == 1 ? "" : " each")
                    .append(", worth ").append(PathReward.VOUCHER_VIRTUE)
                    .append(" virtue wherever you like. Spend it with /path voucher claim.");
        }
        fresh.stream().filter(reward -> !reward.isVoucher()).findFirst().ifPresent(reward ->
                text.append("\n\nThe tribunal will be told you earned the ")
                        .append(reward.display().toLowerCase(java.util.Locale.ROOT))
                        .append(" on the first of the month."));
        channel.sendMessage(text.toString()).queue(null, error ->
                LOG.warn("Could not announce the Path: {}", error.getMessage()));
    }
}
