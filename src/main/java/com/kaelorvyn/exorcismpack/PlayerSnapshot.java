package com.kaelorvyn.exorcismpack;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

final class PlayerSnapshot {
    private final ItemStack[] inventory;
    private final ItemStack[] enderChest;
    private final double health;
    private final int foodLevel;
    private final float saturation;
    private final float exp;
    private final int level;
    private final int totalExperience;
    private final GameMode gameMode;
    private final List<PotionEffect> effects;
    private final Location location;

    private PlayerSnapshot(ItemStack[] inventory, ItemStack[] enderChest,
                           double health, int foodLevel, float saturation,
                           float exp, int level, int totalExperience,
                           GameMode gameMode, List<PotionEffect> effects,
                           Location location) {
        this.inventory = inventory;
        this.enderChest = enderChest;
        this.health = health;
        this.foodLevel = foodLevel;
        this.saturation = saturation;
        this.exp = exp;
        this.level = level;
        this.totalExperience = totalExperience;
        this.gameMode = gameMode;
        this.effects = effects;
        this.location = location;
    }

    static PlayerSnapshot capture(Player player) {
        ItemStack[] inventory = copyItems(player.getInventory().getContents());
        ItemStack[] enderChest = copyItems(player.getEnderChest().getContents());
        Location location = player.getLocation().clone();
        return new PlayerSnapshot(
                inventory,
                enderChest,
                player.getHealth(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getExp(),
                player.getLevel(),
                player.getTotalExperience(),
                player.getGameMode(),
                List.copyOf(player.getActivePotionEffects()),
                location
        );
    }

    void restore(Player player) {
        player.getInventory().clear();
        player.getInventory().setContents(inventory);
        player.getEnderChest().setContents(enderChest);
        player.setTotalExperience(0);
        player.setLevel(0);
        player.setExp(0);
        player.giveExp(totalExperience);
        player.setHealth(Math.min(health, player.getMaxHealth()));
        player.setFoodLevel(foodLevel);
        player.setSaturation(saturation);
        player.setGameMode(gameMode);

        for (PotionEffectType type : player.getActivePotionEffects().stream()
                .map(PotionEffect::getType).toList()) {
            player.removePotionEffect(type);
        }
        for (PotionEffect effect : effects) {
            player.addPotionEffect(effect);
        }

        Location target = location;
        if (target.getWorld() == null) {
            target = player.getLocation();
        }
        player.teleport(target);
    }

    static PlayerSnapshot missing(Player player) {
        return capture(player);
    }

    private static ItemStack[] copyItems(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[source.length];
        for (int index = 0; index < source.length; index++) {
            copy[index] = source[index] == null ? null : source[index].clone();
        }
        return copy;
    }
}
