package dev.capna.bardbot.discord;

import dev.capna.bardbot.houses.Renown;
import dev.capna.bardbot.ops.BoardKind;
import dev.capna.bardbot.ops.SettingsStore;
import dev.capna.bardbot.store.AwardLog;
import dev.capna.bardbot.store.ProfileStore;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The standing leaderboards that keep themselves up to date.
 *
 * <p>One message per board, edited in place rather than reposted, so a channel holds one board that
 * is always current instead of a scroll of increasingly wrong ones.
 *
 * <p>Refreshes are collapsed. A tribunal member awarding ten people in a row would otherwise be ten
 * edits of the same message within a minute, which is both pointless and the fastest way to be rate
 * limited. The first award schedules one refresh; every award until it fires is already covered by
 * it.
 */
public final class LiveBoards {

    private static final Logger LOG = LoggerFactory.getLogger(LiveBoards.class);

    /** Long enough to absorb a run of awards, short enough that the board is never stale for long. */
    private static final Duration SETTLE = Duration.ofSeconds(60);

    private final SettingsStore settings;
    private final AwardLog awards;
    private final ProfileStore profiles;
    private final Renown renown;
    private final dev.capna.bardbot.store.HouseStore houses;
    private final ScheduledExecutorService schedule;

    /** Whether a refresh is already coming, so a burst of awards only ever books one. */
    private final AtomicBoolean pending = new AtomicBoolean();

    public LiveBoards(SettingsStore settings, AwardLog awards, ProfileStore profiles,
                      Renown renown, dev.capna.bardbot.store.HouseStore houses,
                      ScheduledExecutorService schedule) {
        this.houses = Objects.requireNonNull(houses, "houses");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.awards = Objects.requireNonNull(awards, "awards");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.renown = Objects.requireNonNull(renown, "renown");
        this.schedule = Objects.requireNonNull(schedule, "schedule");
    }

    /** Called after every award. Cheap, and does nothing at all when no board has been planted. */
    public void refreshSoon(JDA jda) {
        if (settings.board(BoardKind.VIRTUE).isEmpty() && settings.board(BoardKind.HOUSE).isEmpty()) {
            return;
        }
        if (pending.compareAndSet(false, true)) {
            schedule.schedule(() -> {
                pending.set(false);
                refreshNow(jda);
            }, SETTLE.toSeconds(), TimeUnit.SECONDS);
        }
    }

    /** Redraws both boards, skipping either that has not been planted. */
    public void refreshNow(JDA jda) {
        for (BoardKind kind : BoardKind.values()) {
            settings.board(kind).ifPresent(location -> edit(jda, kind, location));
        }
    }

    public MessageEmbed render(BoardKind kind) {
        return switch (kind) {
            case VIRTUE -> LeaderboardEmbed.of(Optional.empty(),
                    awards.standings(Optional.empty()), profiles, 1, "");
            case HOUSE -> HouseEmbed.leaderboard(kind.display(), renown.thisMonthTable().stream()
                    .limit(LeaderboardEmbed.PAGE_SIZE)
                    .toList(), houses::colorOf);
        };
    }

    private void edit(JDA jda, BoardKind kind, String location) {
        String[] parts = location.split("/");
        TextChannel channel = jda.getTextChannelById(parts[0]);
        if (channel == null) {
            LOG.warn("The {} board's channel {} is gone", kind.key(), parts[0]);
            return;
        }
        channel.editMessageEmbedsById(parts[1], render(kind)).queue(null, error ->
                // Usually the message was deleted. Saying so once beats trying forever, and the
                // fix is to plant a new one.
                LOG.warn("Could not update the {} board; plant a new one with /admin leaderboard: {}",
                        kind.key(), error.getMessage()));
    }
}
