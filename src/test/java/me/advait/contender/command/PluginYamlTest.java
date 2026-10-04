package me.advait.contender.command;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PluginYamlTest {
    @Test void everyCommandHasADescriptionAndUsage() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var reader = new InputStreamReader(PluginYamlTest.class.getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8)) {
            yaml.load(reader);
        }
        ConfigurationSection commands = yaml.getConfigurationSection("commands");
        assertNotNull(commands);
        assertFalse(commands.getKeys(false).isEmpty());
        for (String name : commands.getKeys(false)) {
            assertTrue(commands.isString(name + ".description"), name + " needs a description");
            assertTrue(commands.isString(name + ".usage"), name + " needs a usage line");
        }
    }
}
