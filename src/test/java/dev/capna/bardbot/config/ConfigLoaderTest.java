package dev.capna.bardbot.config;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    private static Properties valid() {
        Properties properties = new Properties();
        properties.setProperty("discord.guild.id", "1172634772388970496");
        properties.setProperty("discord.roles.tribunal", "1223479239815467093");
        properties.setProperty("paths.data.dir", "data");
        properties.setProperty("renown.roll.at", "00:05");
        properties.setProperty("renown.roll.zone", "America/New_York");
        properties.setProperty("features.profiles.enabled", "true");
        properties.setProperty("features.virtue.enabled", "true");
        properties.setProperty("features.houses.enabled", "true");
        properties.setProperty("features.titles.enabled", "false");
        properties.setProperty("max.concurrent.operations", "3");
        properties.setProperty("command.cooldown.seconds", "3");
        return properties;
    }

    @Test
    void readsAValidFile() throws ConfigException {
        BotConfig config = ConfigLoader.from(valid());
        assertEquals("1172634772388970496", config.discord().guildId());
        assertEquals(1, config.discord().tribunalRoleIds().size());
        assertTrue(config.features().houses());
        assertTrue(!config.features().titles());
    }

    @Test
    void severalRolesMayShareTheTribunal() throws ConfigException {
        Properties properties = valid();
        properties.setProperty("discord.roles.tribunal",
                "1223479239815467093, 1223479239815467094");
        assertEquals(2, ConfigLoader.from(properties).discord().tribunalRoleIds().size());
    }

    /**
     * The usual mistake is pasting a channel's name or a role's mention. Caught at startup, that is
     * one clear message; missed, it is a silent "no such channel" weeks later.
     */
    @Test
    void anIdThatIsNotAnIdIsRefused() {
        Properties properties = valid();
        properties.setProperty("discord.guild.id", "#general");
        ConfigException thrown = assertThrows(ConfigException.class,
                () -> ConfigLoader.from(properties));
        assertTrue(thrown.problems().get(0).contains("discord.guild.id"));
    }

    /** With nobody privileged, half the bot is unreachable, which nobody would choose on purpose. */
    @Test
    void anEmptyTribunalIsRefused() {
        Properties properties = valid();
        properties.setProperty("discord.roles.tribunal", "");
        assertThrows(ConfigException.class, () -> ConfigLoader.from(properties));
    }

    /** Every problem at once, so setting the bot up is one pass rather than one restart per typo. */
    @Test
    void everyProblemIsReportedTogether() {
        Properties properties = valid();
        properties.remove("discord.guild.id");
        properties.setProperty("renown.roll.at", "half past three");
        properties.setProperty("max.concurrent.operations", "nine hundred");

        ConfigException thrown = assertThrows(ConfigException.class,
                () -> ConfigLoader.from(properties));
        assertEquals(3, thrown.problems().size());
    }

    @Test
    void anUnknownZoneIsRefused() {
        Properties properties = valid();
        properties.setProperty("renown.roll.zone", "Middle/Earth");
        assertThrows(ConfigException.class, () -> ConfigLoader.from(properties));
    }
}
