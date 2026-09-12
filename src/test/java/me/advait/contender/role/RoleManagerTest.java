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
        when(plugin.getServer()).thenReturn(mock(org.bukkit.Server.class));
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
    @Test void eliminatingAnEnteredContestantPausesTheStageCancelsTheirDuelAndSetsSpectatorMode() {
        Contender plugin = plugin();
        RoleManager manager = new RoleManager(plugin);
        UUID id = UUID.randomUUID();
        var player = mock(org.bukkit.entity.Player.class);
        when(plugin.getServer().getPlayer(id)).thenReturn(player);
        var duel = mock(me.advait.contender.duel.Duel.class);
        when(duel.isInDuel(id)).thenReturn(true);
        when(plugin.getDuelManager().getDuel(id)).thenReturn(duel);
        when(plugin.getTournamentManager().isReserved(id)).thenReturn(true);
        manager.setRole(id, PlayerRole.SPECTATOR);
        assertEquals(PlayerRole.SPECTATOR, new RoleManager(plugin).getRole(id));
        var order = inOrder(plugin.getTournamentManager(), player);
        order.verify(plugin.getTournamentManager()).pause();
        order.verify(plugin.getTournamentManager()).cancelDuel(duel);
        order.verify(player).setGameMode(org.bukkit.GameMode.SPECTATOR);
        verify(plugin.getTournamentManager(), never()).cancel();
        verify(plugin.getNameTagManager()).refresh();
    }
    @Test void changingBackToContestantRestoresSurvivalAndSavedSpectatorsApplyOnLogin() {
        Contender plugin = plugin(); RoleManager manager = new RoleManager(plugin);
        UUID id = UUID.randomUUID(); var player = mock(org.bukkit.entity.Player.class);
        when(player.getUniqueId()).thenReturn(id); when(plugin.getServer().getPlayer(id)).thenReturn(player);
        manager.setRole(id, PlayerRole.SPECTATOR);
        new RoleManager(plugin).applySpectatorRole(player);
        verify(player, times(2)).setGameMode(org.bukkit.GameMode.SPECTATOR);
        manager.setRole(id, PlayerRole.CONTESTANT);
        verify(player).setGameMode(org.bukkit.GameMode.SURVIVAL);
        assertTrue(manager.isContestant(id));
    }
    @Test void legacySpectatorsMigrateOnceAndExplicitDirectorRolesSurvive() throws Exception {
        Contender plugin = plugin(); UUID eliminated = UUID.randomUUID(), director = UUID.randomUUID();
        var old = new YamlConfiguration(); old.set("spectators", java.util.List.of(eliminated.toString(), director.toString()));
        old.save(folder.resolve("spectators.yml").toFile());
        var roles = new YamlConfiguration(); roles.set("players." + director, "director");
        roles.save(folder.resolve("roles.yml").toFile());
        RoleManager manager = new RoleManager(plugin);
        assertEquals(PlayerRole.SPECTATOR, manager.getRole(eliminated));
        assertEquals(PlayerRole.DIRECTOR, manager.getRole(director));
        manager.setRole(eliminated, PlayerRole.CONTESTANT);
        assertTrue(new RoleManager(plugin).isContestant(eliminated), "The old file must never re-eliminate someone");
        assertTrue(folder.resolve("spectators.yml").toFile().exists());
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
