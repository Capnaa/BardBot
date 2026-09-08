package dev.capna.bardbot.ops;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Warnings and errors, mirrored into a channel.
 *
 * <p>Whoever runs this bot is not watching a terminal. Without this, the first anyone knows about a
 * store that will not write is somebody complaining that their profile keeps reverting.
 *
 * <p>Repeats are collapsed into one line with a count. A failure that happens every minute is
 * otherwise 1,440 messages a day, which is indistinguishable from the channel being broken.
 *
 * <p>Sends on a timer rather than per event, for two reasons: a burst of related failures reads
 * better as one message, and logging from inside a Discord send would otherwise be able to log
 * about its own logging.
 */
public final class ConsoleMirror extends AppenderBase<ILoggingEvent> {

    /** Long enough to gather a burst, short enough that a problem is not learned about late. */
    private static final Duration FLUSH = Duration.ofSeconds(15);

    /** Discord's message limit is 2000; this leaves room for the counts and the fences. */
    private static final int MAX_BODY = 1800;

    private final JDA jda;
    private final SettingsStore settings;

    /** Message to how many times it has been seen since the last flush. Insertion ordered. */
    private final Map<String, Integer> pending = new LinkedHashMap<>();

    public ConsoleMirror(JDA jda, SettingsStore settings, ScheduledExecutorService schedule) {
        this.jda = Objects.requireNonNull(jda, "jda");
        this.settings = Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(schedule, "schedule").scheduleAtFixedRate(
                this::flush, FLUSH.toSeconds(), FLUSH.toSeconds(), TimeUnit.SECONDS);
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!event.getLevel().isGreaterOrEqual(Level.WARN)) {
            return;
        }
        String line = event.getLevel() + " " + event.getLoggerName()
                .substring(event.getLoggerName().lastIndexOf('.') + 1)
                + " — " + event.getFormattedMessage();
        synchronized (pending) {
            pending.merge(line, 1, Integer::sum);
        }
    }

    private void flush() {
        Map<String, Integer> batch;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return;
            }
            batch = new LinkedHashMap<>(pending);
            pending.clear();
        }

        settings.channel(ChannelRole.CONSOLE).ifPresent(channelId -> {
            TextChannel channel = jda.getTextChannelById(channelId);
            if (channel == null) {
                return;
            }
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, Integer> entry : batch.entrySet()) {
                String line = entry.getValue() > 1
                        ? entry.getKey() + "  (x" + entry.getValue() + ")"
                        : entry.getKey();
                if (body.length() + line.length() > MAX_BODY) {
                    body.append("…and more");
                    break;
                }
                body.append(line).append('\n');
            }
            // Fenced, so a stack trace fragment or a stray backtick cannot reformat the channel.
            // Failures here are dropped rather than logged: logging about a failure to log is how
            // a mirror turns into a loop.
            channel.sendMessage("```\n" + body + "```").queue(null, error -> { });
        });
    }
}
