package com.kaelorvyn.exorcismpack;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class CollectionRoomManager implements Listener {
    private static final String COLLECTION_TAG = "exo_col";
    private static final String ENTER_LIBRARY = "enter_library";
    private static final String LEAVE_LIBRARY = "leave_library";

    private final ExorcismResourcePackPlugin plugin;
    private final PlayerDataManager playerData;
    private final Map<UUID, String> origins = new ConcurrentHashMap<>();

    CollectionRoomManager(ExorcismResourcePackPlugin plugin) {
        this.plugin = plugin;
        this.playerData = new PlayerDataManager(plugin);
        createObjectives();
    }

    private void createObjectives() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        registerObjective(scoreboard, "exo_col_treasure");
    }

    private void registerObjective(Scoreboard scoreboard, String name) {
        if (scoreboard.getObjective(name) == null) {
            scoreboard.registerNewObjective(name, org.bukkit.scoreboard.Criteria.DUMMY, name);
        }
    }

    void handleQuit(Player player) {
        origins.remove(player.getUniqueId());
        scheduleUnload(player.getWorld());
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!plugin.isMainMode()) {
            return;
        }
        Player player = event.getPlayer();
        Entity clicked = event.getRightClicked();
        if (clicked.getScoreboardTags().contains(ENTER_LIBRARY)
                && !isCollectionWorld(player.getWorld())) {
            event.setCancelled(true);
            openCollection(player);
            return;
        }
        if (clicked.getScoreboardTags().contains(LEAVE_LIBRARY)
                && isCollectionWorld(player.getWorld())) {
            event.setCancelled(true);
            leaveCollection(player);
            return;
        }
        if (isCollectionWorld(player.getWorld())) {
            if (clicked.getScoreboardTags().contains("lib_treasure_next")) {
                event.setCancelled(true);
                treasureNext(player);
            } else if (clicked.getScoreboardTags().contains("lib_treasure_back")) {
                event.setCancelled(true);
                treasureBack(player);
            }
        }
    }

    private void openCollection(Player player) {
        origins.put(player.getUniqueId(), player.getWorld().getName());
        String worldName = collectionWorldName(player.getUniqueId());
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            Path target = Bukkit.getWorldContainer().toPath().toAbsolutePath()
                    .normalize().resolve(worldName);
            if (!Files.isDirectory(target)) {
                Path template = Path.of(plugin.getConfig().getString(
                        "collection.template",
                        "D:/MC/server/exorcism-collection-template/world"));
                if (!Files.isDirectory(template)) {
                    player.sendMessage(ChatColor.RED + "个人收藏室模板不存在，请联系管理员。");
                    plugin.getLogger().warning("Collection template missing: " + template);
                    return;
                }
                try {
                    copyTree(template, target);
                } catch (IOException error) {
                    player.sendMessage(ChatColor.RED + "个人收藏室创建失败：" + error.getMessage());
                    return;
                }
            }
            world = Bukkit.createWorld(new WorldCreator(worldName)
                    .environment(World.Environment.NORMAL)
                    .generateStructures(false));
        }
        if (world == null) {
            player.sendMessage(ChatColor.RED + "个人收藏室加载失败。");
            return;
        }
        setScore(player, "exo_col_treasure", 1);
        player.addScoreboardTag(COLLECTION_TAG);
        player.teleport(new Location(world, -50.5, -39.5, 120.5, -180.0f, 0.0f));
        renderCollection(world, player);
        player.sendMessage(ChatColor.GREEN + "已进入个人收藏室。");
    }

    private void leaveCollection(Player player) {
        String origin = origins.remove(player.getUniqueId());
        player.removeScoreboardTag(COLLECTION_TAG);
        World originWorld = origin == null ? null : Bukkit.getWorld(origin);
        if (originWorld != null) {
            player.teleport(new Location(originWorld, -51.0, -59.0, 113.0, 180.0f, 0.0f));
        } else {
            World lobby = Bukkit.getWorld("world");
            if (lobby != null) {
                player.teleport(new Location(lobby, -9.5, -59.0, 118.5));
            }
        }
        player.sendMessage(ChatColor.GRAY + "已返回房间大厅。");
        scheduleUnload(player.getWorld());
    }

    private String collectionWorldName(UUID playerId) {
        return plugin.getConfig().getString("collection.world-prefix", "exo_collection_") + playerId;
    }

    private boolean isCollectionWorld(World world) {
        return world != null && world.getName().startsWith(
                plugin.getConfig().getString("collection.world-prefix", "exo_collection_"));
    }

    private void scheduleUnload(World world) {
        if (world == null || !isCollectionWorld(world)) {
            return;
        }
        String name = world.getName();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            World current = Bukkit.getWorld(name);
            if (current != null && current.getPlayers().isEmpty()) {
                Bukkit.unloadWorld(current, true);
            }
        }, 100L);
    }

    private void dispatch(String command) {
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (Exception error) {
            plugin.getLogger().warning("Command failed: " + command + " -> " + error.getMessage());
        }
    }

    private void renderCollection(World world, Player player) {
        String key = world.getKey().asString();
        dispatch("execute in " + key + " run kill @e[tag=exo_col_display]");

        UUID id = player.getUniqueId();
        boolean d9 = playerData.hasAchievement(id, "difficulty_9");
        boolean d12 = playerData.hasAchievement(id, "difficulty_12");
        boolean d15 = playerData.hasAchievement(id, "difficulty_15");
        boolean e1 = playerData.hasAchievement(id, "ending_newborn");
        boolean e2 = playerData.hasAchievement(id, "ending_exorcism");
        boolean e3 = playerData.hasAchievement(id, "ending_third");
        boolean a1 = playerData.hasAchievement(id, "damage_999");
        boolean a2 = playerData.hasAchievement(id, "defense_90");
        boolean a3 = playerData.hasAchievement(id, "gold_plating_s");
        int g1 = (d9 ? 1 : 0) + (d12 ? 1 : 0) + (d15 ? 1 : 0);
        int g2 = (e1 ? 1 : 0) + (e2 ? 1 : 0) + (e3 ? 1 : 0);
        int g3 = (a1 ? 1 : 0) + (a2 ? 1 : 0) + (a3 ? 1 : 0);

        colSummon(world, "text_display -45.5 -35 111.5 {brightness:{block:15,sky:15},"
                + "Tags:[\"exo_col_display\"],text:'[{\"translate\":\"challenge.exorcism_legend.title\"}]',"
                + "billboard:\"fixed\",background:-2147483648,line_width:1000,Rotation:[90.0f,0.0f],"
                + "transformation:{left_rotation:[0.0,0.0,0.0,1.0],right_rotation:[0.0,0.0,0.0,1.0],"
                + "scale:[2.0,2.0,2.0],translation:[0.0,0.0,0.0]}}");
        colSummon(world, "text_display -45.5 -35.5 105.5 {brightness:{block:15,sky:15},"
                + "Tags:[\"exo_col_display\"],text:'[{\"translate\":\"challenge.exorcism_legend.1\"}]',"
                + "billboard:\"fixed\",background:-2147483648,line_width:1000,Rotation:[90.0f,0.0f]}");
        colSummon(world, "text_display -45.5 -35.5 111.5 {brightness:{block:15,sky:15},"
                + "Tags:[\"exo_col_display\"],text:'[{\"translate\":\"challenge.exorcism_legend.2\"}]',"
                + "billboard:\"fixed\",background:-2147483648,line_width:1000,Rotation:[90.0f,0.0f]}");
        colSummon(world, "text_display -45.5 -35.5 117.5 {brightness:{block:15,sky:15},"
                + "Tags:[\"exo_col_display\"],text:'[{\"translate\":\"challenge.exorcism_legend.3\"}]',"
                + "billboard:\"fixed\",background:-2147483648,line_width:1000,Rotation:[90.0f,0.0f]}");

        colAchievement(world, "1_1", d9, "105.5", "-36");
        colAchievement(world, "1_2", d12, "105.5", "-36.5");
        colAchievement(world, "1_3", d15, "105.5", "-37");
        colAchievement(world, "2_1", e1, "111.5", "-36");
        colAchievement(world, "2_2", e2, "111.5", "-36.5");
        colAchievement(world, "2_3", e3, "111.5", "-37");
        colAchievement(world, "3_1", a1, "117.5", "-36");
        colAchievement(world, "3_2", a2, "117.5", "-36.5");
        colAchievement(world, "3_3", a3, "117.5", "-37");

        colAward(world, "1_award", "105.5");
        colAward(world, "2_award", "111.5");
        colAward(world, "3_award", "117.5");
        colTrophy(world, g1, "105.5");
        colTrophy(world, g2, "111.5");
        colTrophy(world, g3, "117.5");

        int treasure = Math.max(1, getScore(player, "exo_col_treasure"));
        int treasureCmd = 5000 + treasure;
        colSummon(world, "text_display -56.5 -36.5 118.2 {brightness:{block:15,sky:15},"
                + "Tags:[\"exo_col_display\"],text:'[{\"translate\":\"library.exorcism_legend.treasure_display_TITLE\"}]',"
                + "billboard:\"fixed\",background:-2147483648,line_width:1000,Rotation:[-90.0f,0.0f],"
                + "transformation:{left_rotation:[0.0,0.0,0.0,1.0],right_rotation:[0.0,0.0,0.0,1.0],"
                + "scale:[2.0,2.0,2.0],translation:[0.0,0.0,0.0]}}");
        colSummon(world, "text_display -56.5 -38.9 116.3 {brightness:{block:15,sky:15},"
                + "Tags:[\"exo_col_display\"],text:'[{\"translate\":\"library.exorcism_legend.treasure_next\"}]',"
                + "billboard:\"fixed\",background:-2147483648,line_width:1000,Rotation:[-90.0f,0.0f]}");
        colSummon(world, "interaction -56.5 -38.9 116.3 "
                + "{Tags:[\"exo_col_display\",\"lib_treasure_next\"],height:0.4,width:0.4}");
        colSummon(world, "text_display -56.5 -38.9 120.3 {brightness:{block:15,sky:15},"
                + "Tags:[\"exo_col_display\"],text:'[{\"translate\":\"library.exorcism_legend.treasure_back\"}]',"
                + "billboard:\"fixed\",background:-2147483648,line_width:1000,Rotation:[-90.0f,0.0f]}");
        colSummon(world, "interaction -56.5 -38.9 120.3 "
                + "{Tags:[\"exo_col_display\",\"lib_treasure_back\"],height:0.4,width:0.4}");
        colSummon(world, "text_display -56.5 -39.2 118.3 {brightness:{block:15,sky:15},"
                + "Tags:[\"exo_col_display\"],"
                + "text:'[{\"translate\":\"library.exorcism_legend.treasure_now\"},"
                + "{\"translate\":\"treasure.exorcism_legend.treasure_passive_" + treasure + "_name\"},"
                + "{\"text\":\"(" + treasure + ")\"}]',"
                + "billboard:\"fixed\",background:-2147483648,line_width:1000,Rotation:[-90.0f,0.0f]}");
        colSummon(world, "item_display -56.5 -37.8 119.5 {brightness:{block:15,sky:15},"
                + "Tags:[\"exo_col_display\"],item:{id:\"ender_eye\",components:{custom_model_data:"
                + treasureCmd + "}},billboard:\"fixed\",Rotation:[-90.0f,0.0f],"
                + "transformation:{left_rotation:[0.0,0.0,0.0,1.0],right_rotation:[0.0,0.0,0.0,1.0],"
                + "scale:[1.5,1.5,1.5],translation:[0.0,0.0,0.0]}}");
        colSummon(world, "text_display -56.5 -38.2 117.2 {brightness:{block:15,sky:15},"
                + "Tags:[\"exo_col_display\"],"
                + "text:'[{\"translate\":\"library.exorcism_legend.treasure_effect\"},"
                + "{\"translate\":\"treasure.exorcism_legend.treasure_passive_" + treasure + "_lore\"}]',"
                + "billboard:\"fixed\",background:-2147483648,line_width:100,Rotation:[-90.0f,0.0f]}");

        colSummon(world, "interaction -50.5 -38.5 101.5 "
                + "{Tags:[\"exo_col_display\",\"leave_library\"],height:0.4,width:0.7}");
        colSummon(world, "text_display -50.5 -38.5 101.5 {brightness:{block:15,sky:15},"
                + "Tags:[\"exo_col_display\"],text:'[{\"translate\":\"library.exorcism_legend.back\"}]',"
                + "billboard:\"center\",background:-2147483648,line_width:1000}");
    }

    private void colSummon(World world, String commandTail) {
        dispatch("execute in " + world.getKey().asString() + " run summon " + commandTail);
    }

    private void colAchievement(World world, String slot, boolean done, String z, String y) {
        String color = done ? "green" : "red";
        colSummon(world, "text_display -45.5 " + y + " " + z + " {brightness:{block:15,sky:15},"
                + "Tags:[\"exo_col_display\"],"
                + "text:'[{\"translate\":\"challenge.exorcism_legend." + slot
                + "\",\"color\":\"" + color + "\"}]',"
                + "billboard:\"fixed\",background:-2147483648,line_width:1000,Rotation:[90.0f,0.0f]}");
    }

    private void colAward(World world, String slot, String z) {
        colSummon(world, "text_display -45.5 -37.5 " + z + " {brightness:{block:15,sky:15},"
                + "Tags:[\"exo_col_display\"],"
                + "text:'[{\"translate\":\"challenge.exorcism_legend." + slot + "\"}]',"
                + "billboard:\"fixed\",background:-2147483648,line_width:1000,Rotation:[90.0f,0.0f]}");
    }

    private void colTrophy(World world, int count, String z) {
        if (count < 1 || count > 3) {
            return;
        }
        colSummon(world, "item_display -45.5 -38.5 " + z + " {brightness:{block:15,sky:15},"
                + "Tags:[\"exo_col_display\"],"
                + "item:{id:\"ender_eye\",components:{custom_model_data:1200" + count + "}},"
                + "billboard:\"fixed\",Rotation:[90.0f,0.0f]}");
    }

    private void treasureNext(Player player) {
        int display = getScore(player, "exo_col_treasure") + 1;
        if (display > 162) display = 162;
        if (display == 0) display = 1;
        setScore(player, "exo_col_treasure", display);
        renderCollection(player.getWorld(), player);
    }

    private void treasureBack(Player player) {
        int display = getScore(player, "exo_col_treasure") - 1;
        if (display < -12) display = -12;
        if (display == 0) display = -1;
        setScore(player, "exo_col_treasure", display);
        renderCollection(player.getWorld(), player);
    }

    private int getScore(Player player, String objective) {
        Objective objectiveHandle = Bukkit.getScoreboardManager().getMainScoreboard().getObjective(objective);
        return objectiveHandle == null ? 0 : objectiveHandle.getScore(player.getName()).getScore();
    }

    private void setScore(Player player, String objective, int value) {
        Objective objectiveHandle = Bukkit.getScoreboardManager().getMainScoreboard().getObjective(objective);
        if (objectiveHandle != null) {
            objectiveHandle.getScore(player.getName()).setScore(value);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }
}
