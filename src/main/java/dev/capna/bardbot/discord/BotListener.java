package dev.capna.bardbot.discord;

import dev.capna.bardbot.ops.SettingsStore;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Everything Discord sends, routed to the command that asked for it.
 *
 * <p>Components carry a {@code command:argument} custom ID, so a button or a menu on a days-old
 * message reaches the command that put it there without anything having to be remembered in
 * memory.
 *
 * <p>Work runs on an executor rather than on JDA's event thread. A command that blocks the event
 * thread stalls every other interaction in the guild, and a store that is being rewritten will
 * block.
 */
public final class BotListener extends ListenerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(BotListener.class);

    private final CommandRegistry registry;
    private final SettingsStore settings;
    private final ExecutorService executor;
    private final Duration cooldown;

    /**
     * When each user last ran something.
     *
     * <p>Only pruned by being overwritten, which is fine: an entry is two references per person who
     * has ever used the bot, in a guild whose whole point is that its members are countable.
     */
    private final Map<String, Instant> lastUsed = new ConcurrentHashMap<>();

    public BotListener(CommandRegistry registry, SettingsStore settings,
                       ExecutorService executor, Duration cooldown) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        dispatch(event, event.getName(), command -> command.handle(event));
    }

    @Override
    public void onMessageContextInteraction(@NotNull MessageContextInteractionEvent event) {
        dispatch(event, event.getName(), command -> command.messageContext(event));
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        dispatch(event, route(event.getComponentId()), command -> command.button(event));
    }

    @Override
    public void onStringSelectInteraction(@NotNull StringSelectInteractionEvent event) {
        dispatch(event, route(event.getComponentId()), command -> command.select(event));
    }

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        dispatch(event, route(event.getModalId()), command -> command.modal(event));
    }

    /**
     * Autocomplete is answered inline rather than on the executor.
     *
     * <p>Discord allows three seconds, will not accept a deferral, and a suggestion list that
     * arrives late is worse than none. Every implementation answers from memory for that reason.
     */
    @Override
    public void onCommandAutoCompleteInteraction(@NotNull CommandAutoCompleteInteractionEvent event) {
        registry.find(event.getName()).ifPresent(command -> {
            try {
                command.autocomplete(event);
            } catch (RuntimeException e) {
                LOG.warn("Autocomplete for /{} failed", event.getName(), e);
            }
        });
    }

    private void dispatch(IReplyCallback event, String name, Action action) {
        Optional<SlashCommand> found = registry.find(name);
        if (found.isEmpty()) {
            // Registered once, since removed, and still showing in somebody's client.
            Replies.problem(event, "That command is no longer available.");
            return;
        }
        SlashCommand command = found.get();

        if (!registry.isEnabled(command)) {
            Replies.problem(event, "That has been switched off for now.");
            return;
        }
        if (isTooSoon(event.getUser().getId())) {
            Replies.problem(event, "You are doing that too quickly. Give it a moment.");
            return;
        }

        executor.execute(() -> {
            try {
                action.run(command);
            } catch (Exception e) {
                Replies.failed(event, name, e);
            }
        });
    }

    /**
     * Spacing between one person's commands.
     *
     * <p>Not rate limiting in any serious sense; it exists so that a held-down key does not queue
     * fifty leaderboard renders. The clock is only advanced for a command that is actually allowed
     * through, so being refused never extends the wait.
     */
    private boolean isTooSoon(String userId) {
        if (cooldown.isZero()) {
            return false;
        }
        Instant now = Instant.now();
        Instant previous = lastUsed.get(userId);
        if (previous != null && Duration.between(previous, now).compareTo(cooldown) < 0) {
            return true;
        }
        lastUsed.put(userId, now);
        return false;
    }

    /** The part of a component ID before the colon, which is the command that owns it. */
    private static String route(String componentId) {
        int colon = componentId.indexOf(':');
        return colon < 0 ? componentId : componentId.substring(0, colon);
    }

    @FunctionalInterface
    private interface Action {
        void run(SlashCommand command) throws Exception;
    }
}
