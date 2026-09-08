package dev.capna.bardbot.houses;

import dev.capna.bardbot.model.Standings;
import dev.capna.bardbot.ops.ChannelRole;
import dev.capna.bardbot.ops.SettingsStore;
import dev.capna.bardbot.store.AwardLog;
import dev.capna.bardbot.store.RenownStore;
import net.dv8tion.jda.api.JDA;
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

/**
 * Settling a month.
 *
 * <p>A month is settled by archiving its table, not by wiping anything: renown is a window over the
 * award log, so the new month starts at zero on its own. What the archive adds is permanence, since
 * an award corrected in March must not rewrite who won February.
 *
 * <p>The archive is written before the standings are posted, which is what makes this safe to run
 * repeatedly. A crash between the two costs an announcement somebody can ask for; the other order
 * would repost February's result on every restart for a day.
 *
 * <p>It catches up rather than only handling the month just gone. A bot that was down across a
 * boundary, or across three of them, settles every month it missed, oldest first, so the record
 * has no holes in it.
 */
public final class MonthRoll {

    private static final Logger LOG = LoggerFactory.getLogger(MonthRoll.class);

    private final Renown renown;
    private final RenownStore archive;
    private final AwardLog awards;
    private final SettingsStore settings;
    private final ZoneId zone;

    public MonthRoll(Renown renown, RenownStore archive, AwardLog awards, SettingsStore settings,
                     ZoneId zone) {
        this.renown = Objects.requireNonNull(renown, "renown");
        this.archive = Objects.requireNonNull(archive, "archive");
        this.awards = Objects.requireNonNull(awards, "awards");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /**
     * Settles every month that has ended and has not been settled yet.
     *
     * <p>Safe to call as often as you like. A month already in the archive is skipped, so running
     * this hourly costs one comparison and does nothing.
     */
    public void settleDue(JDA jda) {
        Optional<Instant> earliest = awards.earliest();
        if (earliest.isEmpty()) {
            return;
        }
        YearMonth current = renown.thisMonth();
        YearMonth month = YearMonth.from(earliest.get().atZone(zone));

        while (month.isBefore(current)) {
            if (!archive.hasRolled(month)) {
                settle(jda, month);
            }
            month = month.plusMonths(1);
        }
    }

    private void settle(JDA jda, YearMonth month) {
        List<Standings.Place> places = renown.tableFor(month);
        Standings standings = new Standings(month, places, Instant.now());
        try {
            archive.archive(standings);
        } catch (IOException e) {
            // Not posted either, deliberately. An announcement without the archive behind it would
            // be repeated on the next restart.
            LOG.error("Could not settle {}; it will be tried again", month, e);
            return;
        }
        post(jda, standings);
    }

    private void post(JDA jda, Standings standings) {
        Optional<String> channelId = settings.channel(ChannelRole.RENOWN);
        if (channelId.isEmpty()) {
            LOG.info("{} settled, but no renown channel is set, so nothing was posted",
                    standings.month());
            return;
        }
        TextChannel channel = jda.getTextChannelById(channelId.get());
        if (channel == null) {
            LOG.warn("The renown channel {} is gone; {} was settled but not posted",
                    channelId.get(), standings.month());
            return;
        }
        channel.sendMessageEmbeds(StandingsEmbed.of(standings)).queue(null, error ->
                LOG.warn("Could not post the standings for {}: {}",
                        standings.month(), error.getMessage()));
    }
}
