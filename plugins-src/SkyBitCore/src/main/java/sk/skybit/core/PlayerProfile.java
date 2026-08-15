package sk.skybit.core;

import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

final class PlayerProfile {
    final UUID uuid;
    String name;
    int level = 1;
    long xp;
    long coins;
    long activeSeconds;
    long blocksMined;
    long mobsKilled;
    long fishCaught;
    long distanceTravelled;
    long mineRushPeriod = -1;
    long mineRushBlocks;
    long lastDailyEpoch = -1;
    int dailyStreak;
    String activeTag = "";
    String activeTrail = "";
    final Map<String, Long> questProgress = new HashMap<>();
    final Set<String> questClaims = new HashSet<>();
    final Set<String> achievements = new HashSet<>();
    final Set<Integer> playtimeClaims = new HashSet<>();
    final Set<String> unlocks = new HashSet<>();
    final Set<Integer> mineRushClaims = new HashSet<>();

    PlayerProfile(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    static PlayerProfile load(UUID uuid, ConfigurationSection s) {
        PlayerProfile p = new PlayerProfile(uuid, s.getString("name", "Unknown"));
        p.level = s.getInt("level", 1);
        p.xp = s.getLong("xp");
        p.coins = s.getLong("coins");
        p.activeSeconds = s.getLong("active-seconds");
        p.blocksMined = s.getLong("stats.blocks-mined");
        p.mobsKilled = s.getLong("stats.mobs-killed");
        p.fishCaught = s.getLong("stats.fish-caught");
        p.distanceTravelled = s.getLong("stats.distance-travelled");
        p.mineRushPeriod = s.getLong("mine-rush.period", -1L);
        p.mineRushBlocks = s.getLong("mine-rush.blocks");
        p.mineRushClaims.addAll(s.getIntegerList("mine-rush.claims"));
        p.lastDailyEpoch = s.getLong("daily.last-epoch", -1);
        p.dailyStreak = s.getInt("daily.streak");
        p.activeTag = s.getString("cosmetics.tag", "");
        p.activeTrail = s.getString("cosmetics.trail", "");
        ConfigurationSection progress = s.getConfigurationSection("quest-progress");
        if (progress != null) for (String key : progress.getKeys(false)) p.questProgress.put(key, progress.getLong(key));
        p.questClaims.addAll(s.getStringList("quest-claims"));
        p.achievements.addAll(s.getStringList("achievements"));
        for (int value : s.getIntegerList("playtime-claims")) p.playtimeClaims.add(value);
        p.unlocks.addAll(s.getStringList("unlocks"));
        return p;
    }

    void save(ConfigurationSection s) {
        s.set("name", name);
        s.set("level", level);
        s.set("xp", xp);
        s.set("coins", coins);
        s.set("active-seconds", activeSeconds);
        s.set("stats.blocks-mined", blocksMined);
        s.set("stats.mobs-killed", mobsKilled);
        s.set("stats.fish-caught", fishCaught);
        s.set("stats.distance-travelled", distanceTravelled);
        s.set("mine-rush.period", mineRushPeriod);
        s.set("mine-rush.blocks", mineRushBlocks);
        s.set("mine-rush.claims", new ArrayList<>(mineRushClaims));
        s.set("daily.last-epoch", lastDailyEpoch);
        s.set("daily.streak", dailyStreak);
        s.set("cosmetics.tag", activeTag);
        s.set("cosmetics.trail", activeTrail);
        s.set("quest-progress", null);
        for (Map.Entry<String, Long> entry : questProgress.entrySet()) s.set("quest-progress." + entry.getKey(), entry.getValue());
        s.set("quest-claims", new ArrayList<>(questClaims));
        s.set("achievements", new ArrayList<>(achievements));
        s.set("playtime-claims", new ArrayList<>(playtimeClaims));
        s.set("unlocks", new ArrayList<>(unlocks));
    }
}
