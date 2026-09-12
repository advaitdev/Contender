package me.advait.contender.kit;

import me.advait.contender.Contender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KitHurtSettingsTest {
    @TempDir Path folder;
    @Test void oldKitsKeepNormalDamageAndBothTogglesSurviveSaving() throws Exception {
        var plugin = mock(Contender.class); when(plugin.getDataFolder()).thenReturn(folder.toFile());
        var old = new YamlConfiguration(); old.set("kits.sword.display-name", "Sword"); old.save(folder.resolve("kits.yml").toFile());
        var manager = new KitManager(plugin);
        assertFalse(manager.getKit("sword").isPvpHurt()); assertFalse(manager.getKit("sword").isPveHurt());
        var sumo = new Kit("sumo"); sumo.setPvpHurt(true); sumo.setPveHurt(true); manager.saveKit(sumo);
        manager.loadKits();
        assertTrue(manager.getKit("sumo").isPvpHurt()); assertTrue(manager.getKit("sumo").isPveHurt());
        assertFalse(manager.getKit("sword").keepsHungerFull());
    }
}
