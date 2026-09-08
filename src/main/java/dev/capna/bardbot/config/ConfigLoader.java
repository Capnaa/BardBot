package dev.capna.bardbot.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Reads and validates configuration, once, at startup.
 *
 * <p>Everything is checked up front and every problem is reported together. A value that is only
 * parsed the first time something needs it turns a typo into a failure days later, when the month
 * rolls, in a stack trace that does not mention configuration.
 *
 * <p>The token is not read here. It comes from the environment, so this object never holds a secret
 * and can be logged in full when diagnosing a deployment.
 */
public final class ConfigLoader {

    private final Properties properties;
    private final List<String> problems = new ArrayList<>();

    private ConfigLoader(Properties properties) {
        this.properties = properties;
    }

    /** @throws ConfigException if the file is missing, unreadable, or any value is unusable */
    public static BotConfig load(Path file) throws ConfigException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            throw new ConfigException(List.of("Cannot read " + file + ": " + e.getMessage()));
        }
        return new ConfigLoader(properties).build();
    }

    /** For tests and for validating a candidate configuration without touching the filesystem. */
    public static BotConfig from(Properties properties) throws ConfigException {
        return new ConfigLoader(properties).build();
    }

    private BotConfig build() throws ConfigException {
        BotConfig config = new BotConfig(
                new BotConfig.Discord(
                        id("discord.guild.id"),
                        ids("discord.roles.tribunal"),
                        optionalId("discord.channel.announcements"),
                        optionalId("discord.channel.renown"),
                        optionalId("discord.channel.console"),
                        optionalId("discord.dev.guild.id")),
                new BotConfig.Paths(
                        path("paths.data.dir", "data")),
                new BotConfig.Renown(
                        time("renown.roll.at"),
                        zone("renown.roll.zone")),
                new BotConfig.Limits(
                        integer("max.concurrent.operations", 1, 64),
                        Duration.ofSeconds(integer("command.cooldown.seconds", 0, 3600))),
                new BotConfig.Features(
                        flag("features.profiles.enabled"),
                        flag("features.virtue.enabled"),
                        flag("features.houses.enabled"),
                        flag("features.titles.enabled")));

        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }
        return config;
    }

    /**
     * A Discord snowflake.
     *
     * <p>Checked as digits rather than merely non-empty, because the usual mistake is pasting a
     * channel's name or a role's mention instead of its ID, and that survives to become a silent
     * "no such channel" at runtime.
     */
    private String id(String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) {
            problems.add(key + " is required");
            return "";
        }
        if (!value.matches("\\d{17,20}")) {
            problems.add(key + " must be a Discord ID (17 to 20 digits), got: " + value);
            return "";
        }
        return value;
    }

    private Optional<String> optionalId(String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        if (!value.matches("\\d{17,20}")) {
            problems.add(key + " must be a Discord ID (17 to 20 digits), got: " + value);
            return Optional.empty();
        }
        return Optional.of(value);
    }

    /**
     * One or more IDs, comma separated.
     *
     * <p>Empty is a problem rather than a permitted "nobody". Every administrative command is
     * gated on this list, so a blank one is a bot where houses cannot be created and virtue cannot
     * be awarded, which is not a state anyone would choose deliberately.
     */
    private List<String> ids(String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) {
            problems.add(key + " is required, or nobody can award virtue or administer houses");
            return List.of();
        }
        List<String> parsed = Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
        for (String part : parsed) {
            if (!part.matches("\\d{17,20}")) {
                problems.add(key + " must be Discord role IDs separated by commas, got: " + part);
            }
        }
        return parsed;
    }

    private Path path(String key, String fallback) {
        return Path.of(properties.getProperty(key, fallback).trim());
    }

    /** Bounded rather than merely numeric, because a plausible typo is worse than an obvious one. */
    private int integer(String key, int min, int max) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) {
            problems.add(key + " is required");
            return min;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                problems.add(key + " must be between " + min + " and " + max + ", got " + parsed);
                return min;
            }
            return parsed;
        } catch (NumberFormatException e) {
            problems.add(key + " must be a whole number, got: " + value);
            return min;
        }
    }

    private LocalTime time(String key) {
        String value = properties.getProperty(key, "").trim();
        try {
            return LocalTime.parse(value);
        } catch (DateTimeException e) {
            problems.add(key + " must be a 24 hour time such as 03:00, got: " + value);
            return LocalTime.MIDNIGHT;
        }
    }

    private ZoneId zone(String key) {
        String value = properties.getProperty(key, "").trim();
        try {
            return ZoneId.of(value);
        } catch (DateTimeException e) {
            problems.add(key + " must be a zone such as America/New_York, got: " + value);
            return ZoneId.of("UTC");
        }
    }

    private boolean flag(String key) {
        String value = properties.getProperty(key, "").trim();
        if (!value.equals("true") && !value.equals("false")) {
            problems.add(key + " must be true or false, got: " + value);
            return false;
        }
        return Boolean.parseBoolean(value);
    }
}
