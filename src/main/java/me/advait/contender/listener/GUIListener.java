package me.advait.contender.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.advait.contender.Contender;
import me.advait.contender.SpectatorManager;
import me.advait.contender.duel.DuelManager;
import me.advait.contender.duel.DuelSetup;
import me.advait.contender.duel.DuelTeam;
import me.advait.contender.gui.*;
import me.advait.contender.kit.Kit;
import me.advait.contender.kit.KitManager;
import me.advait.contender.map.ArenaMap;
import me.advait.contender.map.MapManager;
import me.advait.contender.util.MessageUtil;
import me.advait.contender.vote.VoteManager;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public class GUIListener implements Listener {

    private final Contender plugin;
    private final DuelManager duelManager;
    private final KitManager kitManager;
    private final MapManager mapManager;
    private final VoteManager voteManager;
    private final SpectatorManager spectatorManager;

    public GUIListener(Contender plugin, DuelManager duelManager,
                       KitManager kitManager, MapManager mapManager, VoteManager voteManager,
                       SpectatorManager spectatorManager) {
        this.plugin = plugin;
        this.duelManager = duelManager;
        this.kitManager = kitManager;
        this.mapManager = mapManager;
        this.voteManager = voteManager;
        this.spectatorManager = spectatorManager;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof GUIHolder holder)) return;

        switch (holder.getType()) {
            case DUEL_SETUP -> handleDuelSetup(event, player, holder);
            case KIT_SELECT -> handleKitSelect(event, player, holder);
            case KIT_EDITOR -> handleKitEditor(event, player, holder);
            case MAP_SELECT -> handleMapSelect(event, player, holder);
            case TEAM_SELECT -> handleTeamSelect(event, player, holder);
            case VOTE_GUI -> {} // handled by VoteListener
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (!(event.getInventory().getHolder() instanceof GUIHolder holder)) return;

        if (holder.getType() == GUIType.KIT_EDITOR) {
            for (int slot : event.getRawSlots()) {
                // Block drags onto control slots and armor/offhand slots
                if ((slot >= 36 && slot < 54)) {
                    event.setCancelled(true);
                    return;
                }
            }
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Consumer<String> handler = plugin.pollChatInput(event.getPlayer().getUniqueId());
        if (handler != null) {
            event.setCancelled(true);
            String message = PlainTextComponentSerializer.plainText().serialize(event.message());
            Bukkit.getScheduler().runTask(plugin, () -> handler.accept(message));
        }
    }

    // ── Duel Setup ──────────────────────────────────────────────────────

    private void handleDuelSetup(InventoryClickEvent event, Player player, GUIHolder holder) {
        event.setCancelled(true);
        DuelSetup setup = holder.getData("setup");
        if (setup == null) return;

        switch (event.getSlot()) {
            case DuelSetupGUI.KIT_SLOT -> {
                MessageUtil.playClick(player);
                KitSelectGUI.open(player, kitManager, setup);
            }
            case DuelSetupGUI.MAP_SLOT -> {
                MessageUtil.playClick(player);
                MapSelectGUI.open(player, mapManager, setup);
            }
            case DuelSetupGUI.ROUNDS_SLOT -> {
                if (event.isLeftClick()) setup.setRounds(setup.getRounds() + 1);
                else if (event.isRightClick()) setup.setRounds(setup.getRounds() - 1);
                MessageUtil.playClick(player);
                DuelSetupGUI.open(player, setup);
            }
            case DuelSetupGUI.DELAY_SLOT -> {
                if (event.isLeftClick()) setup.setPreRoundDelay(setup.getPreRoundDelay() + 5);
                else if (event.isRightClick()) setup.setPreRoundDelay(setup.getPreRoundDelay() - 5);
                MessageUtil.playClick(player);
                DuelSetupGUI.open(player, setup);
            }
            case DuelSetupGUI.TEAM1_SLOT -> {
                MessageUtil.playClick(player);
                TeamSelectGUI.open(player, setup, 1, spectatorManager);
            }
            case DuelSetupGUI.TEAM2_SLOT -> {
                MessageUtil.playClick(player);
                TeamSelectGUI.open(player, setup, 2, spectatorManager);
            }
            case DuelSetupGUI.CANCEL_SLOT -> {
                duelManager.removeSetup(player.getUniqueId());
                player.closeInventory();
                MessageUtil.sendActionBar(player, "<color:" + MessageUtil.ERROR + ">Duel setup cancelled</color>");
            }
            case DuelSetupGUI.START_SLOT -> {
                if (voteManager.isVoteActive()) {
                    MessageUtil.sendActionBar(player,
                            "<color:" + MessageUtil.ERROR + ">Cannot start a duel during a vote!</color>");
                    return;
                }
                if (!setup.isValid()) {
                    MessageUtil.sendActionBar(player, "<color:" + MessageUtil.ERROR + ">Complete the setup first!</color>");
                    MessageUtil.playClick(player);
                    return;
                }
                if (duelManager.isMapInUse(setup.getSelectedMap().getId())) {
                    MessageUtil.sendActionBar(player,
                            "<color:" + MessageUtil.ERROR + ">That arena is already in use!</color>");
                    MessageUtil.playClick(player);
                    return;
                }
                player.closeInventory();
                duelManager.startDuel(setup);
                MessageUtil.sendActionBar(player, "<color:" + MessageUtil.PRIMARY + ">Duel started!</color>");
                MessageUtil.playSuccess(player);
            }
        }
    }

    // ── Kit Select ──────────────────────────────────────────────────────

    private void handleKitSelect(InventoryClickEvent event, Player player, GUIHolder holder) {
        event.setCancelled(true);
        DuelSetup setup = holder.getData("setup");
        int slot = event.getSlot();

        if (slot == KitSelectGUI.BACK_SLOT) {
            MessageUtil.playClick(player);
            DuelSetupGUI.open(player, setup);
            return;
        }

        if (slot == KitSelectGUI.CREATE_KIT_SLOT) {
            player.closeInventory();
            MessageUtil.sendActionBar(player,
                    "<color:" + MessageUtil.SECONDARY + ">Type the new kit name in chat</color>");
            plugin.awaitChatInput(player.getUniqueId(), name -> {
                String kitId = name.toLowerCase().replace(" ", "_");
                if (kitManager.kitExists(kitId)) {
                    MessageUtil.sendActionBar(player,
                            "<color:" + MessageUtil.ERROR + ">A kit with that name already exists!</color>");
                    KitSelectGUI.open(player, kitManager, setup);
                    return;
                }
                Kit kit = new Kit(kitId);
                kit.setDisplayName(name);
                KitEditorGUI.open(player, kit, true);
            });
            return;
        }

        if (slot < 45) {
            List<Kit> kitList = new ArrayList<>(kitManager.getKits());
            if (slot < kitList.size()) {
                Kit kit = kitList.get(slot);
                if (event.isShiftClick()) {
                    MessageUtil.playClick(player);
                    KitEditorGUI.open(player, kit, false);
                } else {
                    setup.setSelectedKit(kit);
                    MessageUtil.playClick(player);
                    DuelSetupGUI.open(player, setup);
                }
            }
        }
    }

    // ── Kit Editor ──────────────────────────────────────────────────────

    private void handleKitEditor(InventoryClickEvent event, Player player, GUIHolder holder) {
        int rawSlot = event.getRawSlot();
        Kit kit = holder.getData("kit");
        boolean isNew = holder.getData("isNew");
        if (kit == null) return;

        // Main inventory item slots — allow vanilla behavior
        if (rawSlot >= 0 && rawSlot < 36) {
            return;
        }

        // Armor and offhand slots — custom handling
        if (rawSlot >= 36 && rawSlot <= 40) {
            event.setCancelled(true);
            if (event.isRightClick()) {
                // Reset to placeholder
                event.setCurrentItem(KitEditorGUI.getSlotPlaceholder(rawSlot));
            } else if (event.isLeftClick()) {
                ItemStack cursor = event.getCursor();
                if (cursor != null && cursor.getType() != Material.AIR) {
                    // Place the cursor item into the slot
                    event.setCurrentItem(cursor.clone());
                    event.getView().setCursor(new ItemStack(Material.AIR));
                }
                // Left-click with empty cursor — do nothing (don't let placeholder be picked up)
            }
            return;
        }

        // Player's own inventory
        if (rawSlot >= 54) {
            return;
        }

        event.setCancelled(true);

        switch (rawSlot) {
            case KitEditorGUI.BLOCK_PLACE_SLOT -> {
                saveKitFromInventory(event.getInventory(), kit);
                kit.setAllowBlockPlace(!kit.isAllowBlockPlace());
                MessageUtil.playClick(player);
                KitEditorGUI.open(player, kit, isNew);
            }
            case KitEditorGUI.BLOCK_BREAK_SLOT -> {
                saveKitFromInventory(event.getInventory(), kit);
                kit.setAllowBlockBreak(!kit.isAllowBlockBreak());
                MessageUtil.playClick(player);
                KitEditorGUI.open(player, kit, isNew);
            }
            case KitEditorGUI.NATURAL_REGEN_SLOT -> {
                saveKitFromInventory(event.getInventory(), kit);
                kit.setNaturalRegen(!kit.isNaturalRegen());
                MessageUtil.playClick(player);
                KitEditorGUI.open(player, kit, isNew);
            }
            case KitEditorGUI.SAVE_SLOT -> {
                saveKitFromInventory(event.getInventory(), kit);
                kitManager.saveKit(kit);
                player.closeInventory();
                MessageUtil.sendActionBar(player,
                        "<color:" + MessageUtil.PRIMARY + ">Kit '" + kit.getDisplayName() + "' saved!</color>");
                MessageUtil.playSuccess(player);
            }
            case KitEditorGUI.CANCEL_SLOT -> {
                player.closeInventory();
                MessageUtil.sendActionBar(player,
                        "<color:" + MessageUtil.ERROR + ">Kit editing cancelled</color>");
            }
            case KitEditorGUI.DELETE_SLOT -> {
                if (event.isShiftClick() && !isNew) {
                    String name = kit.getDisplayName();
                    kitManager.deleteKit(kit.getId());
                    player.closeInventory();
                    MessageUtil.sendActionBar(player,
                            "<color:" + MessageUtil.ERROR + ">Kit '" + name + "' deleted!</color>");
                }
            }
            case KitEditorGUI.ICON_SLOT -> {
                ItemStack cursor = event.getCursor();
                if (cursor != null && cursor.getType() != Material.AIR) {
                    saveKitFromInventory(event.getInventory(), kit);
                    kit.setIcon(cursor.getType());
                    MessageUtil.playClick(player);
                    KitEditorGUI.open(player, kit, isNew);
                }
            }
        }
    }

    private void saveKitFromInventory(Inventory inv, Kit kit) {
        ItemStack[] contents = new ItemStack[36];
        for (int i = 0; i < 36; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                contents[i] = item.clone();
            }
        }
        kit.setContents(contents);

        ItemStack[] armor = new ItemStack[4];
        ItemStack boots = inv.getItem(KitEditorGUI.BOOTS_SLOT);
        ItemStack leggings = inv.getItem(KitEditorGUI.LEGGINGS_SLOT);
        ItemStack chestplate = inv.getItem(KitEditorGUI.CHESTPLATE_SLOT);
        ItemStack helmet = inv.getItem(KitEditorGUI.HELMET_SLOT);

        if (boots != null && boots.getType() != Material.AIR && boots.getType() != Material.ARMOR_STAND)
            armor[0] = boots.clone();
        if (leggings != null && leggings.getType() != Material.AIR && leggings.getType() != Material.ARMOR_STAND)
            armor[1] = leggings.clone();
        if (chestplate != null && chestplate.getType() != Material.AIR && chestplate.getType() != Material.ARMOR_STAND)
            armor[2] = chestplate.clone();
        if (helmet != null && helmet.getType() != Material.AIR && helmet.getType() != Material.ARMOR_STAND)
            armor[3] = helmet.clone();
        kit.setArmor(armor);

        ItemStack offhand = inv.getItem(KitEditorGUI.OFFHAND_SLOT);
        if (offhand != null && offhand.getType() != Material.AIR && offhand.getType() != Material.RED_STAINED_GLASS_PANE) {
            kit.setOffhand(offhand.clone());
        } else {
            kit.setOffhand(null);
        }
    }

    // ── Map Select ──────────────────────────────────────────────────────

    private void handleMapSelect(InventoryClickEvent event, Player player, GUIHolder holder) {
        event.setCancelled(true);
        DuelSetup setup = holder.getData("setup");
        int slot = event.getSlot();

        if (slot == MapSelectGUI.BACK_SLOT) {
            MessageUtil.playClick(player);
            DuelSetupGUI.open(player, setup);
            return;
        }

        if (slot < 45) {
            List<ArenaMap> mapList = new ArrayList<>(mapManager.getMaps());
            if (slot < mapList.size()) {
                setup.setSelectedMap(mapList.get(slot));
                MessageUtil.playClick(player);
                DuelSetupGUI.open(player, setup);
            }
        }
    }

    // ── Team Select ─────────────────────────────────────────────────────

    private void handleTeamSelect(InventoryClickEvent event, Player player, GUIHolder holder) {
        event.setCancelled(true);
        DuelSetup setup = holder.getData("setup");
        int teamNumber = holder.<Integer>getData("teamNumber");
        int slot = event.getSlot();

        if (slot == TeamSelectGUI.BACK_SLOT) {
            MessageUtil.playClick(player);
            DuelSetupGUI.open(player, setup);
            return;
        }

        if (slot < 45) {
            List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
            if (slot < onlinePlayers.size()) {
                Player target = onlinePlayers.get(slot);
                UUID targetUuid = target.getUniqueId();

                if (spectatorManager.isEventSpectator(targetUuid)) {
                    MessageUtil.sendActionBar(player,
                            "<color:" + MessageUtil.ERROR + ">That player is an event spectator and cannot participate!</color>");
                    return;
                }

                DuelTeam currentTeam = teamNumber == 1 ? setup.getTeam1() : setup.getTeam2();
                DuelTeam otherTeam = teamNumber == 1 ? setup.getTeam2() : setup.getTeam1();

                if (otherTeam.hasPlayer(targetUuid)) {
                    MessageUtil.sendActionBar(player,
                            "<color:" + MessageUtil.ERROR + ">That player is on the other team!</color>");
                    return;
                }

                if (currentTeam.hasPlayer(targetUuid)) {
                    currentTeam.removePlayer(targetUuid);
                } else {
                    currentTeam.addPlayer(targetUuid);
                }

                MessageUtil.playClick(player);
                TeamSelectGUI.open(player, setup, teamNumber, spectatorManager);
            }
        }
    }
}
