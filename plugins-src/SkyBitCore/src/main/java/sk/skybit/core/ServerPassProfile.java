package sk.skybit.core;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class ServerPassProfile {
    final UUID uuid;
    String name;
    long xp;
    final Set<Integer> claimedTiers = new HashSet<>();
    boolean cosmeticUnlocked;
    boolean cosmeticActive;
    boolean tagUnlocked;
    boolean tagActive;

    private ServerPassProfile(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name == null ? "Unknown" : name;
    }

    static ServerPassProfile load(File file, UUID uuid, String name) {
        ServerPassProfile profile = new ServerPassProfile(uuid, name);
        if (!file.isFile()) return profile;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        profile.name = name == null ? data.getString("name", "Unknown") : name;
        profile.xp = Math.max(0L, data.getLong("xp"));
        profile.claimedTiers.addAll(data.getIntegerList("claimed-tiers"));
        profile.cosmeticUnlocked = data.getBoolean("cosmetic.unlocked");
        profile.cosmeticActive = data.getBoolean("cosmetic.active");
        profile.tagUnlocked = data.getBoolean("tag.unlocked");
        profile.tagActive = data.getBoolean("tag.active");
        return profile;
    }

    void save(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs())
            throw new IOException("Cannot create ServerPass data directory: " + parent);
        YamlConfiguration data = new YamlConfiguration();
        data.set("name", name);
        data.set("xp", xp);
        data.set("claimed-tiers", claimedTiers.stream().sorted().toList());
        data.set("cosmetic.unlocked", cosmeticUnlocked);
        data.set("cosmetic.active", cosmeticActive);
        data.set("tag.unlocked", tagUnlocked);
        data.set("tag.active", tagActive);
        data.save(file);
    }
}
