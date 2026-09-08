package dev.capna.bardbot;

import dev.capna.bardbot.config.BotConfig;
import dev.capna.bardbot.config.Token;
import dev.capna.bardbot.discord.BotListener;
import dev.capna.bardbot.discord.CommandRegistry;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final CommandRegistry registry;

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
        this.registry = new CommandRegistry(settings);
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

        LOG.info("BardBot is up in {}", guild.getName());
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
        return new Settings(false, enabled);
    }

    @Override
    public void close() {
        // Stop accepting work before disconnecting, so nothing is halfway through a store write
        // when the connection goes.
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
