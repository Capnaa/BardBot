package dev.capna.bardbot;

import dev.capna.bardbot.config.BotConfig;
import dev.capna.bardbot.config.Token;
import dev.capna.bardbot.discord.BotListener;
import dev.capna.bardbot.discord.CommandRegistry;
import dev.capna.bardbot.discord.LiveBoards;
import dev.capna.bardbot.discord.Tribunal;
import dev.capna.bardbot.discord.commands.AdminCommand;
import dev.capna.bardbot.discord.commands.AwardCommand;
import dev.capna.bardbot.discord.commands.AwardVirtueCommand;
import dev.capna.bardbot.discord.commands.HelpCommand;
import dev.capna.bardbot.discord.commands.HouseCommand;
import dev.capna.bardbot.houses.MonthRoll;
import dev.capna.bardbot.houses.Renown;
import dev.capna.bardbot.discord.commands.ProfileCommand;
import dev.capna.bardbot.discord.commands.TitleCommand;
import dev.capna.bardbot.discord.commands.VirtueCommand;
import dev.capna.bardbot.titles.Titles;
import dev.capna.bardbot.virtue.Awarding;
import dev.capna.bardbot.virtue.Unlocks;
import dev.capna.bardbot.ops.ConsoleMirror;
import dev.capna.bardbot.ops.Feature;
import dev.capna.bardbot.ops.Settings;
import dev.capna.bardbot.ops.SettingsStore;
import dev.capna.bardbot.store.AwardLog;
import dev.capna.bardbot.store.CatalogueStore;
import dev.capna.bardbot.store.HouseStore;
import dev.capna.bardbot.store.ProfileStore;
import dev.capna.bardbot.store.RenownStore;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Objects;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The running bot: everything constructed once, in one place, and shut down in the reverse order.
 *
 * <p>The bot serves a single guild. Commands are therefore registered to that guild rather than
 * globally, which is not only correct but faster: a guild command appears the moment it is
 * registered, where a global one can take an hour to propagate.
 */
public final class Application implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Application.class);

    private final BotConfig config;
    private final Token token;

    private final SettingsStore settings;
    private final ProfileStore profiles;
    private final HouseStore houses;
    private final AwardLog awards;
    private final CatalogueStore catalogue;
    private final RenownStore renown;

    private final ExecutorService commands;
    private final ScheduledExecutorService schedule;
    private final CommandRegistry registry;
    private final MonthRoll monthRoll;

    private JDA jda;

    public Application(BotConfig config, Token token) {
        this.config = Objects.requireNonNull(config, "config");
        this.token = Objects.requireNonNull(token, "token");

        this.settings = new SettingsStore(config.paths().settings(), startupDefaults(config));
        this.profiles = new ProfileStore(config.paths().profiles());
        this.houses = new HouseStore(config.paths().houses());
        this.awards = new AwardLog(config.paths().awards());
        this.catalogue = new CatalogueStore(config.paths().titles());
        this.renown = new RenownStore(config.paths().renown());

        this.commands = Executors.newFixedThreadPool(config.limits().maxConcurrentOperations(),
                runnable -> {
                    Thread thread = new Thread(runnable, "command");
                    // Daemon, so a command still running cannot keep a stopped process alive.
                    thread.setDaemon(true);
                    return thread;
                });
        Tribunal tribunal = new Tribunal(config.discord().tribunalRoleIds());
        Titles titleHoldings = new Titles(profiles, houses, awards, catalogue);
        Renown houseRenown = new Renown(awards, houses, config.renown().zone());
        Awarding awarding = new Awarding(awards, houses, catalogue);
        Unlocks unlockAnnouncer = new Unlocks(settings);

        this.monthRoll = new MonthRoll(houseRenown, renown, awards, settings,
                config.renown().zone());
        this.schedule = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "schedule");
            thread.setDaemon(true);
            return thread;
        });

        LiveBoards boards = new LiveBoards(settings, awards, profiles, houseRenown, schedule);

        this.registry = new CommandRegistry(settings)
                .add(new ProfileCommand(profiles, houses, awards, titleHoldings))
                .add(new AwardCommand(tribunal, awarding, unlockAnnouncer, profiles, settings, boards))
                .add(new AwardVirtueCommand(tribunal, awarding, unlockAnnouncer, profiles, boards))
                .add(new VirtueCommand(awards, catalogue, profiles, config.renown().zone()))
                .add(new TitleCommand(titleHoldings, profiles))
                .add(new AdminCommand(tribunal, settings, catalogue, houses, profiles, boards))
                .add(new HouseCommand(houses, profiles, houseRenown, renown))
                .add(new HelpCommand());
    }

    public void start() throws InterruptedException {
        jda = JDABuilder.createLight(token.value(), EnumSet.of(GatewayIntent.GUILD_MEMBERS))
                .addEventListeners(new BotListener(registry, settings, commands,
                        config.limits().commandCooldown()))
                .build()
                .awaitReady();

        Guild guild = jda.getGuildById(config.discord().guildId());
        if (guild == null) {
            // Every command is registered to this guild, so there is nothing useful to do without
            // it. Said plainly rather than left as an empty command list nobody can explain.
            throw new IllegalStateException("The bot is not in guild " + config.discord().guildId()
                    + ". Invite it there, then start again.");
        }

        guild.updateCommands().addCommands(registry.enabledDefinitions()).queue(
                registered -> LOG.info("Registered {} command(s) to {}", registered.size(), guild.getName()),
                error -> LOG.error("Could not register commands", error));

        attachConsoleMirror();

        // Checked at startup as well as on the schedule, so a bot that was down when a month
        // ended settles it as soon as it is back rather than waiting for the next one.
        monthRoll.settleDue(jda);
        schedule.scheduleAtFixedRate(() -> {
            try {
                monthRoll.settleDue(jda);
            } catch (RuntimeException e) {
                LOG.error("The monthly roll failed; it will be tried again", e);
            }
        }, untilNextRoll().toSeconds(), Duration.ofDays(1).toSeconds(), TimeUnit.SECONDS);

        LOG.info("BardBot is up in {}", guild.getName());
    }

    /**
     * Starts mirroring warnings and errors into Discord.
     *
     * <p>Attached after the connection is up, since it has nowhere to send anything before then,
     * and left attached whether or not a console channel is set: the channel is read at send time,
     * so setting one takes effect immediately rather than at the next restart.
     */
    private void attachConsoleMirror() {
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ConsoleMirror mirror = new ConsoleMirror(jda, settings, schedule);
        mirror.setContext(root.getLoggerContext());
        mirror.start();
        root.addAppender(mirror);
    }

    /**
     * How long until the next time of day the roll is due.
     *
     * <p>Daily rather than monthly, because a daily task that usually finds nothing to do is far
     * harder to get wrong than one that has to fire on exactly the right date, and the cost of
     * being wrong there is a month that never settles.
     */
    private Duration untilNextRoll() {
        ZonedDateTime now = ZonedDateTime.now(config.renown().zone());
        ZonedDateTime next = now.with(config.renown().rollAt());
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next);
    }

    /**
     * Startup defaults for the settings store, used only the first time the bot runs.
     *
     * <p>After that the stored settings win, since they hold what the tribunal last chose and a
     * redeploy must not silently undo it.
     */
    private static Settings startupDefaults(BotConfig config) {
        EnumSet<Feature> enabled = EnumSet.noneOf(Feature.class);
        if (config.features().profiles()) {
            enabled.add(Feature.PROFILES);
        }
        if (config.features().virtue()) {
            enabled.add(Feature.VIRTUE);
        }
        if (config.features().houses()) {
            enabled.add(Feature.HOUSES);
        }
        if (config.features().titles()) {
            enabled.add(Feature.TITLES);
        }
        return new Settings(enabled, java.util.Map.of(), java.util.Map.of());
    }

    @Override
    public void close() {
        // Stop accepting work before disconnecting, so nothing is halfway through a store write
        // when the connection goes.
        schedule.shutdownNow();
        commands.shutdown();
        try {
            if (!commands.awaitTermination(10, TimeUnit.SECONDS)) {
                LOG.warn("Commands still running at shutdown; stopping anyway");
                commands.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            commands.shutdownNow();
        }
        if (jda != null) {
            jda.shutdown();
        }
        LOG.info("BardBot is down");
    }
}
