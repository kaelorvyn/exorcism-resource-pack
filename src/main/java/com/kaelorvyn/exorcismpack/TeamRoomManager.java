package com.kaelorvyn.exorcismpack;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

final class TeamRoomManager implements CommandExecutor, TabCompleter {
    private static final int MAX_MEMBERS = 4;
    private static final int EMPTY_ROOM_TIMEOUT_TICKS = 6000;
    private static final String LOBBY_WORLD = "world";
    private static final String LOBBY_MAIN_NAMESPACE = "exo_r00_main";
    private static final String LOBBY_AJ_NAMESPACE = "exo_r00_aj";

    private final ExorcismResourcePackPlugin plugin;
    private final PlayerDataManager playerData;
    private final Map<String, TeamRoom> rooms = new ConcurrentHashMap<>();
    private final Map<UUID, TeamRoom> playerRooms = new ConcurrentHashMap<>();
    private final Map<Integer, RunningRoom> activeRooms = new ConcurrentHashMap<>();
    private final Set<Integer> reservedSlots = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PlayerSnapshot> snapshots = new ConcurrentHashMap<>();
    private long tickCount;
    private volatile boolean shuttingDown;

    TeamRoomManager(ExorcismResourcePackPlugin plugin) {
        this.plugin = plugin;
        this.playerData = new PlayerDataManager(plugin);
        Bukkit.getScheduler().runTaskLater(plugin, this::initializeLobby, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickRooms, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::monitorRooms, 20L, 20L);
        Bukkit.getScheduler().runTaskLater(plugin, this::cleanupOrphanWorlds, 40L);
    }

    private void initializeLobby() {
        World world = Bukkit.getWorld(LOBBY_WORLD);
        if (world == null) return;
        String key = world.getKey().asString();
        String position = tickLocation();
        dispatch("execute in " + key + " positioned " + position
                + " run function " + LOBBY_AJ_NAMESPACE + ":global/on_load");
        dispatch("execute in " + key + " positioned " + position
                + " run function " + LOBBY_AJ_NAMESPACE + ":global/internal/gu/load");
        dispatch("execute in " + key + " positioned " + position
                + " run function " + LOBBY_MAIN_NAMESPACE + ":reload");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player) && args.length > 0 && args[0].equalsIgnoreCase("debug")) {
            if (args.length < 3) {
                sender.sendMessage("用法: /kteam debug createworld <槽位> | cleanup <槽位>");
                return true;
            }
            int slot;
            try {
                slot = Integer.parseInt(args[2]);
            } catch (NumberFormatException error) {
                sender.sendMessage("槽位必须是数字");
                return true;
            }
            if (args[1].equalsIgnoreCase("createworld")) {
                debugCreateWorld(sender, slot);
            } else if (args[1].equalsIgnoreCase("cleanup")) {
                debugCleanup(sender, slot);
            } else {
                sender.sendMessage("用法: /kteam debug createworld <槽位> | cleanup <槽位>");
            }
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /kteam.");
            return true;
        }

        RunningRoom currentRoom = roomFor(player);
        if (args.length == 0) {
            if (currentRoom != null) {
                player.sendMessage(ChatColor.GRAY + "副本中，输入 /kteam leave 返回大厅。");
            } else if (plugin.isMainMode()) {
                createOrShow(player);
            } else {
                player.sendMessage(ChatColor.RED + "组队只在驱魔传起始小屋使用。");
            }
            return true;
        }

        String arg = args[0].toLowerCase(Locale.ROOT);
        if (arg.equals("leave")) {
            if (currentRoom != null) {
                returnToLobby(player);
            } else {
                leaveTeam(player);
            }
        } else if (arg.equals("start")) {
            if (currentRoom != null) {
                player.sendMessage(ChatColor.GRAY + "请点击小木屋里的开始游戏。");
            } else if (plugin.isMainMode()) {
                startMulti(player);
            }
        } else if (arg.matches("\\d{6}")) {
            if (currentRoom == null && plugin.isMainMode()) {
                join(player, arg);
            }
        } else {
            player.sendMessage(ChatColor.YELLOW + "/kteam" + ChatColor.WHITE + " 创建队伍，"
                    + ChatColor.YELLOW + "/kteam <六位组队码>" + ChatColor.WHITE + " 加入，"
                    + ChatColor.YELLOW + "/kteam start" + ChatColor.WHITE + " 开始，"
                    + ChatColor.YELLOW + "/kteam leave" + ChatColor.WHITE + " 离开。");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        return List.of("start", "leave").stream()
                .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                .toList();
    }

    void startSolo(Player player) {
        if (roomFor(player) != null) return;
        clearStaleGameTags(player);
        TeamRoom existing = playerRooms.get(player.getUniqueId());
        if (existing != null && !existing.started) {
            removeMember(existing, player.getUniqueId(), player.getName());
        }
        String code;
        do code = String.valueOf(ThreadLocalRandom.current().nextInt(100000, 1000000));
        while (rooms.containsKey(code));
        TeamRoom room = new TeamRoom(code, player.getUniqueId());
        room.started = true;
        playerRooms.put(player.getUniqueId(), room);
        startRoom(room, true);
    }

    void startMulti(Player player) {
        if (roomFor(player) != null) return;
        clearStaleGameTags(player);
        TeamRoom room = playerRooms.get(player.getUniqueId());
        if (room == null) {
            createOrShow(player);
            room = playerRooms.get(player.getUniqueId());
        }
        if (room == null) return;
        if (!room.owner.equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.YELLOW + "只有房主可以开始；房主是 "
                    + Bukkit.getOfflinePlayer(room.owner).getName() + "。");
            return;
        }
        startRoom(room, false);
    }

    void startFromTeamLobby(Player player) {
        TeamRoom team = playerRooms.get(player.getUniqueId());
        if (team == null) return;
        RunningRoom running = activeRoomForTeam(team);
        if (running == null || !running.lobbyPhase || running.world == null) return;
        if (!team.owner.equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.YELLOW + "只有房主可以开始游戏。");
            return;
        }
        if (running.launched) return;
        running.launched = true;
        launchIntoRoom(running);
        unloadTeamLobby(running.slot);
        broadcast(team, ChatColor.GREEN + "游戏开始，正在进入难度选择区域。");
    }

    boolean rejoinActiveRoom(Player player) {
        TeamRoom team = playerRooms.get(player.getUniqueId());
        if (team == null || !team.started) return false;
        RunningRoom running = activeRoomForTeam(team);
        if (running == null || running.world == null) return false;
        if (!running.members.contains(player.getUniqueId())) return false;
        if (running.lobbyPhase) {
            World lobby = Bukkit.getWorld(teamLobbyName(running.slot));
            if (lobby != null && !player.getWorld().equals(lobby)) {
                player.teleport(teamLobbySpawn(lobby));
            }
            player.sendMessage(ChatColor.GREEN + "你已返回次主大厅。");
            return true;
        }
        snapshots.putIfAbsent(player.getUniqueId(), PlayerSnapshot.capture(player));
        player.addScoreboardTag(roomTag(running.slot));
        if (!player.getWorld().equals(running.world)) {
            if (running.soloRoom || running.launched) {
                player.teleport(difficultyLanding(running.world));
            } else {
                player.teleport(roomStart(running.world));
            }
        }
        player.sendMessage(ChatColor.GREEN + "你已返回队伍副本。");
        return true;
    }

    void handleQuit(Player player) {
        RunningRoom room = roomFor(player);
        if (room != null) {
            capturePersonalAchievements(player);
            if (!room.lobbyPhase) {
                room.savedData.put(player.getUniqueId(), PlayerSnapshot.capture(player));
                restoreSnapshot(player);
                snapshots.put(player.getUniqueId(), PlayerSnapshot.capture(player));
            }
            player.removeScoreboardTag(roomTag(room.slot));
            removeMemberFromRunning(room, player.getUniqueId(), player.getName());
            return;
        }
        TeamRoom team = playerRooms.get(player.getUniqueId());
        if (team == null || team.started) return;
        removeMember(team, player.getUniqueId(), player.getName());
    }

    void tagEntity(Entity entity) {
        RunningRoom room = roomForWorld(entity.getWorld());
        if (room != null) {
            entity.addScoreboardTag(roomTag(room.slot));
        }
    }

    boolean isRoomWorld(World world) {
        return roomForWorld(world) != null;
    }

    void shutdown() {
        shuttingDown = true;
        activeRooms.clear();
        reservedSlots.clear();
        snapshots.clear();
    }

    private void createOrShow(Player player) {
        TeamRoom existing = playerRooms.get(player.getUniqueId());
        if (existing != null) {
            player.sendMessage(ChatColor.GREEN + "你的组队码是 " + ChatColor.GOLD + existing.code
                    + ChatColor.GREEN + "，当前人数 " + existing.size() + "/" + MAX_MEMBERS + "。");
            return;
        }
        String code;
        do code = String.valueOf(ThreadLocalRandom.current().nextInt(100000, 1000000));
        while (rooms.containsKey(code));
        TeamRoom room = new TeamRoom(code, player.getUniqueId());
        rooms.put(code, room);
        playerRooms.put(player.getUniqueId(), room);
        player.sendMessage(ChatColor.GREEN + "队伍已创建，组队码：" + ChatColor.GOLD + code);
        player.sendMessage(ChatColor.GRAY + "队友输入 /kteam " + code + " 加入，最多 4 人。"
                + "房主点击多人游戏或输入 /kteam start 开房。");
    }

    private void join(Player player, String code) {
        if (playerRooms.containsKey(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "你已经在一个队伍里了，先输入 /kteam leave。");
            return;
        }
        TeamRoom room = rooms.get(code);
        if (room == null) {
            player.sendMessage(ChatColor.RED + "这个组队码不存在或队伍已经开始。");
            return;
        }
        if (room.started) {
            RunningRoom running = activeRoomForTeam(room);
            if (running == null) {
                player.sendMessage(ChatColor.RED + "这个组队码不存在或队伍已经开始。");
                return;
            }
            if (running.savedData.containsKey(player.getUniqueId())) {
                rejoinRunningRoom(running, player);
                return;
            }
            if (running.members.contains(player.getUniqueId())
                    || running.team.members.contains(player.getUniqueId())) {
                synchronized (room) {
                    if (!running.team.members.contains(player.getUniqueId())) {
                        running.team.members.add(player.getUniqueId());
                    }
                }
                if (!running.members.contains(player.getUniqueId())) {
                    running.members.add(player.getUniqueId());
                }
                playerRooms.put(player.getUniqueId(), room);
                player.addScoreboardTag(roomTag(running.slot));
                if (running.lobbyPhase && !running.launched) {
                    World lobby = Bukkit.getWorld(teamLobbyName(running.slot));
                    if (lobby != null) {
                        player.teleport(teamLobbySpawn(lobby));
                    }
                } else if (running.world != null) {
                    player.teleport(teamLocation(running, player));
                }
                player.sendMessage(ChatColor.GREEN + "你已回到队伍。");
                return;
            }
            if (running.launched) {
                player.sendMessage(ChatColor.RED + "这个组队码不存在或队伍已经开始。");
                return;
            }
            synchronized (room) {
                if (room.members.size() >= MAX_MEMBERS) {
                    player.sendMessage(ChatColor.RED + "这个队伍已经满 4 人了。");
                    return;
                }
                room.members.add(player.getUniqueId());
                playerRooms.put(player.getUniqueId(), room);
                running.members.add(player.getUniqueId());
            }
            World lobby = Bukkit.getWorld(teamLobbyName(running.slot));
            if (lobby != null) {
                player.teleport(teamLobbySpawn(lobby));
            }
            broadcast(room, ChatColor.GREEN + player.getName() + " 加入了队伍，当前人数 "
                    + room.size() + "/" + MAX_MEMBERS);
            return;
        }
        synchronized (room) {
            if (room.members.size() >= MAX_MEMBERS) {
                player.sendMessage(ChatColor.RED + "这个队伍已经满 4 人了。");
                return;
            }
            room.members.add(player.getUniqueId());
            playerRooms.put(player.getUniqueId(), room);
        }
        teleportToStartHut(player);
        broadcast(room, ChatColor.GREEN + player.getName() + " 加入了队伍，当前人数 "
                + room.size() + "/" + MAX_MEMBERS);
    }

    private void leaveTeam(Player player) {
        TeamRoom room = playerRooms.get(player.getUniqueId());
        if (room == null) {
            player.sendMessage(ChatColor.GRAY + "你当前不在队伍中。");
            return;
        }
        if (room.started) {
            if (activeRoomForTeam(room) == null) {
                clearRoom(room);
                player.sendMessage(ChatColor.GRAY + "已退出异常状态下的队伍。");
            } else {
                player.sendMessage(ChatColor.YELLOW + "队伍正在次主大厅/副本中，无法离开。");
            }
            return;
        }
        removeMember(room, player.getUniqueId(), player.getName());
    }

    private void removeMember(TeamRoom room, UUID member, String name) {
        String message;
        synchronized (room) {
            if (!room.members.remove(member)) return;
            playerRooms.remove(member, room);
            if (room.members.isEmpty()) {
                rooms.remove(room.code, room);
                message = ChatColor.YELLOW + "队伍已关闭。";
            } else if (room.owner.equals(member)) {
                room.owner = room.members.get(0);
                message = ChatColor.YELLOW + name + " 离开了队伍，新房主是 "
                        + Bukkit.getOfflinePlayer(room.owner).getName() + "。";
            } else {
                message = ChatColor.YELLOW + name + " 离开了队伍。";
            }
        }
        broadcast(room, message);
    }

    private void startRoom(TeamRoom room, boolean solo) {
        synchronized (room) {
            RunningRoom existing = activeRoomForTeam(room);
            if (existing != null && existing.world != null) {
                teleportTeamInto(existing);
                return;
            }
            if (room.started) {
                if (activeRoomForTeam(room) == null) {
                    room.started = false;
                } else {
                    broadcast(room, ChatColor.YELLOW + "队伍已经在创建副本，请稍候。");
                    return;
                }
            }
            if (room.members.stream().noneMatch(id -> Bukkit.getPlayer(id) != null)) {
                broadcast(room, ChatColor.RED + "队伍里没有在线玩家，无法创建副本。");
                return;
            }
            room.started = true;
        }

        int slot = findFreeSlot();
        if (slot == 0) {
            room.started = false;
            broadcast(room, ChatColor.RED + "当前副本房间已满，请稍后再试。");
            return;
        }

        List<UUID> members = membersOf(room);
        RunningRoom running = new RunningRoom(room, slot, worldName(slot), members, tickCount, !solo, solo);
        activeRooms.put(slot, running);
        broadcast(room, ChatColor.YELLOW + (solo ? "正在创建单人副本，请稍候……"
                : "正在创建队伍副本与次主大厅，请稍候……"));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                prepareWorld(slot);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        finishWorldSetup(running);
                    } catch (Exception error) {
                        failSetup(running, "副本创建失败：" + readable(error));
                    }
                });
            } catch (Exception error) {
                failSetup(running, "副本创建失败：" + readable(error));
            }
        });
    }

    private void debugCreateWorld(CommandSender sender, int slot) {
        if (activeRooms.containsKey(slot)) {
            sender.sendMessage("槽位 " + slot + " 已在运行");
            return;
        }
        TeamRoom room = new TeamRoom("debug", null);
        room.members.clear();
        room.started = true;
        RunningRoom running = new RunningRoom(room, slot, worldName(slot), List.of(), tickCount, false, false);
        activeRooms.put(slot, running);
        reservedSlots.add(slot);
        sender.sendMessage("正在创建 " + worldName(slot) + " ...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                prepareWorld(slot);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        finishWorldSetup(running);
                        sender.sendMessage("房间世界已创建");
                    } catch (Exception error) {
                        failSetup(running, "调试副本创建失败：" + readable(error));
                    }
                });
            } catch (Exception error) {
                failSetup(running, "调试副本创建失败：" + readable(error));
            }
        });
    }

    private void debugCleanup(CommandSender sender, int slot) {
        RunningRoom running = activeRooms.get(slot);
        if (running == null) {
            sender.sendMessage("槽位 " + slot + " 没有运行");
            return;
        }
        finishRoom(running);
        sender.sendMessage("房间世界已清理");
    }

    private void prepareWorld(int slot) throws IOException {
        Path worldContainer = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        Path target = worldContainer.resolve(worldName(slot)).normalize();
        if (!target.startsWith(worldContainer)) {
            throw new IOException("房间目录不在服务器根目录下");
        }
        Path template = Path.of(plugin.getConfig().getString(
                "multiworld.template", "D:/MC/server/exorcism-template/world"));
        if (!Files.isDirectory(template)) {
            throw new IOException("模板不存在：" + template);
        }
        deleteTree(target);
        copyTree(template, target);
        deleteTree(target.resolve("datapacks").resolve("Main"));
        deleteTree(target.resolve("datapacks").resolve("AJ"));
        deleteTree(target.resolve("playerdata"));
        deleteTree(target.resolve("stats"));
        deleteTree(target.resolve("advancements"));
        deleteTree(target.resolve("entities"));
        Path dataDirectory = target.resolve("data");
        if (Files.isDirectory(dataDirectory)) {
            try (var files = Files.list(dataDirectory)) {
                for (Path file : files.toList()) {
                    String name = file.getFileName().toString();
                    if (name.equals("scoreboard.dat")
                            || name.startsWith("command_storage_")
                            || name.equals("raids.dat")
                            || name.equals("random_sequences.dat")) {
                        Files.deleteIfExists(file);
                    }
                }
            }
        }
        Files.deleteIfExists(target.resolve("session.lock"));
        Files.deleteIfExists(target.resolve("uid.dat"));

        Path instanceRoot = Path.of(plugin.getConfig().getString(
                "multiworld.instances",
                "D:/MC/server/[25580] 驱魔传/plugins/ExorcismResourcePack/instances"));
        Path slotPacks = instanceRoot.resolve(instancePrefix(slot));
        if (!Files.isDirectory(slotPacks)) {
            throw new IOException("房间实例数据包不存在：" + slotPacks);
        }
        Path datapacks = target.resolve("datapacks");
        try (var packs = Files.list(slotPacks)) {
            for (Path pack : packs.toList()) {
                if (Files.isDirectory(pack)) {
                    copyTree(pack, datapacks.resolve(pack.getFileName()));
                }
            }
        }
    }

    private void finishWorldSetup(RunningRoom running) {
        if (activeRooms.get(running.slot) != running) return;
        World world = Bukkit.getWorld(running.worldName);
        if (world == null) {
            world = Bukkit.createWorld(new WorldCreator(running.worldName)
                    .environment(World.Environment.NORMAL)
                    .generateStructures(false));
        }
        if (world == null) {
            failSetup(running, "副本世界加载失败。");
            return;
        }
        running.world = world;
        String key = world.getKey().asString();
        String position = tickLocation();
        dispatch("execute in " + key + " positioned " + position
                + " run function " + ajNamespace(running.slot) + ":global/on_load");
        dispatch("execute in " + key + " positioned " + position
                + " run function " + ajNamespace(running.slot) + ":global/internal/gu/load");
        dispatch("execute in " + key + " positioned " + position
                + " run function " + mainNamespace(running.slot) + ":reload");

        String tag = roomTag(running.slot);
        for (Entity entity : world.getEntities()) {
            entity.addScoreboardTag(tag);
        }

        if (running.lobbyPhase) {
            World lobby = ensureTeamLobby(running);
            if (lobby == null) {
                failSetup(running, "次主大厅加载失败。");
                return;
            }
            renderTeamLobby(lobby, running.team.code);
            for (UUID member : running.members) {
                Player player = Bukkit.getPlayer(member);
                if (player == null || !player.isOnline()) continue;
                player.teleport(teamLobbySpawn(lobby));
            }
            broadcast(running.team, ChatColor.GREEN + "副本已创建，已进入次主大厅等待队友。");
            return;
        }

        launchIntoRoom(running);
        broadcast(running.team, ChatColor.GREEN + "副本已创建，正在进入难度选择区域。");
    }

    /**
     * 等价于原版在小木屋点一次“开始游戏”：数据包自己初始化大厅 UI 并把人送到开局门，
     * 然后我们把玩家放到难度/天赋选择区并打上不被拉回小木屋的标记。
     */
    private void launchIntoRoom(RunningRoom running) {
        for (UUID member : running.members) {
            Player player = Bukkit.getPlayer(member);
            if (player == null || !player.isOnline()) continue;
            snapshots.put(member, PlayerSnapshot.capture(player));
            player.addScoreboardTag(roomTag(running.slot));
            resetPlayerScores(player, running.slot);
        }
        revokeRoomAdvancements(running.slot);
        dispatch("execute in " + running.world.getKey().asString()
                + " run function " + mainNamespace(running.slot) + ":lobby/_spawn_place/interaction/start");
        for (UUID member : running.members) {
            Player player = Bukkit.getPlayer(member);
            if (player == null || !player.isOnline()) continue;
            markSpawned(player, running.slot);
            player.teleport(difficultyLanding(running.world));
        }
    }

    private void revokeRoomAdvancements(int slot) {
        String namespace = mainNamespace(slot);
        for (String advancement : new String[]{
                "use_skill",
                "enter_end_gateway",
                "normal_attack",
                "crit_attack",
                "enchant_item",
                "consume_item",
                "enemy_attack"
        }) {
            dispatch("advancement revoke @a only " + namespace + ":" + advancement);
        }
    }

    private void tickRooms() {
        tickCount++;
        if (shuttingDown) return;
        World lobby = Bukkit.getWorld(LOBBY_WORLD);
        if (lobby != null) {
            String key = lobby.getKey().asString();
            String position = tickLocation();
            dispatch("execute in " + key + " positioned " + position
                    + " run function " + LOBBY_MAIN_NAMESPACE + ":_tick");
            dispatch("execute in " + key + " positioned " + position
                    + " run function " + LOBBY_AJ_NAMESPACE + ":global/on_tick");
        }
        for (RunningRoom running : activeRooms.values()) {
            World world = running.world;
            if (world == null) continue;
            if (tickCount % 100 == 0) {
                String tag = roomTag(running.slot);
                for (Entity entity : world.getEntities()) {
                    entity.addScoreboardTag(tag);
                }
            }
            String key = world.getKey().asString();
            String position = tickLocation();
            dispatch("execute in " + key + " positioned " + position
                    + " run function " + mainNamespace(running.slot) + ":_tick");
            dispatch("execute in " + key + " positioned " + position
                    + " run function " + ajNamespace(running.slot) + ":global/on_tick");
        }
        enforceRoomWorlds();
    }

    /**
     * 模拟子服务器隔离：房间成员永远只允许待在自己的副本世界里。
     * 数据包如果有任何传送把玩家漏到别的世界，这里会在下一 tick 拉回同坐标。
     */
    private void enforceRoomWorlds() {
        for (RunningRoom running : activeRooms.values()) {
            if (running.world == null) continue;
            if (running.lobbyPhase && !running.launched) continue;
            for (UUID member : running.members) {
                Player player = Bukkit.getPlayer(member);
                if (player == null || !player.isOnline()) continue;
                if (player.getWorld().equals(running.world)) continue;
                Location location = player.getLocation();
                player.teleport(new Location(running.world,
                        location.getX(), location.getY(), location.getZ(),
                        location.getYaw(), location.getPitch()));
            }
        }
    }

    private void monitorRooms() {
        if (shuttingDown) return;
        for (RunningRoom running : activeRooms.values()) {
            if (running.world == null) {
                if (tickCount - running.createdTick >= 600) {
                    failSetup(running, "副本创建超时，请重新开始。");
                }
                continue;
            }
            if (hasPeopleInRoom(running)) {
                running.emptySinceTick = -1;
            } else {
                if (running.emptySinceTick < 0) {
                    running.emptySinceTick = tickCount;
                } else if (tickCount - running.emptySinceTick >= EMPTY_ROOM_TIMEOUT_TICKS) {
                    finishRoom(running);
                    continue;
                }
            }
            int startState = score(instancePrefix(running.slot) + "_Ctrl",
                    "#" + instancePrefix(running.slot) + "_DoGameStart");
            if (startState == 1) {
                running.startedOnce = true;
            } else if (running.startedOnce) {
                if (running.endTick < 0) {
                    running.endTick = tickCount;
                } else if (tickCount - running.endTick >= 60) {
                    finishRoom(running);
                }
            }
        }
    }

    private void finishRoom(RunningRoom running) {
        if (!activeRooms.remove(running.slot, running)) return;
        reservedSlots.remove(running.slot);
        broadcast(running.team, ChatColor.YELLOW + "副本已结束，正在返回大厅。");
        syncAchievements(running);
        for (UUID member : running.members) {
            Player player = Bukkit.getPlayer(member);
            if (player == null || !player.isOnline()) continue;
            if (!player.getWorld().equals(running.world)) continue;
            restoreSnapshot(player);
            player.removeScoreboardTag(roomTag(running.slot));
            teleportToLobby(player);
        }
        for (UUID member : running.members) {
            snapshots.remove(member);
        }
        for (UUID member : running.savedData.keySet()) {
            snapshots.remove(member);
        }
        running.savedData.clear();

        World world = running.world;
        if (world != null) {
            String key = world.getKey().asString();
            dispatch("execute in " + key + " positioned 0 0 0 run function "
                    + mainNamespace(running.slot) + ":instance_cleanup");
            Bukkit.unloadWorld(world, false);
        }
        deleteWorldAsync(running.slot);
        unloadTeamLobby(running.slot);
        clearRoom(running.team);
    }

    private void syncAchievements(RunningRoom running) {
        String prefix = instancePrefix(running.slot);
        String objective = prefix + "_Ctrl";
        boolean difficulty9 = flag(objective, "#" + prefix + "_Challenge_set_1_1");
        boolean difficulty12 = flag(objective, "#" + prefix + "_Challenge_set_1_2");
        boolean difficulty15 = flag(objective, "#" + prefix + "_Challenge_set_1_3");
        boolean ending1 = flag(objective, "#" + prefix + "_Challenge_set_2_1");
        boolean ending2 = flag(objective, "#" + prefix + "_Challenge_set_2_2");
        boolean ending3 = flag(objective, "#" + prefix + "_Challenge_set_2_3");
        for (UUID member : running.members) {
            if (difficulty9) playerData.grantAchievement(member, "difficulty_9");
            if (difficulty12) playerData.grantAchievement(member, "difficulty_12");
            if (difficulty15) playerData.grantAchievement(member, "difficulty_15");
            if (ending1) playerData.grantAchievement(member, "ending_newborn");
            if (ending2) playerData.grantAchievement(member, "ending_exorcism");
            if (ending3) playerData.grantAchievement(member, "ending_third");
        }
        for (UUID member : running.members) {
            Player player = Bukkit.getPlayer(member);
            if (player != null && player.isOnline()) {
                capturePersonalAchievements(player);
            }
        }
    }

    private void capturePersonalAchievements(Player player) {
        if (player.getScoreboardTags().contains("exo_challenge_damage_999")) {
            playerData.grantAchievement(player.getUniqueId(), "damage_999");
            player.removeScoreboardTag("exo_challenge_damage_999");
        }
        if (player.getScoreboardTags().contains("exo_challenge_defense_90")) {
            playerData.grantAchievement(player.getUniqueId(), "defense_90");
            player.removeScoreboardTag("exo_challenge_defense_90");
        }
        if (player.getScoreboardTags().contains("exo_challenge_gold_plating")) {
            playerData.grantAchievement(player.getUniqueId(), "gold_plating_s");
            player.removeScoreboardTag("exo_challenge_gold_plating");
        }
    }

    private boolean flag(String objective, String entry) {
        return score(objective, entry) >= 1;
    }

    private int score(String objectiveName, String entry) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective objective = scoreboard.getObjective(objectiveName);
        if (objective == null) return 0;
        return objective.getScore(entry).getScore();
    }

    private void returnToLobby(Player player) {
        RunningRoom room = roomFor(player);
        if (room == null) return;
        if (room.lobbyPhase) {
            player.removeScoreboardTag(roomTag(room.slot));
            teleportToLobby(player);
            removeMemberFromRunning(room, player.getUniqueId(), player.getName());
            player.sendMessage(ChatColor.GRAY + "已返回大厅。");
            return;
        }
        room.savedData.put(player.getUniqueId(), PlayerSnapshot.capture(player));
        restoreSnapshot(player);
        player.removeScoreboardTag(roomTag(room.slot));
        teleportToLobby(player);
        snapshots.put(player.getUniqueId(), PlayerSnapshot.capture(player));
        removeMemberFromRunning(room, player.getUniqueId(), player.getName());
        player.sendMessage(ChatColor.GRAY + "已返回大厅，输入 /kteam "
                + room.team.code + " 可重新加入副本。");
    }

    private void removeMemberFromRunning(RunningRoom running, UUID member, String name) {
        running.members.remove(member);
        String message;
        synchronized (running.team) {
            if (!running.team.members.remove(member)) return;
            playerRooms.remove(member, running.team);
            if (running.team.members.isEmpty()) {
                message = name + " 离开了队伍。";
            } else if (running.team.owner.equals(member)) {
                running.team.owner = running.team.members.get(0);
                message = name + " 离开了队伍，新房主是 "
                        + Bukkit.getOfflinePlayer(running.team.owner).getName() + "。";
            } else {
                message = name + " 离开了队伍。";
            }
        }
        broadcast(running.team, message);
    }

    private void rejoinRunningRoom(RunningRoom running, Player player) {
        PlayerSnapshot saved = running.savedData.remove(player.getUniqueId());
        if (saved == null) return;
        snapshots.put(player.getUniqueId(), PlayerSnapshot.capture(player));
        synchronized (running.team) {
            if (!running.team.members.contains(player.getUniqueId())) {
                running.team.members.add(player.getUniqueId());
            }
        }
        if (!running.members.contains(player.getUniqueId())) {
            running.members.add(player.getUniqueId());
        }
        playerRooms.put(player.getUniqueId(), running.team);
        player.addScoreboardTag(roomTag(running.slot));
        saved.restore(player);
        player.teleport(teamLocation(running, player));
        player.sendMessage(ChatColor.GREEN + "你已重新加入副本。");
        broadcast(running.team, ChatColor.GREEN + player.getName() + " 重新加入了副本。");
    }

    private Location teamLocation(RunningRoom running, Player rejoining) {
        for (UUID member : running.members) {
            if (member.equals(rejoining.getUniqueId())) continue;
            Player player = Bukkit.getPlayer(member);
            if (player != null && player.isOnline() && player.getWorld().equals(running.world)) {
                return player.getLocation().clone();
            }
        }
        return roomStart(running.world);
    }

    private void restoreSnapshot(Player player) {
        PlayerSnapshot snapshot = snapshots.remove(player.getUniqueId());
        if (snapshot == null) {
            player.getInventory().clear();
            player.getEnderChest().clear();
        } else {
            snapshot.restore(player);
        }
    }

    private void failSetup(RunningRoom running, String message) {
        if (!activeRooms.remove(running.slot, running)) return;
        reservedSlots.remove(running.slot);
        running.team.started = false;
        synchronized (running.team) {
            if (running.team.members.isEmpty()) {
                rooms.remove(running.team.code, running.team);
            }
        }
        for (UUID member : running.savedData.keySet()) {
            snapshots.remove(member);
        }
        running.savedData.clear();
        broadcast(running.team, ChatColor.RED + message);
        plugin.getLogger().warning(message);
    }

    private void clearRoom(TeamRoom room) {
        rooms.remove(room.code, room);
        synchronized (room) {
            for (UUID member : room.members) {
                playerRooms.remove(member, room);
            }
            room.members.clear();
        }
    }

    private RunningRoom activeRoomForTeam(TeamRoom team) {
        for (RunningRoom running : activeRooms.values()) {
            if (running.team == team) return running;
        }
        return null;
    }

    private void teleportTeamInto(RunningRoom running) {
        World target = running.lobbyPhase ? Bukkit.getWorld(teamLobbyName(running.slot)) : running.world;
        if (target == null) target = running.world;
        Location location = running.lobbyPhase ? teamLobbySpawn(target) : roomStart(running.world);
        for (UUID member : running.members) {
            Player player = Bukkit.getPlayer(member);
            if (player == null || !player.isOnline()) continue;
            if (!running.lobbyPhase) {
                player.addScoreboardTag(roomTag(running.slot));
            }
            player.teleport(location);
        }
        broadcast(running.team, ChatColor.GREEN + "副本已存在，正在传送队伍。");
    }

    private int findFreeSlot() {
        int slots = Math.max(1, plugin.getConfig().getInt("multiworld.slots", 4));
        for (int slot = 1; slot <= slots; slot++) {
            if (!reservedSlots.add(slot)) continue;
            if (Bukkit.getWorld(worldName(slot)) == null) return slot;
            reservedSlots.remove(slot);
        }
        return 0;
    }

    private void resetPlayerScores(Player player, int slot) {
        String prefix = instancePrefix(slot);
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Objective objective : scoreboard.getObjectives()) {
            if (objective.getName().startsWith(prefix + "_")) {
                objective.getScore(player.getName()).setScore(0);
            }
        }
    }

    private void markSpawned(Player player, int slot) {
        Objective objective = Bukkit.getScoreboardManager().getMainScoreboard()
                .getObjective(instancePrefix(slot) + "_WhetherTP");
        if (objective != null) {
            objective.getScore(player.getName()).setScore(1);
        }
    }

    private void clearStaleGameTags(Player player) {
        for (String tag : new String[]{
                "Main_Player",
                "kit_1", "kit_2", "kit_3", "kit_4",
                "kit_5", "kit_6", "kit_7", "kit_8"
        }) {
            player.removeScoreboardTag(tag);
        }
    }

    private void teleportToStartHut(Player player) {
        World world = Bukkit.getWorld(LOBBY_WORLD);
        if (world != null && player.getWorld().equals(world)) {
            player.teleport(new Location(world, -9.5, -59.0, 118.5, 0.0f, 0.0f));
            player.setGameMode(GameMode.ADVENTURE);
        }
    }

    private void teleportToLobby(Player player) {
        World world = Bukkit.getWorld(LOBBY_WORLD);
        if (world != null) {
            player.teleport(new Location(world, -9.5, -59.0, 118.5, 0.0f, 0.0f));
            player.setGameMode(GameMode.ADVENTURE);
        }
    }

    private Location roomStart(World world) {
        return new Location(world, -9.5, -59.0, 118.5, 0.0f, 0.0f);
    }

    private Location difficultyLanding(World world) {
        return new Location(world, -50.5, -59.0, 111.5, 180.0f, 0.0f);
    }

    private String tickLocation() {
        return plugin.getConfig().getString("multiworld.tick-location", "0 0 0");
    }

    private void broadcast(TeamRoom room, String message) {
        for (UUID member : membersOf(room)) {
            Player player = Bukkit.getPlayer(member);
            if (player != null && player.isOnline()) player.sendMessage(message);
        }
    }

    private List<UUID> membersOf(TeamRoom room) {
        synchronized (room) {
            return List.copyOf(room.members);
        }
    }

    private RunningRoom roomFor(Player player) {
        RunningRoom room = roomForWorld(player.getWorld());
        if (room != null) return room;
        for (RunningRoom running : activeRooms.values()) {
            if (!running.lobbyPhase) continue;
            World lobby = Bukkit.getWorld(teamLobbyName(running.slot));
            if (lobby != null && lobby.equals(player.getWorld())) return running;
        }
        return null;
    }

    private RunningRoom roomForWorld(World world) {
        for (RunningRoom running : activeRooms.values()) {
            if (running.worldName.equals(world.getName())) return running;
        }
        return null;
    }

    private boolean hasPeopleInRoom(RunningRoom running) {
        for (UUID member : running.members) {
            Player player = Bukkit.getPlayer(member);
            if (player == null || !player.isOnline()) continue;
            if (player.getWorld().equals(running.world)) return true;
            if (running.lobbyPhase) {
                World lobby = Bukkit.getWorld(teamLobbyName(running.slot));
                if (lobby != null && player.getWorld().equals(lobby)) return true;
            }
        }
        return false;
    }

    private String worldName(int slot) {
        return plugin.getConfig().getString("multiworld.world-prefix", "exo_room_")
                + String.format("%02d", slot);
    }

    private String teamLobbyName(int slot) {
        return plugin.getConfig().getString("teamlobby.world-prefix", "exo_teamlobby_")
                + String.format("%02d", slot);
    }

    private Location teamLobbySpawn(World world) {
        return new Location(world, -9.5, -58.5, 118.5, 180.0f, 0.0f);
    }

    private World ensureTeamLobby(RunningRoom running) {
        String name = teamLobbyName(running.slot);
        World world = Bukkit.getWorld(name);
        if (world != null) return world;
        Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        Path target = container.resolve(name).normalize();
        if (!target.startsWith(container)) return null;
        if (!Files.isDirectory(target)) {
            Path template = Path.of(plugin.getConfig().getString(
                    "teamlobby.template", "D:/MC/server/exorcism-teamlobby-template/world"));
            if (!Files.isDirectory(template)) {
                plugin.getLogger().warning("Team lobby template missing: " + template);
                return null;
            }
            try {
                copyTree(template, target);
            } catch (IOException error) {
                plugin.getLogger().warning("Failed to copy team lobby template: " + error.getMessage());
                return null;
            }
        }
        return Bukkit.createWorld(new WorldCreator(name)
                .environment(World.Environment.NORMAL)
                .generateStructures(false));
    }

    private void renderTeamLobby(World lobby, String code) {
        String key = lobby.getKey().asString();
        dispatch("execute in " + key + " run kill @e[tag=team_lobby_display]");
        dispatch("execute in " + key
                + " run summon text_display -9.5 -58 120.5 {brightness:{block:15,sky:15},"
                + "Tags:[\"team_lobby_display\"],text:'[{\"text\":\"组队码：" + code + "\"}]',"
                + "billboard:\"center\",background:-2147483648,line_width:1000}");
        dispatch("execute in " + key
                + " run summon text_display -9.5 -59 120.5 {brightness:{block:15,sky:15},"
                + "Tags:[\"team_lobby_display\"],"
                + "text:'[{\"text\":\"等待队友加入，房主点击开始游戏\"}]',"
                + "billboard:\"center\",background:-2147483648,line_width:1000}");
        dispatch("execute in " + key
                + " run summon interaction -9.5 -58.5 121.5 "
                + "{Tags:[\"team_lobby_display\",\"team_lobby_start\"],height:0.4,width:0.7}");
        dispatch("execute in " + key
                + " run summon text_display -9.5 -58.5 121.5 {brightness:{block:15,sky:15},"
                + "Tags:[\"team_lobby_display\"],text:'[{\"text\":\"开始游戏\"}]',"
                + "billboard:\"center\",background:-2147483648,line_width:1000}");
    }

    private void unloadTeamLobby(int slot) {
        String name = teamLobbyName(slot);
        World world = Bukkit.getWorld(name);
        if (world != null) {
            for (Player player : world.getPlayers()) {
                teleportToLobby(player);
            }
            Bukkit.unloadWorld(world, false);
        }
        Path target = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize().resolve(name);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                deleteTree(target);
            } catch (IOException error) {
                plugin.getLogger().warning("Failed to delete team lobby " + target + ": " + error.getMessage());
            }
        });
    }

    void cleanupOrphanWorlds() {
        String roomPrefix = plugin.getConfig().getString("multiworld.world-prefix", "exo_room_");
        String teamLobbyPrefix = plugin.getConfig().getString("teamlobby.world-prefix", "exo_teamlobby_");
        String collectionPrefix = plugin.getConfig().getString("collection.world-prefix", "exo_collection_");
        Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        for (World world : Bukkit.getWorlds()) {
            String name = world.getName();
            boolean tracked = false;
            for (RunningRoom running : activeRooms.values()) {
                if (running.world != null && running.world.equals(world)) {
                    tracked = true;
                    break;
                }
            }
            if (tracked) continue;
            if (name.startsWith(roomPrefix) || name.startsWith(teamLobbyPrefix)) {
                for (Player player : world.getPlayers()) {
                    teleportToLobby(player);
                }
                Bukkit.unloadWorld(world, false);
                Path target = container.resolve(name).normalize();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        deleteTree(target);
                    } catch (IOException error) {
                        plugin.getLogger().warning("Failed to delete orphan world " + target + ": " + error.getMessage());
                    }
                });
            } else if (name.startsWith(collectionPrefix) && world.getPlayers().isEmpty()) {
                Bukkit.unloadWorld(world, false);
            }
        }
    }

    private String instancePrefix(int slot) {
        return String.format("r%02d", slot);
    }

    private String mainNamespace(int slot) {
        return "exo_" + instancePrefix(slot) + "_main";
    }

    private String ajNamespace(int slot) {
        return "exo_" + instancePrefix(slot) + "_aj";
    }

    private String roomTag(int slot) {
        return "exo_" + instancePrefix(slot);
    }

    private void dispatch(String command) {
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (Exception error) {
            plugin.getLogger().warning("Command failed: " + command + " -> " + error.getMessage());
        }
    }

    private void deleteWorldAsync(int slot) {
        Path target = Bukkit.getWorldContainer().toPath().toAbsolutePath()
                .normalize().resolve(worldName(slot));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                deleteTree(target);
            } catch (IOException error) {
                plugin.getLogger().warning("Failed to delete room " + target + ": " + error.getMessage());
            }
        });
    }

    private static String readable(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
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

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            for (Path current : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(current);
            }
        }
    }

    private static final class TeamRoom {
        private final String code;
        private UUID owner;
        private final List<UUID> members = new ArrayList<>();
        private volatile boolean started;

        private TeamRoom(String code, UUID owner) {
            this.code = code;
            this.owner = owner;
            this.members.add(owner);
        }

        private int size() {
            synchronized (this) {
                return members.size();
            }
        }
    }

    private static final class RunningRoom {
        private final TeamRoom team;
        private final int slot;
        private final String worldName;
        private final List<UUID> members;
        private final long createdTick;
        private final boolean lobbyPhase;
        private final boolean soloRoom;
        private final Map<UUID, PlayerSnapshot> savedData = new ConcurrentHashMap<>();
        private World world;
        private volatile boolean launched;
        private boolean startedOnce;
        private long endTick = -1;
        private long emptySinceTick = -1;

        private RunningRoom(TeamRoom team, int slot, String worldName,
                            List<UUID> members, long createdTick,
                            boolean lobbyPhase, boolean soloRoom) {
            this.team = team;
            this.slot = slot;
            this.worldName = worldName;
            this.members = new ArrayList<>(members);
            this.createdTick = createdTick;
            this.lobbyPhase = lobbyPhase;
            this.soloRoom = soloRoom;
        }
    }
}
