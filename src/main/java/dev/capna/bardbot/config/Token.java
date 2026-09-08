package dev.capna.bardbot.config;

import java.util.List;
import java.util.function.Function;

/**
 * The bot token, read from the environment.
 *
 * <p>Kept apart from {@link BotConfig} so that object stays safe to log in full. Nothing here
 * should ever reach a log, an embed, or an error message.
 */
public record Token(String value) {

    public static final String VARIABLE = "DISCORD_TOKEN";

    /**
     * @param environment lookup, normally {@code System::getenv}
     * @throws ConfigException if it is missing, without quoting anything, since a token that is
     *                         present but wrong must not end up in a log
     */
    public static Token fromEnvironment(Function<String, String> environment) throws ConfigException {
        String value = environment.apply(VARIABLE);
        if (value == null || value.isBlank()) {
            throw new ConfigException(List.of(VARIABLE + " is not set"));
        }
        return new Token(value.trim());
    }

    /** Deliberately reveals nothing, so an accidental log line or crash dump cannot leak it. */
    @Override
    public String toString() {
        return "Token[<set>]";
    }
}
