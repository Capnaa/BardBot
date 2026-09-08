package dev.capna.bardbot.discord;

import dev.capna.bardbot.ops.SettingsStore;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The commands the bot offers, and what Discord is told about them.
 *
 * <p>Registration is data rather than a hardcoded list. A disabled feature's commands are never
 * registered, so they do not appear in Discord at all, which is a better answer than appearing and
 * then refusing.
 */
public final class CommandRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(CommandRegistry.class);

    private final Map<String, SlashCommand> byName = new LinkedHashMap<>();
    private final SettingsStore settings;

    public CommandRegistry(SettingsStore settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public CommandRegistry add(SlashCommand command) {
        byName.put(command.name(), command);
        return this;
    }

    public Optional<SlashCommand> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** @return definitions for every command whose feature is currently on */
    public List<CommandData> enabledDefinitions() {
        List<CommandData> definitions = byName.values().stream()
                .filter(this::isEnabled)
                .map(SlashCommand::definition)
                .toList();
        LOG.info("Registering {} of {} commands", definitions.size(), byName.size());
        return definitions;
    }

    /**
     * Checked again at dispatch, not only at registration. A feature switched off while the bot is
     * running leaves its commands in Discord until the next registration, and they must stop
     * working immediately rather than at some later restart.
     */
    public boolean isEnabled(SlashCommand command) {
        return command.feature().map(settings::isEnabled).orElse(true);
    }
}
