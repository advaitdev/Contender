package me.advait.contender.role;

import me.advait.contender.Contender;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.nametag.NameTagManager;
import me.advait.contender.tournament.TournamentManager;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoleManagerTest {
    @TempDir Path folder;
    private Contender plugin() {
        Contender plugin = mock(Contender.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getDuelManager()).thenReturn(mock(DuelManager.class));
        when(plugin.getTournamentManager()).thenReturn(mock(TournamentManager.class));
        when(plugin.getNameTagManager()).thenReturn(mock(NameTagManager.class));
        return plugin;
    }
    @Test void defaultsToContestantAndRemembersEachAssignedRoleAcrossRestarts() {
        Contender plugin = plugin();
        RoleManager manager = new RoleManager(plugin);
        UUID director = UUID.randomUUID(), spectator = UUID.randomUUID();
        assertTrue(manager.isContestant(director));
        manager.setRole(director, PlayerRole.DIRECTOR);
        manager.setRole(spectator, PlayerRole.SPECTATOR);
        RoleManager loaded = new RoleManager(plugin);
        assertEquals(PlayerRole.DIRECTOR, loaded.getRole(director));
        assertEquals(PlayerRole.SPECTATOR, loaded.getRole(spectator));
        loaded.setRole(spectator, PlayerRole.CONTESTANT);
        assertTrue(new RoleManager(plugin).isContestant(spectator));
    }
    @Test void cannotChangeAnEnteredContestantIntoANonplayingRole() {
        Contender plugin = plugin();
        RoleManager manager = new RoleManager(plugin);
        UUID player = UUID.randomUUID();
        when(plugin.getDuelManager().isPlaying(player)).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> manager.setRole(player, PlayerRole.DIRECTOR));
        assertTrue(manager.isContestant(player));
        when(plugin.getDuelManager().isPlaying(player)).thenReturn(false);
        when(plugin.getTournamentManager().isReserved(player)).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> manager.setRole(player, PlayerRole.SPECTATOR));
        assertTrue(manager.isContestant(player));
        verify(plugin.getNameTagManager(), never()).refresh();
    }
    @Test void stylesKeepPlainPrefixesAndIndependentColorsThroughYaml() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        new RoleStyle("[Camera]", NamedTextColor.LIGHT_PURPLE).write(config, PlayerRole.DIRECTOR);
        new RoleStyle("", NamedTextColor.AQUA).write(config, PlayerRole.SPECTATOR);
        YamlConfiguration loaded = new YamlConfiguration();
        loaded.loadFromString(config.saveToString());
        assertEquals(new RoleStyle("[Camera]", NamedTextColor.LIGHT_PURPLE), RoleStyle.read(loaded, PlayerRole.DIRECTOR));
        assertEquals(new RoleStyle("", NamedTextColor.AQUA), RoleStyle.read(loaded, PlayerRole.SPECTATOR));
        assertThrows(IllegalArgumentException.class, () -> new RoleStyle("two\nlines", NamedTextColor.WHITE));
        assertThrows(IllegalArgumentException.class, () -> RoleStyle.parseColor("unknown"));
    }
}
