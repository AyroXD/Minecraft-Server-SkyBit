package sk.skybit.core;

import me.clip.placeholderapi.PlaceholderAPI;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.*;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class SkyBitCore extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<UUID, PlayerProfile> profiles = new HashMap<>();
    private final Map<UUID, ServerPassProfile> serverPassProfiles = new HashMap<>();
    private final Map<UUID, Long> lastActivity = new HashMap<>();
    private final Map<UUID, Location> lastDistanceLocation = new HashMap<>();
    private final Map<UUID, Integer> distanceRemainder = new HashMap<>();
    private final Map<UUID, Integer> eventScores = new HashMap<>();
    private final Map<UUID, UUID> voteLlamaLastHit = new HashMap<>();
    private YamlConfiguration data;
    private File dataFile;
    private File voteDataFile;
    private File serverPassDirectory;
    private Economy economy;
    private Chat chat;
    private NamespacedKey customItemKey;
    private NamespacedKey serverPassTierKey;
    private String activeEvent;
    private int eventTaskId = -1;
    private long eventEndsAt;
    private long nextRandomEventAt;
    private long observedVotePartyId;
    private WebBridge webBridge;

    private static final Set<Material> ORES = EnumSet.of(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE, Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE, Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE, Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE, Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.NETHER_GOLD_ORE, Material.NETHER_QUARTZ_ORE, Material.ANCIENT_DEBRIS
    );
    private static final Set<Material> CROPS = EnumSet.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.NETHER_WART, Material.COCOA, Material.MELON, Material.PUMPKIN
    );

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "players.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        loadProfiles();
        voteDataFile = new File(getDataFolder(), getConfig().getString("vote.data-file", "../../../../shared-data/votes.yml"));
        if (voteDataFile.getParentFile() != null) voteDataFile.getParentFile().mkdirs();
        observedVotePartyId = loadVoteData().getLong("party-id", 0L);
        serverPassDirectory = new File(getDataFolder(), getConfig().getString("server-pass.data-directory", "../../../../shared-data/serverpass"));
        if (!serverPassDirectory.exists() && !serverPassDirectory.mkdirs())
            getLogger().warning("ServerPass data directory could not be created: " + serverPassDirectory);
        customItemKey = new NamespacedKey(this, "custom_item");
        serverPassTierKey = new NamespacedKey(this, "serverpass_tier");

        RegisteredServiceProvider<Economy> registration = getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            getLogger().severe("Vault economy provider was not found. Disabling SkyBitCore.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economy = registration.getProvider();
        RegisteredServiceProvider<Chat> chatRegistration = getServer().getServicesManager().getRegistration(Chat.class);
        chat = chatRegistration == null ? null : chatRegistration.getProvider();
        getServer().getPluginManager().registerEvents(this, this);
        for (String name : List.of("level", "quests", "daily", "coinshop", "ranks", "buykey", "skycoins", "tag", "vote", "voteparty", "minerush", "serverpass", "events", "weblink", "skybitvote", "skybitadmin")) {
            PluginCommand command = getCommand(name);
            if (command != null) { command.setExecutor(this); command.setTabCompleter(this); }
        }
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) new SkyBitExpansion(this).register();

        long autosave = Math.max(1, getConfig().getLong("autosave-minutes", 5)) * 1200L;
        getServer().getScheduler().runTaskTimer(this, this::saveProfiles, autosave, autosave);
        getServer().getScheduler().runTaskTimer(this, this::activeMinuteTick, 1200L, 1200L);
        getServer().getScheduler().runTaskTimer(this, this::particleTick, 10L, 10L);
        getServer().getScheduler().runTaskTimer(this, this::syncVoteParty, 200L, 200L);
        long eventInterval = Math.max(15, getConfig().getLong("event-interval-minutes", 120)) * 1200L;
        nextRandomEventAt = System.currentTimeMillis() + eventInterval * 50L;
        getServer().getScheduler().runTaskTimer(this, this::startRandomEvent, eventInterval, eventInterval);
        webBridge = new WebBridge(this);
        webBridge.start();
        for (Player player : Bukkit.getOnlinePlayers()) markActive(player);
    }

    @Override public void onDisable() {
        if (webBridge != null) webBridge.stop();
        saveProfiles();
        saveServerPassProfiles();
    }

    Collection<PlayerProfile> profiles() { return Collections.unmodifiableCollection(profiles.values()); }

    String serverMode() { return getConfig().getString("server-mode", "survival"); }

    double balance(OfflinePlayer player) { return economy == null || player == null ? 0.0 : economy.getBalance(player); }

    String roleName(OfflinePlayer player) {
        if (player == null || chat == null) return "player";
        String world = player.isOnline() && player.getPlayer() != null ? player.getPlayer().getWorld().getName() : null;
        String role = chat.getPrimaryGroup(world, player);
        return role == null || role.isBlank() ? "player" : role.toLowerCase(Locale.ROOT);
    }

    void grantWebCoins(String playerName, long amount) {
        if (amount <= 0 || playerName == null || playerName.isBlank()) return;
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
        PlayerProfile targetProfile = profile(target.getUniqueId(), target.getName() == null ? playerName : target.getName());
        targetProfile.coins += amount;
        saveProfiles();
        if (target.isOnline() && target.getPlayer() != null)
            message(target.getPlayer(), "&aWebShop objednávka doručená: &b+" + amount + " SkyCoins&a.");
        getLogger().info("WebShop delivered " + amount + " SkyCoins to " + playerName + ".");
    }

    PlayerProfile profile(UUID uuid, String name) {
        PlayerProfile result = profiles.computeIfAbsent(uuid, id -> new PlayerProfile(id, name == null ? "Unknown" : name));
        if (name != null) result.name = name;
        return result;
    }

    private PlayerProfile profile(Player player) { return profile(player.getUniqueId(), player.getName()); }

    private File serverPassFile(UUID uuid) { return new File(serverPassDirectory, uuid + ".yml"); }

    private ServerPassProfile serverPassProfile(UUID uuid, String name) {
        ServerPassProfile profile = serverPassProfiles.computeIfAbsent(uuid,
                id -> ServerPassProfile.load(serverPassFile(id), id, name));
        if (name != null) profile.name = name;
        return profile;
    }

    private ServerPassProfile serverPassProfile(Player player) {
        return serverPassProfile(player.getUniqueId(), player.getName());
    }

    private void saveServerPassProfile(ServerPassProfile profile) {
        try { profile.save(serverPassFile(profile.uuid)); }
        catch (IOException exception) { getLogger().severe("Cannot save ServerPass profile " + profile.uuid + ": " + exception.getMessage()); }
    }

    private void saveServerPassProfiles() { serverPassProfiles.values().forEach(this::saveServerPassProfile); }

    int serverPassTier(OfflinePlayer player) {
        if (player == null) return 0;
        return serverPassTier(serverPassProfile(player.getUniqueId(), player.getName()));
    }

    long serverPassXp(OfflinePlayer player) {
        if (player == null) return 0L;
        return serverPassProfile(player.getUniqueId(), player.getName()).xp;
    }

    String activeTag(OfflinePlayer player, PlayerProfile profile) {
        if (player != null && serverPassProfile(player.getUniqueId(), player.getName()).tagActive)
            return color("&d✦ &fPASS MASTER");
        return profile == null ? "" : profile.activeTag;
    }

    String formattedMoney(OfflinePlayer player) {
        return economy == null || player == null ? "$0" : economy.format(economy.getBalance(player));
    }

    String rankPrefix(OfflinePlayer player) {
        if (player == null || chat == null) return color("&8[&7HRÁČ&8]&7");
        String world = player.isOnline() && player.getPlayer() != null ? player.getPlayer().getWorld().getName() : null;
        String value = chat.getPlayerPrefix(world, player);
        return value == null || value.isBlank() ? color("&8[&7HRÁČ&8]&7") : color(value);
    }

    private void loadProfiles() {
        ConfigurationSection root = data.getConfigurationSection("players");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            try { profiles.put(UUID.fromString(id), PlayerProfile.load(UUID.fromString(id), root.getConfigurationSection(id))); }
            catch (IllegalArgumentException ignored) { getLogger().warning("Ignored invalid profile UUID: " + id); }
        }
    }

    private synchronized void saveProfiles() {
        data.set("players", null);
        for (PlayerProfile p : profiles.values()) p.save(data.createSection("players." + p.uuid));
        try { data.save(dataFile); } catch (IOException e) { getLogger().severe("Cannot save players.yml: " + e.getMessage()); }
    }

    private String color(String value) { return ChatColor.translateAlternateColorCodes('&', value); }
    private String prefix() { return color(getConfig().getString("prefix", "&8[&bSkyBit&8] ")); }
    private void message(CommandSender sender, String text) { sender.sendMessage(prefix() + color(text)); }
    private void markActive(Player player) { lastActivity.put(player.getUniqueId(), System.currentTimeMillis()); }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        PlayerProfile joined = profile(event.getPlayer()); markActive(event.getPlayer());
        serverPassProfiles.remove(event.getPlayer().getUniqueId());
        ServerPassProfile pass = serverPassProfile(event.getPlayer());
        lastDistanceLocation.put(event.getPlayer().getUniqueId(), event.getPlayer().getLocation());
        getServer().getScheduler().runTaskLater(this, () -> syncNetworkRank(event.getPlayer()), 40L);
        getServer().getScheduler().runTaskLater(this, () -> {
            if (!event.getPlayer().isOnline()) return;
            if (joined.lastDailyEpoch != dayId()) message(event.getPlayer(), "&eMáš pripravenú dennú odmenu: &f/daily");
            if (hasClaimableServerPassReward(pass)) message(event.getPlayer(), "&dServerPass: &fMáš pripravenú FREE odmenu! &e/serverpass");
            showVoteParty(event.getPlayer(), false);
        }, 80L);
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        lastActivity.remove(event.getPlayer().getUniqueId()); lastDistanceLocation.remove(event.getPlayer().getUniqueId());
        ServerPassProfile pass = serverPassProfiles.remove(event.getPlayer().getUniqueId());
        if (pass != null) saveServerPassProfile(pass);
    }
    @EventHandler(ignoreCancelled = true) public void onInteract(PlayerInteractEvent event) { markActive(event.getPlayer()); }
    @EventHandler(ignoreCancelled = true) public void onCommand(PlayerCommandPreprocessEvent event) { markActive(event.getPlayer()); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onAsyncChat(AsyncChatEvent event) {
        Component prefixComponent = LegacyComponentSerializer.legacySection().deserialize(rankPrefix(event.getPlayer()).trim());
        event.renderer((source, displayName, message, viewer) -> Component.text()
                .append(prefixComponent).append(Component.space()).append(displayName)
                .append(Component.text(" » ", NamedTextColor.DARK_GRAY)).append(message).build());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) return;
        Player player = event.getPlayer();
        markActive(player);
        Location previous = lastDistanceLocation.put(player.getUniqueId(), event.getTo());
        if (previous == null || previous.getWorld() != event.getTo().getWorld()) return;
        double distance = previous.distance(event.getTo());
        if (distance > 20) return;
        int total = distanceRemainder.getOrDefault(player.getUniqueId(), 0) + (int)Math.round(distance * 10);
        int wholeBlocks = total / 10;
        distanceRemainder.put(player.getUniqueId(), total % 10);
        if (wholeBlocks > 0) {
            PlayerProfile p = profile(player); p.distanceTravelled += wholeBlocks;
            incrementQuest(player, "TRAVEL", wholeBlocks);
            scoreEvent(player, "EXPLORER", wholeBlocks);
            if (p.distanceTravelled % 100 < wholeBlocks) {
                long xp = getConfig().getLong("xp.distance-per-100-blocks", 2);
                addXp(player, xp);
                addServerPassXp(player, xp);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        markActive(player);
        Material type = event.getBlock().getType();
        PlayerProfile p = profile(player); p.blocksMined++;
        incrementMineRush(player, p);
        long xp = ORES.contains(type) ? getConfig().getLong("xp.ore-block", 8) : getConfig().getLong("xp.mined-block", 1);
        String custom = customItem(player.getInventory().getItemInMainHand());
        if ("miners_pickaxe".equals(custom)) xp = Math.max(1, Math.round(xp * 1.05));
        if (CROPS.contains(type) && isMature(event)) {
            xp = getConfig().getLong("xp.harvested-crop", 3);
            if ("farmers_hoe".equals(custom)) xp = Math.max(1, Math.round(xp * 1.10));
            incrementQuest(player, "HARVEST_" + cropName(type), 1);
            scoreEvent(player, "HARVEST_FRENZY", 1);
        } else {
            incrementQuest(player, "MINE_ANY", 1);
            if (type == Material.STONE || type == Material.COBBLESTONE || type == Material.DEEPSLATE) incrementQuest(player, "MINE_STONE", 1);
            if (type == Material.IRON_ORE || type == Material.DEEPSLATE_IRON_ORE) incrementQuest(player, "MINE_IRON", 1);
            scoreEvent(player, "ISLAND_MINER", ORES.contains(type) ? 3 : 1);
            if (type.name().endsWith("_LOG") || type.name().endsWith("_STEM")) scoreEvent(player, "LUMBERJACK", 2);
        }
        addXp(player, xp);
        addServerPassXp(player, xp);
        checkAchievements(player);
    }

    private boolean isMature(BlockBreakEvent event) {
        if (!(event.getBlock().getBlockData() instanceof Ageable ageable)) return true;
        return ageable.getAge() >= ageable.getMaximumAge();
    }
    private String cropName(Material type) { return type == Material.WHEAT ? "WHEAT" : type.name(); }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        markActive(killer); profile(killer).mobsKilled++;
        incrementQuest(killer, "KILL_ANY", 1);
        incrementQuest(killer, "KILL_" + event.getEntityType().name(), 1);
        scoreEvent(killer, "BOUNTY_HUNT", 1);
        long xp = getConfig().getLong("xp.mob-kill", 5);
        addXp(killer, xp);
        addServerPassXp(killer, xp);
        checkAchievements(killer);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        Player player = event.getPlayer(); markActive(player); profile(player).fishCaught++;
        long xp = getConfig().getLong("xp.fish-caught", 15);
        if ("fishermans_rod".equals(customItem(player.getInventory().getItemInMainHand()))) xp = Math.round(xp * 1.10);
        addXp(player, xp); addServerPassXp(player, xp); incrementQuest(player, "FISH", 1); scoreEvent(player, "FISHING_DERBY", 1); checkAchievements(player);
    }

    private void activeMinuteTick() {
        long now = System.currentTimeMillis();
        long afkLimit = getConfig().getLong("afk-seconds", 300) * 1000L;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (now - lastActivity.getOrDefault(player.getUniqueId(), 0L) > afkLimit) continue;
            PlayerProfile p = profile(player); p.activeSeconds += 60;
            long xp = getConfig().getLong("xp.active-minute", 5);
            addXp(player, xp);
            addServerPassXp(player, xp);
            incrementQuest(player, "ACTIVE_MINUTE", 1);
            checkPlaytimeRewards(player); checkAchievements(player);
        }
    }

    private void particleTick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (serverPassProfile(player).cosmeticActive && !player.isInvisible())
                player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation().add(0, 1.0, 0), 1, .22, .35, .22, 0.005);
            String trail = profile(player).activeTrail;
            if (trail.isBlank() || player.isInvisible()) continue;
            Particle particle;
            try { particle = Particle.valueOf(trail); } catch (IllegalArgumentException e) { continue; }
            player.getWorld().spawnParticle(particle, player.getLocation().add(0, .2, 0), 1, .15, .1, .15, 0);
        }
    }

    private void addXp(Player player, long amount) {
        if (amount <= 0) return;
        PlayerProfile p = profile(player); p.xp += amount;
        int max = getConfig().getInt("level.max", 100);
        while (p.level < max && p.xp >= requiredXp(p.level)) {
            p.xp -= requiredXp(p.level); p.level++;
            Bukkit.broadcastMessage(prefix() + color("&e" + player.getName() + " &7dosiahol server level &b" + p.level + "&7!"));
            for (String command : getConfig().getStringList("level.rewards." + p.level))
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", player.getName()));
        }
    }

    private long requiredXp(int level) {
        return getConfig().getLong("level.base-xp", 250) + getConfig().getLong("level.quadratic-xp", 20) * level * level;
    }

    private int serverPassMaxTier() { return Math.max(1, getConfig().getInt("server-pass.max-tier", 50)); }

    private long serverPassXpPerTier() { return Math.max(1L, getConfig().getLong("server-pass.xp-per-tier", 500L)); }

    private int serverPassTier(ServerPassProfile profile) {
        return (int)Math.min(serverPassMaxTier(), profile.xp / serverPassXpPerTier());
    }

    private List<Integer> serverPassRewardTiers() { return List.of(1, 5, 10, 25, 50); }

    private void addServerPassXp(Player player, long amount) {
        if (!getConfig().getBoolean("server-pass.enabled", true) || amount <= 0) return;
        ServerPassProfile pass = serverPassProfile(player);
        int oldTier = serverPassTier(pass);
        long maximumXp = serverPassMaxTier() * serverPassXpPerTier();
        pass.xp = Math.min(maximumXp, pass.xp + amount);
        int newTier = serverPassTier(pass);
        if (newTier <= oldTier) return;
        saveServerPassProfile(pass);
        player.sendTitle(color("&d&lSERVERPASS &fTIER " + newTier), color("&aNová FREE odmena môže byť pripravená!"), 10, 50, 15);
        if (serverPassRewardTiers().stream().anyMatch(tier -> tier > oldTier && tier <= newTier))
            message(player, "&aOdomkol si novú ServerPass odmenu. Klikni na ňu v &f/serverpass&a.");
    }

    private boolean hasClaimableServerPassReward(ServerPassProfile pass) {
        int tier = serverPassTier(pass);
        return serverPassRewardTiers().stream().anyMatch(reward -> reward <= tier && !pass.claimedTiers.contains(reward));
    }

    private String serverPassRewardName(int tier) {
        return switch (tier) {
            case 1 -> "&a$5 000";
            case 5 -> "&3Rare Key";
            case 10 -> "&dStarlight kozmetika";
            case 25 -> "&5Epic Crate";
            case 50 -> "&d✦ PASS MASTER tag";
            default -> "&fOdmena";
        };
    }

    private Material serverPassRewardMaterial(int tier) {
        return switch (tier) {
            case 1 -> Material.GOLD_INGOT;
            case 5 -> Material.ECHO_SHARD;
            case 10 -> Material.FIREWORK_STAR;
            case 25 -> Material.AMETHYST_SHARD;
            case 50 -> Material.NAME_TAG;
            default -> Material.CHEST;
        };
    }

    private void openServerPass(Player player) {
        ServerPassProfile pass = serverPassProfile(player);
        int currentTier = serverPassTier(pass);
        long perTier = serverPassXpPerTier();
        long current = currentTier >= serverPassMaxTier() ? perTier : pass.xp % perTier;
        int filled = currentTier >= serverPassMaxTier() ? 20 : (int)Math.min(20L, current * 20L / perTier);
        Inventory inventory = Bukkit.createInventory(null, 54, color("&0SkyBit ServerPass • FREE"));

        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta(); fillerMeta.setDisplayName(" "); filler.setItemMeta(fillerMeta);
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);

        ItemStack progress = new ItemStack(Material.EXPERIENCE_BOTTLE); ItemMeta progressMeta = progress.getItemMeta();
        progressMeta.setDisplayName(color("&d&lSERVERPASS &f• FREE"));
        progressMeta.setLore(List.of(
                color("&7Tier: &f" + currentTier + "&7/&f" + serverPassMaxTier()),
                color("&d" + "■".repeat(filled) + "&8" + "■".repeat(20 - filled)),
                color("&7XP: &f" + current + "&7/&f" + perTier),
                "",
                color("&aXP získavaš iba aktívnym hraním."),
                color("&7Ťažba • mobovia • rybolov • pohyb • eventy")));
        progress.setItemMeta(progressMeta); inventory.setItem(4, progress);

        int[] slots = {11, 20, 22, 24, 33};
        List<Integer> rewards = serverPassRewardTiers();
        for (int index = 0; index < rewards.size(); index++) {
            int tier = rewards.get(index);
            boolean claimed = pass.claimedTiers.contains(tier);
            boolean available = currentTier >= tier;
            ItemStack item = new ItemStack(claimed ? Material.LIME_DYE : available ? serverPassRewardMaterial(tier) : Material.GRAY_DYE);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(color("&fTier " + tier + " &8• " + serverPassRewardName(tier)));
            List<String> lore = new ArrayList<>();
            lore.add(color("&7Potrebné XP: &f" + tier * perTier));
            lore.add("");
            lore.add(color(claimed ? "&a✓ Vyzdvihnuté" : available ? "&e▶ Klikni pre vyzdvihnutie" : "&c✖ Zamknuté"));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(serverPassTierKey, PersistentDataType.INTEGER, tier);
            item.setItemMeta(meta); inventory.setItem(slots[index], item);
        }

        ItemStack settings = new ItemStack(Material.NETHER_STAR); ItemMeta settingsMeta = settings.getItemMeta();
        settingsMeta.setDisplayName(color("&dServerPass kozmetika"));
        settingsMeta.setLore(List.of(
                color("&7Starlight Trail: " + (pass.cosmeticUnlocked ? pass.cosmeticActive ? "&aZAP" : "&cVYP" : "&8zamknutý do Tier 10")),
                color("&7PASS MASTER Tag: " + (pass.tagUnlocked ? pass.tagActive ? "&aZAP" : "&cVYP" : "&8zamknutý do Tier 50")),
                "",
                color("&f/serverpass cosmetic"), color("&f/serverpass tag")));
        settings.setItemMeta(settingsMeta); inventory.setItem(49, settings);
        player.openInventory(inventory);
    }

    private void handleServerPassCommand(Player player, String[] args) {
        if (args.length == 0) { openServerPass(player); return; }
        ServerPassProfile pass = serverPassProfile(player);
        if (args[0].equalsIgnoreCase("cosmetic")) {
            if (!pass.cosmeticUnlocked) { message(player, "&cStarlight kozmetiku odomkneš na ServerPass Tier 10."); return; }
            pass.cosmeticActive = !pass.cosmeticActive; saveServerPassProfile(pass);
            message(player, "&dStarlight Trail: " + (pass.cosmeticActive ? "&aZAPNUTÝ" : "&cVYPNUTÝ"));
            return;
        }
        if (args[0].equalsIgnoreCase("tag")) {
            if (!pass.tagUnlocked) { message(player, "&cExkluzívny tag odomkneš na ServerPass Tier 50."); return; }
            pass.tagActive = !pass.tagActive; saveServerPassProfile(pass);
            message(player, "&d✦ PASS MASTER tag: " + (pass.tagActive ? "&aZAPNUTÝ" : "&cVYPNUTÝ"));
            return;
        }
        message(player, "&7Použi &f/serverpass&7, &f/serverpass cosmetic &7alebo &f/serverpass tag&7.");
    }

    private void claimServerPassReward(Player player, int tier) {
        if (!serverPassRewardTiers().contains(tier)) return;
        ServerPassProfile pass = serverPassProfile(player);
        if (serverPassTier(pass) < tier) { message(player, "&cTáto odmena je ešte zamknutá."); return; }
        if (!pass.claimedTiers.add(tier)) { message(player, "&7Túto odmenu si už vyzdvihol."); return; }
        switch (tier) {
            case 1 -> economy.depositPlayer(player, getConfig().getDouble("server-pass.rewards.1.money", 5000));
            case 5 -> giveCrateKey(player, "rare", 1);
            case 10 -> { pass.cosmeticUnlocked = true; pass.cosmeticActive = true; }
            case 25 -> giveCrateKey(player, "epic", 1);
            case 50 -> { pass.tagUnlocked = true; pass.tagActive = true; }
            default -> { return; }
        }
        saveServerPassProfile(pass);
        player.sendTitle(color("&a&lODMENA VYZDVIHNUTÁ"), color("&fTier " + tier + " &8• " + serverPassRewardName(tier)), 10, 50, 15);
        openServerPass(player);
    }

    private long dayId() { return LocalDate.now(ZoneId.systemDefault()).toEpochDay(); }
    private String weekId() {
        LocalDate now = LocalDate.now(ZoneId.systemDefault());
        return now.get(IsoFields.WEEK_BASED_YEAR) + "-" + now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    private List<String> dailyQuestIds() {
        ConfigurationSection section = getConfig().getConfigurationSection("daily-quests");
        if (section == null) return List.of();
        List<String> ids = new ArrayList<>(section.getKeys(false)); Collections.sort(ids);
        if (ids.size() <= 3) return ids;
        int start = Math.floorMod((int)dayId(), ids.size());
        return List.of(ids.get(start), ids.get((start + 1) % ids.size()), ids.get((start + 2) % ids.size()));
    }

    private void incrementQuest(Player player, String stat, long amount) {
        PlayerProfile p = profile(player);
        for (String id : dailyQuestIds()) updateQuest(player, p, "daily-quests", id, "D" + dayId(), stat, amount);
        ConfigurationSection weekly = getConfig().getConfigurationSection("weekly-quests");
        if (weekly != null) for (String id : weekly.getKeys(false)) updateQuest(player, p, "weekly-quests", id, "W" + weekId(), stat, amount);
    }

    private void updateQuest(Player player, PlayerProfile p, String root, String id, String period, String stat, long amount) {
        String path = root + "." + id;
        if (!stat.equalsIgnoreCase(getConfig().getString(path + ".stat", ""))) return;
        String key = period + "." + id;
        if (p.questClaims.contains(key)) return;
        long goal = getConfig().getLong(path + ".goal", 1);
        long progress = Math.min(goal, p.questProgress.getOrDefault(key, 0L) + amount);
        p.questProgress.put(key, progress);
        if (progress >= goal) {
            p.questClaims.add(key);
            giveReward(player, getConfig().getConfigurationSection(path + ".reward"));
            message(player, "&aQuest splnený: &f" + getConfig().getString(path + ".title", id));
        }
    }

    private void giveReward(Player player, ConfigurationSection reward) {
        if (reward == null) return;
        double money = reward.getDouble("money"); if (money > 0) economy.depositPlayer(player, money);
        long xp = reward.getLong("xp"); if (xp > 0) addXp(player, xp);
        long coins = reward.getLong("coins"); if (coins > 0) profile(player).coins += coins;
        for (String command : reward.getStringList("commands")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", player.getName()));
    }

    private void checkPlaytimeRewards(Player player) {
        PlayerProfile p = profile(player); long minutes = p.activeSeconds / 60;
        ConfigurationSection section = getConfig().getConfigurationSection("playtime-rewards");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            int threshold;
            try { threshold = Integer.parseInt(key); } catch (NumberFormatException e) { continue; }
            if (minutes >= threshold && p.playtimeClaims.add(threshold)) {
                giveReward(player, section.getConfigurationSection(key));
                message(player, "&aAktívna playtime odmena za &f" + threshold + " minút&a bola pripísaná.");
            }
        }
    }

    private void checkAchievements(Player player) {
        PlayerProfile p = profile(player);
        achieve(player, "first_blood", p.mobsKilled >= 1, "First Blood", 2);
        achieve(player, "miner_1", p.blocksMined >= 1000, "Miner I", 10);
        achieve(player, "miner_2", p.blocksMined >= 10000, "Miner II", 25);
        achieve(player, "fisherman", p.fishCaught >= 500, "Fisherman", 25);
        achieve(player, "veteran", p.activeSeconds >= 360000, "Veteran", 50);
        double balance = economy.getBalance(player);
        achieve(player, "rich", balance >= 100000, "Rich", 20);
        achieve(player, "millionaire", balance >= 1000000, "Millionaire", 100);
    }

    private void achieve(Player player, String id, boolean condition, String title, int coins) {
        PlayerProfile p = profile(player);
        if (condition && p.achievements.add(id)) {
            p.coins += coins; Bukkit.broadcastMessage(prefix() + color("&e" + player.getName() + " &7získal achievement &6" + title + " &8(+" + coins + " SkyCoins)"));
        }
    }

    private void startRandomEvent() {
        long interval = Math.max(15, getConfig().getLong("event-interval-minutes", 120)) * 60_000L;
        nextRandomEventAt = System.currentTimeMillis() + interval;
        List<String> events = getConfig().getString("server-mode", "survival").equalsIgnoreCase("skyblock")
                ? List.of("HARVEST_FRENZY", "FISHING_DERBY", "ISLAND_MINER")
                : List.of("BOUNTY_HUNT", "LUMBERJACK", "EXPLORER");
        startEvent(events.get(ThreadLocalRandom.current().nextInt(events.size())));
    }

    private void startEvent(String type) {
        if (activeEvent != null) endEvent();
        activeEvent = type.toUpperCase(Locale.ROOT); eventScores.clear();
        int minutes = getConfig().getInt("event-duration-minutes", 15);
        eventEndsAt = System.currentTimeMillis() + minutes * 60_000L;
        Bukkit.broadcastMessage(prefix() + color("&d&lEVENT &f" + eventDisplayName(activeEvent) + " &7začal! Trvá " + minutes + " minút."));
        eventTaskId = getServer().getScheduler().runTaskLater(this, this::endEvent, minutes * 1200L).getTaskId();
    }

    private void scoreEvent(Player player, String type, int score) {
        if (type.equals(activeEvent)) eventScores.merge(player.getUniqueId(), score, Integer::sum);
    }

    private void endEvent() {
        if (activeEvent == null) return;
        List<Map.Entry<UUID, Integer>> top = eventScores.entrySet().stream().sorted(Map.Entry.<UUID,Integer>comparingByValue().reversed()).limit(3).toList();
        int[] money = {7500, 4000, 2000}; int[] coins = {30, 15, 8};
        int[] passXp = {300, 200, 100};
        Bukkit.broadcastMessage(prefix() + color("&d&lEVENT &f" + eventDisplayName(activeEvent) + " &7skončil."));
        int participationScore = Math.max(1, getConfig().getInt("random-events.minimum-participation-score", 10));
        int participationXp = Math.max(0, getConfig().getInt("random-events.participation-pass-xp", 50));
        for (Map.Entry<UUID, Integer> entry : eventScores.entrySet()) {
            Player participant = Bukkit.getPlayer(entry.getKey());
            if (participant != null && entry.getValue() >= participationScore) addServerPassXp(participant, participationXp);
        }
        for (int i = 0; i < top.size(); i++) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(top.get(i).getKey());
            economy.depositPlayer(offline, money[i]); profile(offline.getUniqueId(), offline.getName()).coins += coins[i];
            Player online = offline.getPlayer();
            if (online != null) addServerPassXp(online, passXp[i]);
            Bukkit.broadcastMessage(color("&8#" + (i + 1) + " &e" + offline.getName() + " &7- " + top.get(i).getValue() + " bodov"));
        }
        activeEvent = null; eventScores.clear(); eventTaskId = -1; eventEndsAt = 0L;
    }

    private void showRandomEvent(Player player) {
        if (activeEvent == null) {
            long remaining = Math.max(0L, nextRandomEventAt - System.currentTimeMillis());
            message(player, "&dRandomEvents: &7ďalší event približne za &f" + remaining / 60_000L + " minút&7.");
            message(player, getConfig().getString("server-mode", "survival").equalsIgnoreCase("skyblock")
                    ? "&7SkyBlock eventy: &fŽatva, rybárske derby, ostrovná banská výzva"
                    : "&7Survival eventy: &fLov odmien, drevorubačská horúčka, prieskumnícka výprava");
            return;
        }
        int score = eventScores.getOrDefault(player.getUniqueId(), 0);
        long seconds = Math.max(0L, (eventEndsAt - System.currentTimeMillis()) / 1000L);
        message(player, "&d&lEVENT &f" + eventDisplayName(activeEvent) + " &8• &7zostáva &f" + seconds / 60 + "m " + seconds % 60 + "s");
        message(player, "&7Tvoje skóre: &e" + score + " &8• &7Top 3 aj aktívni účastníci získajú ServerPass XP.");
    }

    private String eventDisplayName(String event) {
        return switch (event) {
            case "BOUNTY_HUNT" -> "Lov odmien";
            case "LUMBERJACK" -> "Drevorubačská horúčka";
            case "EXPLORER" -> "Prieskumnícka výprava";
            case "HARVEST_FRENZY" -> "Žatva ostrovov";
            case "FISHING_DERBY" -> "Nebeské rybárske derby";
            case "ISLAND_MINER" -> "Ostrovná banská výzva";
            default -> event.replace('_', ' ');
        };
    }

    private String customItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(customItemKey, PersistentDataType.STRING);
    }

    private ItemStack customItem(String id) {
        Material material; String name; List<String> lore; Map<Enchantment,Integer> enchantments = new HashMap<>();
        switch (id) {
            case "miners_pickaxe" -> { material = Material.DIAMOND_PICKAXE; name = "&bMiner's Pickaxe"; lore = List.of("&7+5 % Mining XP"); enchantments.put(Enchantment.EFFICIENCY, 5); enchantments.put(Enchantment.UNBREAKING, 3); }
            case "farmers_hoe" -> { material = Material.DIAMOND_HOE; name = "&aFarmer's Hoe"; lore = List.of("&7+10 % Farming XP"); enchantments.put(Enchantment.UNBREAKING, 3); }
            case "fishermans_rod" -> { material = Material.FISHING_ROD; name = "&3Fisherman's Rod"; lore = List.of("&7+10 % Fishing XP", "&7EvenMoreFish rarity bonus nastav v jeho configu."); enchantments.put(Enchantment.LUCK_OF_THE_SEA, 3); enchantments.put(Enchantment.UNBREAKING, 3); }
            default -> { return null; }
        }
        ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta(); meta.setDisplayName(color(name));
        meta.setLore(lore.stream().map(this::color).toList()); meta.getPersistentDataContainer().set(customItemKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta); enchantments.forEach((enchant, level) -> item.addUnsafeEnchantment(enchant, level)); return item;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("skybitadmin")) return adminCommand(sender, args);
        if (name.equals("skybitvote")) return registerVoteCommand(sender, args);
        if (!(sender instanceof Player player)) { message(sender, "&cTento príkaz je iba pre hráča."); return true; }
        PlayerProfile p = profile(player);
        switch (name) {
            case "level" -> message(player, "&fLevel: &b" + p.level + "&7/&b" + getConfig().getInt("level.max", 100) + " &8| &fXP: &e" + p.xp + "&7/&e" + requiredXp(p.level));
            case "skycoins" -> message(player, "&fSkyCoins: &e" + p.coins + " 🪙");
            case "daily" -> claimDaily(player);
            case "quests" -> showQuests(player);
            case "coinshop" -> openCoinShop(player);
            case "ranks" -> openRankShop(player);
            case "buykey" -> buyKey(player, args);
            case "vote" -> showVote(player);
            case "voteparty" -> showVoteParty(player, true);
            case "minerush" -> showMineRush(player);
            case "serverpass" -> handleServerPassCommand(player, args);
            case "events" -> showRandomEvent(player);
            case "weblink" -> {
                String code = webBridge == null ? null : webBridge.createLinkCode(player);
                if (code == null) message(player, "&cWebové prepojenie zatiaľ nie je dostupné.");
                else {
                    message(player, "&fTvoj jednorazový web kód: &b&l" + code);
                    message(player, "&7Platí 10 minút. Na webe zadaj svoje meno a tento kód. &cNikdy nezadávaj herné heslo.");
                }
            }
            case "tag" -> setTag(player, args);
            default -> { return false; }
        }
        return true;
    }

    private void claimDaily(Player player) {
        PlayerProfile p = profile(player); long today = dayId();
        if (p.lastDailyEpoch == today) { message(player, "&cDnešnú odmenu si už vyzdvihol."); return; }
        p.dailyStreak = p.lastDailyEpoch == today - 1 ? Math.min(7, p.dailyStreak + 1) : 1;
        p.lastDailyEpoch = today;
        giveReward(player, getConfig().getConfigurationSection("daily-streak." + p.dailyStreak));
        message(player, "&aDaily odmena vyzdvihnutá. Streak: &e" + p.dailyStreak + "/7&a.");
    }

    private void showQuests(Player player) {
        PlayerProfile p = profile(player); player.sendMessage(color("&b&lDENNÉ QUESTY"));
        for (String id : dailyQuestIds()) showQuestLine(player, p, "daily-quests", id, "D" + dayId());
        player.sendMessage(color("&d&lTÝŽDENNÉ QUESTY"));
        ConfigurationSection weekly = getConfig().getConfigurationSection("weekly-quests");
        if (weekly != null) for (String id : weekly.getKeys(false)) showQuestLine(player, p, "weekly-quests", id, "W" + weekId());
    }

    private void showQuestLine(Player player, PlayerProfile p, String root, String id, String period) {
        String key = period + "." + id; long goal = getConfig().getLong(root + "." + id + ".goal");
        long progress = p.questProgress.getOrDefault(key, 0L); String done = p.questClaims.contains(key) ? " &a✓" : "";
        player.sendMessage(color("&8• &f" + getConfig().getString(root + "." + id + ".title", id) + " &7(" + progress + "/" + goal + ")" + done));
    }

    private void openCoinShop(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, color("&0SkyCoin Shop 🪙"));
        ConfigurationSection shop = getConfig().getConfigurationSection("coinshop");
        if (shop != null) for (String id : shop.getKeys(false)) {
            String path = "coinshop." + id; Material material = Material.matchMaterial(getConfig().getString(path + ".material", "PAPER"));
            if (material == null) material = Material.PAPER;
            ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta(); meta.setDisplayName(color(getConfig().getString(path + ".name", id)));
            boolean unlocked = profile(player).unlocks.contains(id); int cost = getConfig().getInt(path + ".cost");
            meta.setLore(List.of(color(unlocked ? "&aOdomknuté - klikni pre aktiváciu" : "&7Cena: &e" + cost + " SkyCoins"), color("&8ID: " + id)));
            meta.getPersistentDataContainer().set(new NamespacedKey(this, "shop_id"), PersistentDataType.STRING, id); item.setItemMeta(meta);
            inventory.setItem(getConfig().getInt(path + ".slot"), item);
        }
        player.openInventory(inventory);
    }

    private void openRankShop(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, color("&0SkyBit VIP Shop ✦"));
        ConfigurationSection shop = getConfig().getConfigurationSection("rankshop");
        if (shop != null) for (String id : shop.getKeys(false)) {
            String path = "rankshop." + id;
            Material material = Material.matchMaterial(getConfig().getString(path + ".material", "NETHER_STAR"));
            if (material == null) material = Material.NETHER_STAR;
            ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(color(getConfig().getString(path + ".name", id)));
            String rank = getConfig().getString(path + ".value", id);
            int cost = getConfig().getInt(path + ".cost");
            boolean active = player.hasPermission("group." + rank.toLowerCase(Locale.ROOT));
            boolean staff = player.hasPermission("skybit.admin");
            List<String> lore = new ArrayList<>();
            for (String line : getConfig().getStringList(path + ".lore")) lore.add(color(line));
            lore.add("");
            lore.add(color("&7Cena: &e&l" + String.format(Locale.US, "%,d", cost).replace(',', ' ') + " SkyCoins"));
            if (staff) lore.add(color("&cStaff hodnosť zostáva zachovaná"));
            else if (active) lore.add(color("&a✓ Hodnosť už máš získanú"));
            else lore.add(color("&e▶ Klikni pre zakúpenie"));
            lore.add(color("&8Tvoje SkyCoins: &f" + String.format(Locale.US, "%,d", profile(player).coins).replace(',', ' ')));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(new NamespacedKey(this, "shop_id"), PersistentDataType.STRING, id);
            item.setItemMeta(meta); inventory.setItem(getConfig().getInt(path + ".slot"), item);
        }
        player.openInventory(inventory);
    }

    private int rankTier(String rank) {
        return switch (rank.toLowerCase(Locale.ROOT)) {
            case "vip" -> 1;
            case "knight" -> 2;
            case "baron" -> 3;
            case "king" -> 4;
            case "emperor" -> 5;
            default -> 0;
        };
    }

    private int currentRankTier(Player player) {
        if (player.hasPermission("group.emperor")) return 5;
        if (player.hasPermission("group.king")) return 4;
        if (player.hasPermission("group.baron")) return 3;
        if (player.hasPermission("group.knight")) return 2;
        if (player.hasPermission("group.vip")) return 1;
        return 0;
    }

    private void buyKey(Player player, String[] args) {
        if (args.length == 0) { message(player, "&7Použi &f/buykey <basic|vote|rare|epic|legendary>&7."); return; }
        String tier = args[0].toLowerCase(Locale.ROOT);
        if (!List.of("basic", "vote", "rare", "epic", "legendary").contains(tier)) {
            message(player, "&cNeznámy typ kľúča."); return;
        }
        int cost = getConfig().getInt("keyshop." + tier, -1);
        if (cost < 0) { message(player, "&cTento kľúč sa momentálne nepredáva."); return; }
        PlayerProfile profile = profile(player);
        if (profile.coins < cost) { message(player, "&cNemáš dosť SkyCoins. Potrebuješ &e" + cost + "&c."); return; }
        profile.coins -= cost;
        giveCrateKey(player, tier, 1);
        message(player, "&aKúpil si &f" + tier.toUpperCase(Locale.ROOT) + " Key &aza &e" + cost + " SkyCoins&a.");
    }

    private void giveCrateKey(Player player, String tier, int amount) {
        String command = Bukkit.getPluginManager().isPluginEnabled("ExcellentCrates")
                ? "excellentcrates:crates key give " + player.getName() + " " + tier + " " + amount + " -sf"
                : "skybitkey give " + player.getName() + " " + tier + " " + amount;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private File networkRankFile(UUID uuid) {
        File directory = new File(getDataFolder(), getConfig().getString("network-rank-directory", "../../../../shared-data/ranks"));
        if (!directory.exists() && !directory.mkdirs()) getLogger().warning("Network rank directory could not be created: " + directory);
        return new File(directory, uuid + ".txt");
    }

    private void saveNetworkRank(Player player, String rank) {
        try {
            Files.writeString(networkRankFile(player.getUniqueId()).toPath(), rank.toLowerCase(Locale.ROOT), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            getLogger().severe("Network rank could not be saved for " + player.getName() + ": " + exception.getMessage());
        }
    }

    private void syncNetworkRank(Player player) {
        if (!player.isOnline() || player.hasPermission("group.owner") || player.hasPermission("group.admin") || player.hasPermission("group.helper")) return;
        File file = networkRankFile(player.getUniqueId());
        if (!file.isFile()) return;
        try {
            String rank = Files.readString(file.toPath(), StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
            if (rankTier(rank) > 0 && !player.hasPermission("group." + rank))
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + player.getName() + " parent set " + rank);
        } catch (IOException exception) {
            getLogger().warning("Network rank could not be read for " + player.getName() + ": " + exception.getMessage());
        }
    }

    @EventHandler
    public void onServerPassClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(color("&0SkyBit ServerPass • FREE"))) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        Integer tier = item.getItemMeta().getPersistentDataContainer().get(serverPassTierKey, PersistentDataType.INTEGER);
        if (tier != null) claimServerPassReward(player, tier);
    }

    @EventHandler(ignoreCancelled = true)
    public void onShopClick(InventoryClickEvent event) {
        boolean rankShop = event.getView().getTitle().equals(color("&0SkyBit VIP Shop ✦"));
        if (!rankShop && !event.getView().getTitle().equals(color("&0SkyCoin Shop 🪙"))) return;
        event.setCancelled(true); if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack item = event.getCurrentItem(); if (item == null || !item.hasItemMeta()) return;
        String id = item.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(this, "shop_id"), PersistentDataType.STRING);
        String root = rankShop ? "rankshop" : "coinshop";
        if (id == null || !getConfig().contains(root + "." + id)) return;
        PlayerProfile p = profile(player); int cost = getConfig().getInt(root + "." + id + ".cost");
        String kind = getConfig().getString(root + "." + id + ".kind", "");
        String value = getConfig().getString(root + "." + id + ".value", "");
        if (kind.equalsIgnoreCase("RANK")) {
            if (player.hasPermission("skybit.admin")) { message(player, "&cTvoja staff hodnosť zostala zachovaná; VIP ju nemôže prepísať."); return; }
            int targetTier = rankTier(value);
            if (targetTier <= currentRankTier(player)) { message(player, "&aTúto alebo vyššiu hodnosť už máš."); return; }
            if (p.coins < cost) { message(player, "&cNemáš dosť SkyCoins. Potrebuješ &e" + cost + "&c."); return; }
            p.coins -= cost; p.unlocks.add("rank:" + value.toLowerCase(Locale.ROOT));
            saveNetworkRank(player, value);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + player.getName() + " parent set " + value.toLowerCase(Locale.ROOT));
            player.closeInventory();
            message(player, "&aOdomkol si hodnosť " + getConfig().getString(root + "." + id + ".name", value) + "&a za &e" + cost + " SkyCoins&a.");
            return;
        }
        if (!p.unlocks.contains(id)) {
            if (p.coins < cost) { message(player, "&cNemáš dosť SkyCoins."); return; }
            p.coins -= cost; p.unlocks.add(id); message(player, "&aKozmetika bola odomknutá.");
        }
        if (kind.equalsIgnoreCase("TAG")) {
            p.activeTag = value;
            ServerPassProfile pass = serverPassProfile(player); pass.tagActive = false; saveServerPassProfile(pass);
        }
        if (kind.equalsIgnoreCase("TRAIL")) p.activeTrail = value;
        player.closeInventory(); message(player, "&aAktívna kozmetika: &f" + value);
    }

    private void setTag(Player player, String[] args) {
        if (args.length == 0) { message(player, "&7Použi &f/tag off&7 alebo si tag aktivuj cez /coinshop."); return; }
        if (args[0].equalsIgnoreCase("off")) {
            profile(player).activeTag = "";
            ServerPassProfile pass = serverPassProfile(player); pass.tagActive = false; saveServerPassProfile(pass);
            message(player, "&aTag vypnutý."); return;
        }
        message(player, "&7Tagy sa odomykajú a aktivujú cez &f/coinshop&7.");
    }

    private long mineRushPeriodId() {
        long hours = Math.max(1L, getConfig().getLong("mine-rush.period-hours", 12L));
        return System.currentTimeMillis() / (hours * 3_600_000L);
    }

    private void ensureMineRushPeriod(PlayerProfile profile) {
        long current = mineRushPeriodId();
        if (profile.mineRushPeriod == current) return;
        profile.mineRushPeriod = current;
        profile.mineRushBlocks = 0L;
        profile.mineRushClaims.clear();
    }

    private void incrementMineRush(Player player, PlayerProfile profile) {
        ensureMineRushPeriod(profile);
        int goal = Math.max(1, getConfig().getInt("mine-rush.goal", 5000));
        if (profile.mineRushBlocks >= goal) return;
        profile.mineRushBlocks++;
        ConfigurationSection rewards = getConfig().getConfigurationSection("mine-rush.rewards");
        if (rewards == null) return;
        List<Integer> milestones = rewards.getKeys(false).stream().map(value -> {
            try { return Integer.parseInt(value); } catch (NumberFormatException exception) { return -1; }
        }).filter(value -> value > 0).sorted().toList();
        for (int milestone : milestones) {
            if (profile.mineRushBlocks < milestone || !profile.mineRushClaims.add(milestone)) continue;
            for (String reward : getConfig().getStringList("mine-rush.rewards." + milestone + ".commands"))
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), reward.replace("{player}", player.getName()));
            String message = getConfig().getString("mine-rush.rewards." + milestone + ".message", "Odmena za " + milestone + " blokov");
            player.sendTitle(color(milestone >= goal ? "&6&lMINE RUSH HOTOVÝ!" : "&b&lMINE RUSH"), color("&f" + message), 10, 50, 15);
        }
    }

    private void showMineRush(Player player) {
        PlayerProfile profile = profile(player);
        ensureMineRushPeriod(profile);
        int goal = Math.max(1, getConfig().getInt("mine-rush.goal", 5000));
        int percent = (int) Math.min(100, profile.mineRushBlocks * 100 / goal);
        int filled = percent / 10;
        String bar = "&b" + "■".repeat(filled) + "&8" + "■".repeat(10 - filled);
        message(player, "&b&lMINE RUSH &8[" + bar + "&8] &f" + profile.mineRushBlocks + "&7/&f" + goal);
        message(player, "&7Reset za: &e" + mineRushRemaining() + " &8• &7Odmeny: &f500, 1 500, 3 000 a 5 000 blokov");
    }

    long mineRushBlocks(OfflinePlayer player) {
        if (player == null) return 0L;
        PlayerProfile profile = profile(player.getUniqueId(), player.getName());
        ensureMineRushPeriod(profile);
        return profile.mineRushBlocks;
    }

    int mineRushGoal() { return Math.max(1, getConfig().getInt("mine-rush.goal", 5000)); }

    String mineRushRemaining() {
        long duration = Math.max(1L, getConfig().getLong("mine-rush.period-hours", 12L)) * 3_600_000L;
        long remaining = duration - (System.currentTimeMillis() % duration);
        long hours = remaining / 3_600_000L;
        long minutes = (remaining % 3_600_000L) / 60_000L;
        return hours + "h " + minutes + "m";
    }

    private YamlConfiguration loadVoteData() {
        return voteDataFile == null ? new YamlConfiguration() : YamlConfiguration.loadConfiguration(voteDataFile);
    }

    private void saveVoteData(YamlConfiguration votes) {
        try { votes.save(voteDataFile); }
        catch (IOException exception) { getLogger().severe("Cannot save votes.yml: " + exception.getMessage()); }
    }

    private void showVote(Player player) {
        showVoteParty(player, true);
        ConfigurationSection sites = getConfig().getConfigurationSection("vote.sites");
        if (sites == null || sites.getKeys(false).isEmpty()) {
            message(player, "&7Hlasovacie odkazy zatiaľ nie sú nastavené.");
            return;
        }
        player.sendMessage(color("&b&lHLASUJ ZA SKYBIT &8• &7klikni na odkaz"));
        for (String id : sites.getKeys(false)) {
            String base = "vote.sites." + id;
            String label = getConfig().getString(base + ".name", id);
            String url = getConfig().getString(base + ".url", "").trim();
            if (url.isEmpty()) continue;
            Component link = Component.text("  ▶ " + label, NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.openUrl(url))
                    .hoverEvent(HoverEvent.showText(Component.text("Otvoriť hlasovaciu stránku", NamedTextColor.YELLOW)));
            player.sendMessage(link);
        }
        message(player, "&7Odmena za každý platný hlas: &e" + getConfig().getInt("vote.reward.coins", 20)
                + " SkyCoins&7, &a$" + getConfig().getInt("vote.reward.money", 500) + " &7a &aVote Key&7.");
    }

    private void showVoteParty(Player player, boolean detailed) {
        YamlConfiguration votes = loadVoteData();
        int target = Math.max(1, getConfig().getInt("vote.party-target", 10));
        int progress = votes.getInt("progress", 0);
        String bar = "&a" + "■".repeat(Math.min(10, progress * 10 / target))
                + "&8" + "■".repeat(Math.max(0, 10 - progress * 10 / target));
        message(player, "&dVote Party &8[" + bar + "&8] &f" + progress + "&7/&f" + target);
        if (detailed) message(player, "&7Po naplnení dostanú všetci online hráči SkyCoins, peniaze, XP a Rare Key.");
    }

    private boolean registerVoteCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("skybit.vote.admin")) { message(sender, "&cNemáš oprávnenie."); return true; }
        if (args.length == 0) { message(sender, "&7Použi &f/skybitvote <hráč> [stránka]&7."); return true; }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) { message(sender, "&cHráč musí byť online na tomto hernom serveri."); return true; }
        String site = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_") : "manual";
        YamlConfiguration votes = loadVoteData();
        String lastPath = "last-votes." + target.getUniqueId() + "." + site;
        if (votes.getLong(lastPath, -1L) == dayId()) {
            message(sender, "&cTento hráč už dnes hlasoval na stránke " + site + "."); return true;
        }
        votes.set(lastPath, dayId());
        int progress = votes.getInt("progress", 0) + 1;
        int targetVotes = Math.max(1, getConfig().getInt("vote.party-target", 10));
        profile(target).coins += getConfig().getInt("vote.reward.coins", 20);
        economy.depositPlayer(target, getConfig().getDouble("vote.reward.money", 500));
        addXp(target, getConfig().getLong("vote.reward.xp", 50));
        giveCrateKey(target, "vote", 1);
        Bukkit.broadcastMessage(color("&8[&dVote&8] &f" + target.getName() + " &7hlasoval za SkyBit! &d(" + Math.min(progress, targetVotes) + "/" + targetVotes + ")"));
        if (progress >= targetVotes) {
            progress = 0;
            long partyId = votes.getLong("party-id", 0L) + 1L;
            votes.set("party-id", partyId);
            votes.set("progress", progress);
            saveVoteData(votes);
            observedVotePartyId = partyId;
            rewardVoteParty(partyId);
        } else {
            votes.set("progress", progress);
            saveVoteData(votes);
        }
        saveProfiles();
        message(sender, "&aHlas a odmeny boli zapísané pre " + target.getName() + ".");
        return true;
    }

    private void syncVoteParty() {
        YamlConfiguration votes = loadVoteData();
        long partyId = votes.getLong("party-id", 0L);
        if (partyId <= observedVotePartyId) return;
        observedVotePartyId = partyId;
        rewardVoteParty(partyId);
    }

    private void rewardVoteParty(long partyId) {
        YamlConfiguration votes = loadVoteData();
        int coins = getConfig().getInt("vote.party-reward.coins", 50);
        double money = getConfig().getDouble("vote.party-reward.money", 1000);
        long xp = getConfig().getLong("vote.party-reward.xp", 100);
        for (Player player : Bukkit.getOnlinePlayers()) {
            String claim = "party-claims." + partyId + "." + player.getUniqueId();
            if (votes.getBoolean(claim, false)) continue;
            votes.set(claim, true);
            profile(player).coins += coins;
            economy.depositPlayer(player, money);
            addXp(player, xp);
            giveCrateKey(player, "rare", 1);
            player.sendTitle(color("&d&lVOTE PARTY!"), color("&f+" + coins + " SkyCoins &8• &a$" + (int) money + " &8• &3Rare Key"), 10, 60, 15);
        }
        saveVoteData(votes);
        saveProfiles();
        Bukkit.broadcastMessage(color("&8[&dVote Party&8] &aCieľ bol splnený! &fVšetci online hráči dostali odmenu."));
        spawnVotePartyLlama();
    }

    private void spawnVotePartyLlama() {
        if (Bukkit.getOnlinePlayers().isEmpty()) return;
        World world = Bukkit.getWorld(getConfig().getString("vote.llama.world", "world"));
        if (world == null) return;
        Location location = new Location(world,
                getConfig().getDouble("vote.llama.x", 28.5),
                getConfig().getDouble("vote.llama.y", 150.0),
                getConfig().getDouble("vote.llama.z", -29.5));
        world.getEntitiesByClass(Llama.class).stream()
                .filter(entity -> entity.getScoreboardTags().contains("skybit_vote_llama"))
                .forEach(Llama::remove);
        Llama llama = world.spawn(location, Llama.class);
        llama.addScoreboardTag("skybit_vote_llama");
        llama.setCustomName(color("&d&lVOTE PARTY LAMA &7• &fudri ju ako posledný!"));
        llama.setCustomNameVisible(true);
        llama.setGlowing(true);
        llama.setAI(false);
        llama.setPersistent(false);
        int seconds = Math.max(15, getConfig().getInt("vote.llama.duration-seconds", 60));
        Bukkit.broadcastMessage(color("&8[&dVote Party&8] &fNa Trading Poste sa objavila &dVote Lama&f! Posledný zásah o &e" + seconds + " sekúnd &fzíska bonus."));
        getServer().getScheduler().runTaskLater(this, () -> finishVoteLlama(llama.getUniqueId()), seconds * 20L);
    }

    @EventHandler
    public void onVoteLlamaHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Llama llama) || !llama.getScoreboardTags().contains("skybit_vote_llama")) return;
        event.setCancelled(true);
        Player hitter = event.getDamager() instanceof Player player ? player
                : event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player ? player : null;
        if (hitter == null) return;
        voteLlamaLastHit.put(llama.getUniqueId(), hitter.getUniqueId());
        hitter.sendActionBar(Component.text("Teraz máš posledný zásah na Vote Lame!", NamedTextColor.LIGHT_PURPLE));
    }

    private void finishVoteLlama(UUID llamaId) {
        org.bukkit.entity.Entity entity = Bukkit.getEntity(llamaId);
        UUID winnerId = voteLlamaLastHit.remove(llamaId);
        if (entity != null) entity.remove();
        Player winner = winnerId == null ? null : Bukkit.getPlayer(winnerId);
        if (winner == null) {
            Bukkit.broadcastMessage(color("&8[&dVote Party&8] &7Vote Lama odišla bez víťaza."));
            return;
        }
        int coins = getConfig().getInt("vote.llama.reward.coins", 100);
        double money = getConfig().getDouble("vote.llama.reward.money", 5000);
        long xp = getConfig().getLong("vote.llama.reward.xp", 250);
        profile(winner).coins += coins;
        economy.depositPlayer(winner, money);
        addXp(winner, xp);
        giveCrateKey(winner, "legendary", 1);
        winner.sendTitle(color("&d&lPOSLEDNÝ ZÁSAH!"), color("&f+" + coins + " SkyCoins &8• &a$" + (int) money + " &8• &6Legendary Key"), 10, 60, 15);
        Bukkit.broadcastMessage(color("&8[&dVote Party&8] &f" + winner.getName() + " &azískal posledný zásah na Vote Lame!"));
        saveProfiles();
    }

    private boolean adminCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("skybit.admin")) { message(sender, "&cNemáš oprávnenie."); return true; }
        if (args.length == 0) { message(sender, "&7/skybitadmin <addxp|coins|giveitem|event|save|reload>"); return true; }
        if (args[0].equalsIgnoreCase("save")) { saveProfiles(); message(sender, "&aDáta uložené."); return true; }
        if (args[0].equalsIgnoreCase("reload")) { reloadConfig(); message(sender, "&aConfig načítaný."); return true; }
        if (args[0].equalsIgnoreCase("event") && args.length >= 2) { startEvent(args[1]); return true; }
        if (args.length < 3) { message(sender, "&cChýbajú argumenty."); return true; }
        Player target = Bukkit.getPlayerExact(args[1]); if (target == null) { message(sender, "&cHráč nie je online."); return true; }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "addxp" -> addXp(target, Long.parseLong(args[2]));
                case "coins" -> profile(target).coins += Long.parseLong(args[2]);
                case "giveitem" -> { ItemStack item = customItem(args[2].toLowerCase(Locale.ROOT)); if (item == null) throw new IllegalArgumentException(); target.getInventory().addItem(item); }
                default -> { message(sender, "&cNeznámy subcommand."); return true; }
            }
        } catch (IllegalArgumentException e) { message(sender, "&cNeplatná hodnota alebo item."); return true; }
        message(sender, "&aHotovo pre " + target.getName() + "."); return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("skybitvote")) {
            if (args.length == 1) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            if (args.length == 2) return getConfig().getConfigurationSection("vote.sites") == null ? List.of("manual") : new ArrayList<>(getConfig().getConfigurationSection("vote.sites").getKeys(false));
            return List.of();
        }
        if (!command.getName().equalsIgnoreCase("skybitadmin")) return List.of();
        if (args.length == 1) return List.of("addxp", "coins", "giveitem", "event", "save", "reload");
        if (args.length == 2 && List.of("addxp", "coins", "giveitem").contains(args[0].toLowerCase(Locale.ROOT))) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("event")) return getConfig().getString("server-mode", "survival").equalsIgnoreCase("skyblock")
                ? List.of("HARVEST_FRENZY", "FISHING_DERBY", "ISLAND_MINER")
                : List.of("BOUNTY_HUNT", "LUMBERJACK", "EXPLORER");
        if (args.length == 3 && args[0].equalsIgnoreCase("giveitem")) return List.of("miners_pickaxe", "farmers_hoe", "fishermans_rod");
        return List.of();
    }
}
