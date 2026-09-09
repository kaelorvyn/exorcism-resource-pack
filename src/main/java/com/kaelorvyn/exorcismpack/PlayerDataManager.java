package com.kaelorvyn.exorcismpack;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

final class PlayerDataManager {
    private final ExorcismResourcePackPlugin plugin;
    private final File dataDirectory;

    PlayerDataManager(ExorcismResourcePackPlugin plugin) {
        this.plugin = plugin;
        this.dataDirectory = new File(plugin.getDataFolder(), "player-data");
        if (!dataDirectory.exists()) {
            dataDirectory.mkdirs();
        }
    }

    void grantAchievement(UUID playerId, String key) {
        File file = new File(dataDirectory, playerId + ".yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        config.set("achievements." + key, true);
        File temporary = new File(dataDirectory, playerId + ".yml.tmp");
        try {
            config.save(temporary);
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            plugin.getLogger().warning("Failed to save achievements for " + playerId + ": " + error.getMessage());
        }
    }

    boolean hasAchievement(UUID playerId, String key) {
        File file = new File(dataDirectory, playerId + ".yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        return config.getBoolean("achievements." + key, false);
    }
}
