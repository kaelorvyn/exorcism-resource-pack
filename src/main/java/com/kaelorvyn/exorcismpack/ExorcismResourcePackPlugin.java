package com.kaelorvyn.exorcismpack;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ExorcismResourcePackPlugin extends JavaPlugin implements Listener {
    private static final UUID PACK_ID = UUID.fromString("bd94fa4e-c6b9-4d2c-8efd-cf8c5026307e");
    private static final String PACK_URL = "http://43.226.36.115:3213/exorcism-pack.zip";
    private static final String PACK_SHA1 = "8920400E02BFC810E8A1ACDC98C6147EFC8057A7";
    private final Map<UUID, Long> loadDeadlines = new ConcurrentHashMap<>();
    private TeamRoomManager teamRoomManager;
    private CollectionRoomManager collectionRoomManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (isMainMode()) {
            teamRoomManager = new TeamRoomManager(this);
            collectionRoomManager = new CollectionRoomManager(this);
            Bukkit.getPluginManager().registerEvents(collectionRoomManager, this);
            getCommand("kteam").setExecutor(teamRoomManager);
            getCommand("kteam").setTabCompleter(teamRoomManager);
            Bukkit.getScheduler().runTaskTimer(this, this::checkPackTimeouts, 20L, 20L);
            Bukkit.getScheduler().runTaskTimer(this, this::ensureStartDisplays, 40L, 100L);
            Bukkit.getScheduler().runTaskLater(this, this::ensureStartDisplays, 20L);
            getLogger().info("Exorcism resource pack enforcement enabled");
        } else if (isRoomMode()) {
            Bukkit.getScheduler().runTaskTimer(this, this::ensureStartDisplays, 40L, 100L);
            getLogger().info("Exorcism room mode enabled");
        } else {
            getLogger().info("Hub resource pack cleanup enabled");
            Bukkit.getScheduler().runTaskTimer(this, (Runnable) this::cleanDisabledLobbyItems, 20L, 20L);
        }
    }

    @Override
    public void onDisable() {
        if (teamRoomManager != null) {
            teamRoomManager.shutdown();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isMainMode()) {
            clearStaleGameTags(player);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (teamRoomManager == null || !teamRoomManager.rejoinActiveRoom(player)) {
                    teleportToStartRoom(player);
                }
            }, 1L);
            Bukkit.getScheduler().runTaskLater(this, this::ensureStartDisplays, 10L);
            if (!isProxyGated()) {
                Bukkit.getScheduler().runTaskLater(this, () -> sendRequiredPack(player), 10L);
            }
        } else if (isRoomMode()) {
            Bukkit.getScheduler().runTaskLater(this, () -> teleportToStartRoom(player), 1L);
        } else {
            // A proxy server switch can leave the previous server pack active.
            Bukkit.getScheduler().runTaskLater(this, () -> clearServerPacks(player), 5L);
            Bukkit.getScheduler().runTaskLater(this, () -> clearServerPacks(player), 20L);
            Bukkit.getScheduler().runTaskLater(this, () -> clearServerPacks(player), 40L);
            Bukkit.getScheduler().runTaskLater(this, () -> cleanDisabledLobbyItems(player), 2L);
            Bukkit.getScheduler().runTaskLater(this, () -> cleanDisabledLobbyItems(player), 10L);
        }
    }

    private void clearStaleGameTags(Player player) {
        for (String tag : new String[]{
                "Main_Player",
                "kit_1", "kit_2", "kit_3", "kit_4",
                "kit_5", "kit_6", "kit_7", "kit_8",
                "exo_r01", "exo_r02", "exo_r03", "exo_r04",
                "exo_col"
        }) {
            player.removeScoreboardTag(tag);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        loadDeadlines.remove(event.getPlayer().getUniqueId());
        if (isMainMode() && teamRoomManager != null) {
            teamRoomManager.handleQuit(event.getPlayer());
        }
        if (collectionRoomManager != null) {
            collectionRoomManager.handleQuit(event.getPlayer());
        }
        if (isRoomMode()) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (Bukkit.getOnlinePlayers().isEmpty()) {
                    Bukkit.shutdown();
                }
            }, 100L);
        }
    }

    @EventHandler
    public void onStartButton(PlayerInteractEntityEvent event) {
        if (!isMainMode() || teamRoomManager == null) {
            return;
        }
        Player player = event.getPlayer();
        if (teamRoomManager.isRoomWorld(player.getWorld())) {
            return;
        }
        Entity clicked = event.getRightClicked();
        if (clicked.getScoreboardTags().contains("team_lobby_start")) {
            event.setCancelled(true);
            teamRoomManager.startFromTeamLobby(player);
            return;
        }
        if (clicked.getScoreboardTags().contains("settings_solo")) {
            event.setCancelled(true);
            teamRoomManager.startSolo(player);
            return;
        }
        if (clicked.getScoreboardTags().contains("settings_multi")) {
            event.setCancelled(true);
            teamRoomManager.startMulti(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (isHubMode() && isDisabledLobbyItem(event.getItem())) {
            event.setCancelled(true);
            return;
        }
        if (isExorcismMode() && loadDeadlines.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar("材质包加载完成后才能开始游戏");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (teamRoomManager != null) {
            teamRoomManager.tagEntity(event.getEntity());
        }
    }

    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (isExorcismMode() && loadDeadlines.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar("材质包加载完成后才能开始游戏");
        }
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        if (!PACK_ID.equals(event.getID())) {
            return;
        }

        Player player = event.getPlayer();
        if (!isExorcismMode()) {
            return;
        }

        switch (event.getStatus()) {
            case ACCEPTED, DOWNLOADED -> getLogger().info(player.getName() + " resource pack status: " + event.getStatus());
            case SUCCESSFULLY_LOADED -> {
                loadDeadlines.remove(player.getUniqueId());
                getLogger().info(player.getName() + " loaded the Exorcism resource pack");
            }
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> {
                getLogger().warning(player.getName() + " failed to load the Exorcism resource pack: " + event.getStatus());
                player.kick(Component.text("进入驱魔传必须加载材质包。请接受下载并等待加载完成。"));
            }
            case DISCARDED -> Bukkit.getScheduler().runTaskLater(this, () -> sendRequiredPack(player), 5L);
            default -> getLogger().info(player.getName() + " resource pack status: " + event.getStatus());
        }
    }

    private void sendRequiredPack(Player player) {
        if (!isExorcismMode() || !player.isOnline()) {
            return;
        }
        teleportToStartRoom(player);
        loadDeadlines.put(player.getUniqueId(), System.currentTimeMillis() + 60_000L);
        player.setResourcePack(
                PACK_ID,
                PACK_URL,
                PACK_SHA1,
                Component.text("进入驱魔传必须加载材质包"),
                true
        );
        getLogger().info("Sent required Exorcism resource pack to " + player.getName());
    }

    private void teleportToStartRoom(Player player) {
        if (!isExorcismMode() || !player.isOnline()) {
            return;
        }
        var world = Bukkit.getWorld("world");
        if (world == null) {
            getLogger().warning("Cannot teleport " + player.getName() + ": world is not loaded");
            return;
        }
        player.teleport(new org.bukkit.Location(world, -9.5, -59.0, 118.5, 0.0f, 0.0f));
    }

    private void clearServerPacks(Player player) {
        if (!isMainMode() && !isRoomMode() && player.isOnline()) {
            player.removeResourcePacks();
            getLogger().info("Cleared server resource packs for " + player.getName());
        }
    }

    private void checkPackTimeouts() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Long deadline = loadDeadlines.get(player.getUniqueId());
            if (deadline == null) {
                continue;
            }
            if (deadline < now) {
                loadDeadlines.remove(player.getUniqueId());
                player.kick(Component.text("材质包下载或加载超时，请重新进入驱魔传。"));
            }
        }
    }

    private boolean isExorcismMode() {
        return "exorcism".equalsIgnoreCase(getConfig().getString("mode", "exorcism"));
    }

    boolean isMainMode() {
        return isExorcismMode();
    }

    private boolean isRoomMode() {
        return "room".equalsIgnoreCase(getConfig().getString("mode", "exorcism"));
    }

    private boolean isProxyGated() {
        return getConfig().getBoolean("proxy-gated", false);
    }

    private boolean isHubMode() {
        return !isExorcismMode() && !isRoomMode();
    }

    private void cleanDisabledLobbyItems() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            cleanDisabledLobbyItems(player);
        }
    }

    private void cleanDisabledLobbyItems(Player player) {
        var inventory = player.getInventory();
        boolean changed = false;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isDisabledLobbyItem(inventory.getItem(slot))) {
                inventory.setItem(slot, null);
                changed = true;
            }
        }
        if (changed) {
            player.updateInventory();
        }
    }

    private boolean isDisabledLobbyItem(org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        String name = item.getItemMeta().getDisplayName();
        if (name == null || name.isBlank()) {
            return false;
        }
        String plainName = ChatColor.stripColor(name);
        return (item.getType() == org.bukkit.Material.BLAZE_ROD && "Dash rod".equalsIgnoreCase(plainName))
                || (item.getType() == org.bukkit.Material.BOW && "Teleport bow".equalsIgnoreCase(plainName));
    }

    private void ensureStartDisplays() {
        if (!isMainMode() && !isRoomMode()) {
            return;
        }
        World lobby = Bukkit.getWorld("world");
        if (lobby != null && (teamRoomManager == null
                || !teamRoomManager.isRoomWorld(lobby))) {
            ensureStartDisplaysFor(lobby, "exo_r00_main");
        }
    }

    private void ensureStartDisplaysFor(World world, String namespace) {
        world.getChunkAt(-1, 7).load();
        boolean hasStart;
        if ("exo_r00_main".equals(namespace)) {
            hasStart = hasTaggedEntity(world, "settings_solo")
                    && hasTaggedEntity(world, "settings_solo_text")
                    && hasTaggedEntity(world, "settings_multi")
                    && hasTaggedEntity(world, "settings_multi_text");
        } else {
            hasStart = hasTaggedEntity(world, "settings_start")
                    && hasTaggedEntity(world, "settings_start_text");
        }
        boolean hasIntro = hasTaggedEntity(world, "settings_intro")
                && hasTaggedEntity(world, "settings_intro_text");
        boolean hasThanks = hasTaggedEntity(world, "settings_thanks")
                && hasTaggedEntity(world, "settings_thanks_text");
        if (hasStart && hasIntro && hasThanks) {
            return;
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "execute in " + world.getKey().asString()
                        + " run function " + namespace + ":lobby/_spawn_place/main");
        getLogger().warning("Restored missing start-room displays in world " + world.getName());
    }

    private boolean hasTaggedEntity(org.bukkit.World world, String tag) {
        return world.getEntities().stream().anyMatch(entity -> entity.getScoreboardTags().contains(tag));
    }
}
