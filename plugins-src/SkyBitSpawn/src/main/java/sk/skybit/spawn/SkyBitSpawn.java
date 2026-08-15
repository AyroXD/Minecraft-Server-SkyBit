package sk.skybit.spawn;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import me.clip.placeholderapi.PlaceholderAPI;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.LookClose;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

public final class SkyBitSpawn extends JavaPlugin implements Listener {
    private static final String SKYBLOCK_MENU_TITLE = "§0☁ SKYBIT • SKYBLOCK";
    private static final String SURVIVAL_MENU_TITLE = "§0☁ SKYBIT • SURVIVAL";
    private static final String SERVER_MENU_TITLE = "§0☁ SKYBIT • SERVERS";
    private static final String TUTORIAL_TITLE = "§0☁ SKYBIT • TUTORIAL";
    private static final String CRATES_TITLE = "§0☁ SKYBIT • CRATES";
    private static final String KEY_SHOP_TITLE = "§0☁ SKYBIT • KEY SHOP";
    private static final String WARPS_TITLE = "§0☁ SKYBIT • PLAYER WARPS";
    private static final String STORY_TITLE = "§0☁ SKYBIT • PRÍBEH";
    private NamespacedKey revisionKey;
    private NamespacedKey actionKey;
    private NamespacedKey tutorialKey;
    private NamespacedKey crateKey;
    private final Map<UUID, Long> rtpCooldowns = new HashMap<>();
    private final Set<UUID> activeNpcs = new HashSet<>();
    private final Map<String, String> crateStations = new HashMap<>();
    private File warpsFile;
    private YamlConfiguration warps;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        revisionKey = new NamespacedKey(this, "spawn_revision");
        actionKey = new NamespacedKey(this, "menu_action");
        tutorialKey = new NamespacedKey(this, "tutorial_seen");
        crateKey = new NamespacedKey(this, "crate_key");
        warpsFile = new File(getDataFolder(), "warps.yml");
        warps = YamlConfiguration.loadConfiguration(warpsFile);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        Bukkit.getScheduler().runTaskLater(this, this::prepareSpawn, 20L);
        Bukkit.getScheduler().runTaskLater(this, this::initializeRoles, 80L);
    }

    private void prepareSpawn() {
        World world = Bukkit.getWorld(getConfig().getString("world", "world"));
        if (world == null) {
            getLogger().severe("Configured spawn world is not loaded.");
            return;
        }
        world.setSpawnLocation(spawn(world));
        world.setGameRule(GameRule.SPAWN_RADIUS, 0);
        refreshDisplay(world);
        refreshCitizensNpcs(world);
        refreshCrateStations(world);
        world.getLivingEntities().stream()
                .filter(entity -> !(entity instanceof Player))
                .filter(entity -> protectedLocation(entity.getLocation()))
                .filter(entity -> !entity.getScoreboardTags().contains("skybit_spawn_npc"))
                .forEach(LivingEntity::remove);
        getLogger().info("Trading Post ready in " + mode() + " mode at " + compact(spawn(world)) + ".");
    }

    private void initializeRoles() {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) return;
        String[][] commands = {
                {"lp creategroup vip"}, {"lp creategroup knight"}, {"lp creategroup baron"}, {"lp creategroup king"}, {"lp creategroup emperor"},
                {"lp creategroup helper"}, {"lp creategroup admin"}, {"lp creategroup owner"},
                {"lp group default meta setprefix 0 &8[&7HRÁČ&8]&7"},
                {"lp group vip parent set default"}, {"lp group vip setweight 20"}, {"lp group vip meta setprefix 20 &8[&6&lVIP&8]&6"},
                {"lp group knight parent set vip"}, {"lp group knight setweight 25"}, {"lp group knight meta setprefix 25 &8[&f&lKNIGHT&8]&f"},
                {"lp group baron parent set knight"}, {"lp group baron setweight 30"}, {"lp group baron meta setprefix 30 &8[&d&lBARON&8]&d"},
                {"lp group king parent set baron"}, {"lp group king setweight 40"}, {"lp group king meta setprefix 40 &8[&e&lKING&8]&e"},
                {"lp group emperor parent set king"}, {"lp group emperor setweight 45"}, {"lp group emperor meta setprefix 45 &8[&5&lEMPEROR&8]&5"},
                {"lp group helper parent set default"}, {"lp group helper setweight 50"}, {"lp group helper meta setprefix 50 &8[&3&lHELPER&8]&3"},
                {"lp group admin parent set helper"}, {"lp group admin setweight 80"}, {"lp group admin meta setprefix 80 &8[&c&lADMIN&8]&c"},
                {"lp group owner parent set admin"}, {"lp group owner setweight 100"}, {"lp group owner meta setprefix 100 &8[&4&lOWNER&8]&4"},
                {"lp group default permission set essentials.kit true"},
                {"lp group default permission set essentials.kits.starter true"},
                {"lp group default permission set essentials.kits.daily true"},
                {"lp group default permission set essentials.sethome true"},
                {"lp group default permission set essentials.home true"},
                {"lp group default permission set essentials.delhome true"},
                {"lp group default permission set essentials.sethome.multiple true"},
                {"lp group default permission set essentials.spawn true"},
                {"lp group vip permission set essentials.kits.vip true"},
                {"lp group vip permission set essentials.sethome.multiple.vip true"},
                {"lp group vip permission set essentials.nick.color true"},
                {"lp group vip permission set essentials.hat true"},
                {"lp group knight permission set essentials.kits.knight true"},
                {"lp group knight permission set essentials.sethome.multiple.knight true"},
                {"lp group knight permission set essentials.workbench true"},
                {"lp group baron permission set essentials.kits.baron true"},
                {"lp group baron permission set essentials.sethome.multiple.baron true"},
                {"lp group baron permission set essentials.workbench true"},
                {"lp group baron permission set essentials.enderchest true"},
                {"lp group king permission set essentials.kits.king true"},
                {"lp group king permission set essentials.sethome.multiple.king true"},
                {"lp group king permission set essentials.feed true"},
                {"lp group king permission set essentials.repair true"},
                {"lp group king permission set essentials.repair.hand true"},
                {"lp group king permission set essentials.fly true"},
                {"lp group emperor permission set essentials.kits.emperor true"},
                {"lp group emperor permission set essentials.sethome.multiple.emperor true"},
                {"lp group emperor permission set essentials.heal true"},
                {"lp group helper permission set essentials.kick true"},
                {"lp group helper permission set essentials.mute true"},
                {"lp group helper permission set coreprotect.inspect true"},
                {"lp group admin permission set essentials.ban true"},
                {"lp group admin permission set essentials.unban true"},
                {"lp group admin permission set skybitspawn.build true"},
                {"lp group owner permission set * true"},
                {"lp user Ayro parent set owner"}
        };
        for (String[] entry : commands) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), entry[0]);
        getLogger().info("LuckPerms roles and SkyBit permissions synchronized.");
    }

    private String mode() {
        return getConfig().getString("mode", "survival").toLowerCase(Locale.ROOT);
    }

    private Location spawn(World world) {
        return new Location(world,
                getConfig().getDouble("spawn.x", 28.5),
                getConfig().getDouble("spawn.y", 149.0),
                getConfig().getDouble("spawn.z", -29.5),
                (float) getConfig().getDouble("spawn.yaw", 180.0),
                (float) getConfig().getDouble("spawn.pitch", 0.0));
    }

    private String compact(Location location) {
        return location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    private void refreshDisplay(World world) {
        world.getEntitiesByClass(TextDisplay.class).stream()
                .filter(entity -> entity.getScoreboardTags().contains("skybit_trading_post"))
                .forEach(TextDisplay::remove);
        if (!getConfig().getBoolean("welcome-display", true)) return;

        String title = mode().equals("skyblock") ? "§d§lSKYBIT SKYBLOCK" : "§b§lSKYBIT SURVIVAL";
        String subtitle = mode().equals("skyblock")
                ? "§fTRADING POST\n§7Ostrovy • Výzvy • Rebríčky\n§eOtvor menu: §f/menu"
                : "§fTRADING POST\n§7Obchod • Aukcie • Slimefun\n§ePreskúmaj trhovisko";
        TextDisplay display = world.spawn(spawn(world).add(0, 3.4, 0), TextDisplay.class);
        display.setText(title + "\n" + subtitle);
        display.setBillboard(Display.Billboard.CENTER);
        display.setSeeThrough(true);
        display.setShadowed(true);
        display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        display.setViewRange(36.0f);
        display.setPersistent(true);
        display.addScoreboardTag("skybit_trading_post");
    }

    private void refreshNpcs(World world) {
        activeNpcs.clear();
        Location center = spawn(world).add(0, 1.0, 0);
        List<Location> locations = List.of(
                center.clone().add(-4, 0, 3), center.clone().add(4, 0, 3),
                center.clone().add(-4, 0, -3), center.clone().add(4, 0, -3),
                center.clone().add(-8, 0, 7), center.clone().add(8, 0, 7),
                center.clone().add(-8, 0, -7), center.clone().add(8, 0, -7));
        for (Location location : locations) world.getChunkAt(location).load();
        world.getEntitiesByClass(Villager.class).stream()
                .filter(entity -> entity.getScoreboardTags().contains("skybit_spawn_npc"))
                .forEach(Villager::remove);
        spawnNpc(locations.get(0), "§b§lSPRIEVODCA §7• Tutorial", Villager.Profession.LIBRARIAN, "tutorial");
        spawnNpc(locations.get(1), "§6§lCRATE MASTER §7• 5 Crates", Villager.Profession.TOOLSMITH, "crates");
        spawnNpc(locations.get(2), "§d§lWARP MASTER §7• Player warpy", Villager.Profession.CARTOGRAPHER, "warps");
        String name = mode().equals("skyblock") ? "§a§lISLAND MASTER §7• Vytvor ostrov" : "§e§lMENU MASTER §7• Hráčske menu";
        spawnNpc(locations.get(3), name, Villager.Profession.FARMER, "player_menu");
        spawnNpc(locations.get(4), "§b§lBANSKÝ MAJSTER §7• Mine Rush", Villager.Profession.ARMORER, "minerush");
        spawnNpc(locations.get(5), "§5§lKRONIKÁR §7• Príbeh SkyBit", Villager.Profession.LIBRARIAN, "story");
        spawnNpc(locations.get(6), "§d§lHLASATEĽ §7• Vote Party", Villager.Profession.CLERIC, "vote");
        spawnNpc(locations.get(7), "§a§lSPRÁVCA POZEMKOV §7• /res", Villager.Profession.MASON, "residence");
    }

    private List<Location> npcLocations(World world) {
        Location center = spawn(world).add(0, 1.0, 0);
        return List.of(
                center.clone().add(-4, 0, 3), center.clone().add(4, 0, 3),
                center.clone().add(-4, 0, -3), center.clone().add(4, 0, -3),
                center.clone().add(-8, 0, 7), center.clone().add(8, 0, 7),
                center.clone().add(-8, 0, -7), center.clone().add(8, 0, -7));
    }

    private void refreshCitizensNpcs(World world) {
        activeNpcs.clear();
        world.getEntitiesByClass(Villager.class).stream()
                .filter(entity -> entity.getScoreboardTags().contains("skybit_spawn_npc"))
                .forEach(Villager::remove);
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        List<NPC> previous = new ArrayList<>();
        for (NPC npc : registry) if (npc.data().has("skybit_action")) previous.add(npc);
        previous.forEach(NPC::destroy);
        List<Location> locations = npcLocations(world);
        for (Location location : locations) world.getChunkAt(location).load();
        createCitizensNpc(registry, locations.get(0), "§b§lSPRIEVODCA", "tutorial");
        createCitizensNpc(registry, locations.get(1), "§6§lCRATE MASTER", "crates");
        createCitizensNpc(registry, locations.get(2), "§d§lWARP MASTER", "warps");
        createCitizensNpc(registry, locations.get(3), mode().equals("skyblock") ? "§a§lISLAND MASTER" : "§e§lMENU MASTER", "player_menu");
        createCitizensNpc(registry, locations.get(4), "§b§lBANSKÝ MAJSTER", "minerush");
        createCitizensNpc(registry, locations.get(5), "§5§lKRONIKÁR", "story");
        createCitizensNpc(registry, locations.get(6), "§d§lHLASATEĽ", "vote");
        createCitizensNpc(registry, locations.get(7), "§a§lSPRÁVCA POZEMKOV", "residence");
        registry.saveToStore();
        getLogger().info("Citizens NPC tutorial network refreshed: 8 interactive NPCs.");
    }

    private void createCitizensNpc(NPCRegistry registry, Location location, String name, String action) {
        NPC npc = registry.createNPC(EntityType.PLAYER, name);
        npc.setProtected(true);
        npc.setUseMinecraftAI(false);
        npc.setAlwaysUseNameHologram(true);
        npc.data().setPersistent("skybit_action", action);
        configurePlayerTracking(npc);
        npc.spawn(location);
        if (npc.getEntity() != null) activeNpcs.add(npc.getEntity().getUniqueId());
    }

    private void configurePlayerTracking(NPC npc) {
        LookClose lookClose = npc.getOrAddTrait(LookClose.class);
        lookClose.lookClose(true);
        lookClose.setRange(12.0);
        lookClose.setRealisticLooking(true);
        lookClose.setPerPlayer(true);
        lookClose.setHeadOnly(false);
        lookClose.setLinkedBody(true);
        lookClose.setRandomlySwitchTargets(false);
        lookClose.setTargetNPCs(false);
    }

    private void spawnNpc(Location location, String name, Villager.Profession profession, String action) {
        Villager npc = location.getWorld().spawn(location, Villager.class);
        npc.setCustomName(name);
        npc.setCustomNameVisible(true);
        npc.setProfession(profession);
        // Level 1 keeps the professional skin without generating structure-map trades
        // while Paper is serializing a chunk (high-level cartographers can load chunks here).
        npc.setVillagerLevel(1);
        npc.setAI(false);
        npc.setSilent(true);
        npc.setInvulnerable(true);
        npc.setCollidable(false);
        npc.setRemoveWhenFarAway(false);
        npc.setPersistent(true);
        npc.addScoreboardTag("skybit_spawn_npc");
        npc.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        activeNpcs.add(npc.getUniqueId());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.getWorld().getName().equals(getConfig().getString("world", "world"))) return;
        for (org.bukkit.entity.Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof Villager && entity.getScoreboardTags().contains("skybit_spawn_npc")
                    && !activeNpcs.contains(entity.getUniqueId())) entity.remove();
        }
    }

    private String blockKey(Location location) {
        return location.getWorld().getName() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private void refreshCrateStations(World world) {
        crateStations.clear();
        Location center = spawn(world);
        List<Block> boxes = new ArrayList<>();
        List<String> tiers = List.of("basic", "vote", "rare", "epic", "legendary");
        ConfigurationSection configured = getConfig().getConfigurationSection("crate-stations");
        if (configured != null && tiers.stream().allMatch(configured::isConfigurationSection)) {
            for (String tier : tiers) {
                String path = "crate-stations." + tier;
                Block block = world.getBlockAt(getConfig().getInt(path + ".x"), getConfig().getInt(path + ".y"), getConfig().getInt(path + ".z"));
                Material material = Material.matchMaterial(getConfig().getString(path + ".material", "SHULKER_BOX"));
                if (material == null || !material.name().endsWith("SHULKER_BOX")) material = Material.SHULKER_BOX;
                if (block.getType() != material) {
                    block.setType(material, false);
                    getLogger().info(crateTierName(tier) + " crate block restored at " + compact(block.getLocation()) + ".");
                }
                boxes.add(block);
            }
        } else {
            int radius = getConfig().getInt("crate-scan-radius", 55);
            int vertical = getConfig().getInt("crate-scan-height", 14);
            for (int x = center.getBlockX() - radius; x <= center.getBlockX() + radius; x++) {
                for (int z = center.getBlockZ() - radius; z <= center.getBlockZ() + radius; z++) {
                    for (int y = center.getBlockY() - vertical; y <= center.getBlockY() + vertical; y++) {
                        Block block = world.getBlockAt(x, y, z);
                        if (block.getType().name().endsWith("SHULKER_BOX")) boxes.add(block);
                    }
                }
            }
        }
        boxes.sort(Comparator.comparingDouble(block -> block.getLocation().distanceSquared(center)));
        if (boxes.size() > 5) boxes = new ArrayList<>(boxes.subList(0, 5));
        boxes.sort(Comparator.comparingInt((Block block) -> block.getX()).thenComparingInt(Block::getZ));
        world.getEntitiesByClass(TextDisplay.class).stream()
                .filter(entity -> entity.getScoreboardTags().contains("skybit_crate_station"))
                .forEach(TextDisplay::remove);
        for (int index = 0; index < boxes.size(); index++) {
            Block block = boxes.get(index);
            String tier = tiers.get(index);
            crateStations.put(blockKey(block.getLocation()), tier);
            if (!excellentCratesEnabled()) {
                TextDisplay display = world.spawn(block.getLocation().add(0.5, 2.0, 0.5), TextDisplay.class);
                display.setText(crateTierColor(tier) + "§l" + crateTierName(tier) + " CRATE\n§fKlikni pravým tlačidlom");
                display.setBillboard(Display.Billboard.CENTER);
                display.setSeeThrough(true);
                display.setShadowed(true);
                display.setBackgroundColor(Color.fromARGB(150, 3, 9, 20));
                display.setPersistent(true);
                display.addScoreboardTag("skybit_crate_station");
            }
            getLogger().info(crateTierName(tier) + " crate linked to shulker box at " + compact(block.getLocation()) + ".");
        }
        if (boxes.size() < 5) getLogger().warning("Only " + boxes.size() + " shulker boxes were found for 5 crate stations.");
    }

    private boolean protectedLocation(Location location) {
        if (location.getWorld() == null || !location.getWorld().getName().equals(getConfig().getString("world", "world"))) return false;
        Location center = spawn(location.getWorld());
        double dx = location.getX() - center.getX();
        double dz = location.getZ() - center.getZ();
        double radius = getConfig().getDouble("protection-radius", 95.0);
        return dx * dx + dz * dz <= radius * radius;
    }

    private boolean canBuild(Player player) {
        return player.hasPermission("skybitspawn.build");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String revision = getConfig().getString("spawn-revision", "trading-post-v1");
        String seen = player.getPersistentDataContainer().get(revisionKey, PersistentDataType.STRING);
        boolean teleport = getConfig().getBoolean("teleport-on-revision-change", true) && !revision.equals(seen);
        Bukkit.getScheduler().runTask(this, () -> {
            World world = Bukkit.getWorld(getConfig().getString("world", "world"));
            if (world == null) return;
            if (teleport) {
                player.teleport(spawn(world));
                player.getPersistentDataContainer().set(revisionKey, PersistentDataType.STRING, revision);
            }
            if (mode().equals("skyblock") && player.getWorld().equals(world)) giveSkyBlockNavigator(player);
            updateLocalDay(player);
            String subtitle = mode().equals("skyblock") ? "§fTrading Post §8• §d/is" : "§fTrading Post §8• §bSlimefun";
            player.sendTitle("§b§lSKYBIT", subtitle, 10, 45, 15);
            if (!player.getPersistentDataContainer().has(tutorialKey, PersistentDataType.BYTE)) {
                player.getPersistentDataContainer().set(tutorialKey, PersistentDataType.BYTE, (byte) 1);
                giveKeys(player, "basic", 1);
                player.sendMessage("§8[§bSkyBit§8] §fDostal si uvítací crate kľúč. Tutorial otvoríš cez §b/tutorial§f.");
                Bukkit.getScheduler().runTaskLater(this, () -> openTutorial(player), 50L);
            }
        });
    }

    private void giveSkyBlockNavigator(Player player) {
        if (!mode().equals("skyblock")) return;
        for (ItemStack item : player.getInventory().getContents()) {
            if (hasAction(item, "skyblock_menu")) return;
        }
        ItemStack navigator = menuItem(Material.NETHER_STAR, "§d§l☁ SKYBLOCK MENU",
                List.of("§8Ostrovy a navigácia", "", "§7Klikni pre otvorenie menu."), "skyblock_menu");
        if (player.getInventory().getItem(8) == null) player.getInventory().setItem(8, navigator);
        else player.getInventory().addItem(navigator);
    }

    private void removeSkyBlockNavigator(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (hasAction(player.getInventory().getItem(slot), "skyblock_menu")) player.getInventory().setItem(slot, null);
        }
    }

    private ItemStack menuItem(Material material, String name, List<String> lore, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    private boolean hasAction(ItemStack item, String expected) {
        if (item == null || !item.hasItemMeta()) return false;
        String action = item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        return expected.equals(action);
    }

    private void fill(Inventory inventory, Material material) {
        ItemStack filler = new ItemStack(material);
        ItemMeta meta = filler.getItemMeta();
        meta.setDisplayName(" ");
        filler.setItemMeta(meta);
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
    }

    private void openSkyBlockMenu(Player player) {
        if (!mode().equals("skyblock")) {
            player.sendMessage("§cSkyBlock menu je dostupné iba na SkyBlock serveri.");
            return;
        }
        Inventory menu = Bukkit.createInventory(null, 54, SKYBLOCK_MENU_TITLE);
        fill(menu, Material.BLACK_STAINED_GLASS_PANE);
        menu.setItem(10, menuItem(Material.GRASS_BLOCK, "§a§lVYTVORIŤ / NAVŠTÍVIŤ OSTROV",
                List.of("§8Tvoj osobný SkyBlock", "", "§7Novému hráčovi vytvorí ostrov.", "§7Majiteľa presunie na jeho ostrov.", "", "§a▶ Klikni"), "command:is"));
        menu.setItem(12, menuItem(Material.WRITABLE_BOOK, "§b§lOSTROVNÉ MENU",
                List.of("§7Členovia, nastavenia a povolenia", "", "§b▶ Klikni"), "command:is settings"));
        menu.setItem(14, menuItem(Material.EMERALD, "§e§lVÝZVY",
                List.of("§7Plň úlohy a získavaj odmeny.", "", "§e▶ Otvoriť výzvy"), "command:is challenges"));
        menu.setItem(16, menuItem(Material.BEACON, "§d§lTOP OSTROVY",
                List.of("§7Pozri si najlepšie ostrovy.", "", "§d▶ Otvoriť rebríček"), "command:is top"));
        menu.setItem(28, menuItem(Material.EXPERIENCE_BOTTLE, "§a§lLEVEL OSTROVA",
                List.of("§7Prepočítaj hodnotu svojho ostrova.", "", "§a▶ Prepočítať"), "command:is level"));
        menu.setItem(30, menuItem(Material.PLAYER_HEAD, "§f§lTÍM OSTROVA",
                List.of("§7Pozvánky a správa členov.", "", "§f▶ Spravovať tím"), "command:is team"));
        menu.setItem(32, menuItem(Material.ENDER_PEARL, "§b§lTRADING POST",
                List.of("§7Vráti ťa na chránený spawn.", "", "§b▶ Teleportovať"), "spawn"));
        menu.setItem(34, menuItem(Material.RECOVERY_COMPASS, "§d§lSERVER MENU",
                List.of("§7Lobby, Survival a SkyBlock", "", "§d▶ Vybrať server"), "servers"));
        menu.setItem(38, menuItem(Material.TRIPWIRE_HOOK, "§6§lCRATES A KĽÚČE",
                List.of("§7SkyBlock odmeny a bonusy.", "", "§6▶ Otvoriť crates"), "crates"));
        menu.setItem(40, menuItem(Material.GOLD_INGOT, "§e§lAUKČNÝ DOM",
                List.of("§7Nakupuj a predávaj predmety.", "", "§e▶ Otvoriť aukcie"), "command:ah"));
        menu.setItem(42, menuItem(Material.ENDER_EYE, "§d§lPLAYER WARPY",
                List.of("§7Navštív verejné warpy hráčov.", "", "§d▶ Otvoriť warpy"), "warps"));
        menu.setItem(44, menuItem(Material.KNOWLEDGE_BOOK, "§b§lTUTORIAL",
                List.of("§7Rýchly sprievodca serverom.", "", "§b▶ Otvoriť tutorial"), "tutorial"));
        menu.setItem(48, menuItem(Material.SUNFLOWER, "§e§lSKYCOIN SHOP",
                List.of("§7Kozmetika a serverové výhody.", "", "§e▶ Otvoriť /coinshop"), "command:coinshop"));
        menu.setItem(50, menuItem(Material.NETHER_STAR, "§6§lVIP HODNOSTI",
                List.of("§7VIP • Knight • Baron • King • Emperor", "§7Ceny: §e500 až 4 500 SkyCoins", "", "§6▶ Otvoriť /ranks"), "command:ranks"));
        menu.setItem(52, menuItem(Material.PAPER, "§d§lHLASOVANIE A VOTE PARTY",
                List.of("§7Hlasuj, získaj Vote Key a SkyCoins.", "§7Každých 10 hlasov odmena pre všetkých.", "", "§d▶ Otvoriť /vote"), "command:vote"));
        menu.setItem(46, menuItem(Material.IRON_PICKAXE, "§b§lMINE RUSH",
                List.of("§7Vyťaž 5 000 blokov za 12 hodín.", "§7Peniaze, SkyCoins, XP a kľúče.", "", "§b▶ Zobraziť progres"), "command:minerush"));
        menu.setItem(47, menuItem(Material.BOOK, "§5§lPRÍBEH SKYBIT",
                List.of("§7Spoznaj svet, výzvy a svoju cestu.", "", "§5▶ Otvoriť príbeh"), "story"));
        player.openInventory(menu);
    }

    private void openSurvivalMenu(Player player) {
        Inventory menu = Bukkit.createInventory(null, 45, SURVIVAL_MENU_TITLE);
        fill(menu, Material.BLACK_STAINED_GLASS_PANE);
        menu.setItem(10, menuItem(Material.COMPASS, "§a§lRANDOM TELEPORT",
                List.of("§7Nájde bezpečné miesto v prírode.", "§8Cooldown: 60 sekúnd", "", "§a▶ Teleportovať"), "rtp"));
        menu.setItem(12, menuItem(Material.IRON_SWORD, "§b§lSLABÉ KITY",
                List.of("§7Starter, Daily a VIP kit.", "", "§b▶ Zobraziť kity"), "command:kits"));
        menu.setItem(14, menuItem(Material.TRIPWIRE_HOOK, "§6§lCRATES A KĽÚČE",
                List.of("§7Survival odmeny a suroviny.", "", "§6▶ Otvoriť crates"), "crates"));
        menu.setItem(16, menuItem(Material.GOLD_INGOT, "§e§lAUKČNÝ DOM",
                List.of("§7Nakupuj a predávaj predmety.", "", "§e▶ Otvoriť aukcie"), "command:ah"));
        menu.setItem(28, menuItem(Material.ENDER_EYE, "§d§lPLAYER WARPY",
                List.of("§7Verejné obchody a stavby hráčov.", "", "§d▶ Otvoriť warpy"), "warps"));
        menu.setItem(30, menuItem(Material.KNOWLEDGE_BOOK, "§b§lTUTORIAL",
                List.of("§7Rýchly sprievodca Survivalom.", "", "§b▶ Otvoriť tutorial"), "tutorial"));
        menu.setItem(32, menuItem(Material.ENDER_PEARL, "§f§lTRADING POST",
                List.of("§7Návrat na chránený spawn.", "", "§f▶ Teleportovať"), "spawn"));
        menu.setItem(34, menuItem(Material.RECOVERY_COMPASS, "§d§lSERVER MENU",
                List.of("§7Lobby, Survival a SkyBlock", "", "§d▶ Vybrať server"), "servers"));
        menu.setItem(22, menuItem(Material.NETHER_STAR, "§6§lVIP HODNOSTI",
                List.of("§7VIP • Knight • Baron • King • Emperor", "§7Ceny: §e500 až 4 500 SkyCoins", "", "§6▶ Otvoriť /ranks"), "command:ranks"));
        menu.setItem(24, menuItem(Material.SUNFLOWER, "§e§lSKYCOIN SHOP",
                List.of("§7Kozmetika a serverové výhody.", "", "§e▶ Otvoriť /coinshop"), "command:coinshop"));
        menu.setItem(26, menuItem(Material.PAPER, "§d§lHLASOVANIE A VOTE PARTY",
                List.of("§7Hlasuj, získaj Vote Key a SkyCoins.", "§7Každých 10 hlasov odmena pre všetkých.", "", "§d▶ Otvoriť /vote"), "command:vote"));
        menu.setItem(20, menuItem(Material.IRON_PICKAXE, "§b§lMINE RUSH",
                List.of("§7Vyťaž 5 000 blokov za 12 hodín.", "§7Peniaze, SkyCoins, XP a kľúče.", "", "§b▶ Zobraziť progres"), "command:minerush"));
        menu.setItem(38, menuItem(Material.GOLDEN_SHOVEL, "§a§lOCHRANA POZEMKU /RES",
                List.of("§7Vytvor claim a povoľ svojich priateľov.", "§7Príkazy: §f/res", "", "§a▶ Zobraziť pomoc"), "command:res"));
        menu.setItem(40, menuItem(Material.BOOK, "§5§lPRÍBEH SKYBIT",
                List.of("§7Spoznaj svet, výzvy a svoju cestu.", "", "§5▶ Otvoriť príbeh"), "story"));
        player.openInventory(menu);
    }

    private void openTutorial(Player player) {
        Inventory menu = Bukkit.createInventory(null, 54, TUTORIAL_TITLE);
        fill(menu, Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        menu.setItem(10, menuItem(Material.NAME_TAG, "§b§l1. VITAJ NA SKYBITE",
                List.of("§7Účet si chráň cez §f/register§7 a §f/login§7.", "§7Hlavné menu otvoríš cez §f/menu§7.", "", "§b▶ Teleportovať na začiatok"), "tour:spawn"));
        menu.setItem(12, menuItem(Material.CHEST, "§a§l2. PRVÉ VYBAVENIE",
                List.of("§7Vyzdvihni §f/kit starter§7.", "§7Dennú odmenu nájdeš cez §f/daily§7.", "", "§a▶ Zobraziť kity"), "command:kits"));
        menu.setItem(14, menuItem(Material.EMERALD, "§a§l3. EKONOMIKA",
                List.of("§7Zarábaj cez Jobs, questy a obchod.", "§7Stav účtu: §f/bal", "§7Serverový obchod: §f/shop", "", "§a▶ Otvoriť shop"), "command:shop"));
        menu.setItem(16, menuItem(Material.GOLD_INGOT, "§e§l4. AUKCIE",
                List.of("§7Predávaj predmety ostatným hráčom.", "§7Použi §f/ah§7.", "", "§e▶ Otvoriť aukcie"), "command:ah"));
        menu.setItem(19, menuItem(Material.SUNFLOWER, "§e§l5. SKYCOINS",
                List.of("§7Získaš ich za questy, daily a eventy.", "§7Stav: §f/coins", "§7Obchod: §f/coinshop", "", "§e▶ Otvoriť Coin Shop"), "command:coinshop"));
        menu.setItem(21, menuItem(Material.NETHER_STAR, "§6§l6. VIP HODNOSTI",
                List.of("§7VIP, Knight, Baron, King a Emperor.", "§7Ceny sú 500 až 4 500 SkyCoins.", "§7Každá hodnosť pridáva domovy, kit a výhody.", "", "§6▶ Otvoriť VIP Shop"), "command:ranks"));
        menu.setItem(23, menuItem(Material.TRIPWIRE_HOOK, "§6§l7. CRATES",
                List.of("§7Basic, Vote, Rare, Epic a Legendary.", "§7Kľúče získavaš z hlasovania, eventov a odmien.", "", "§6▶ Teleportovať ku crates"), "tour:crates"));
        menu.setItem(25, menuItem(Material.ENDER_EYE, "§d§l8. PLAYER WARPY",
                List.of("§7Vytvor: §f/pwarp set <názov>", "§7Zoznam: §f/pwarp", "", "§d▶ Teleportovať k Warp Masterovi"), "tour:warps"));
        menu.setItem(28, menuItem(Material.RED_BED, "§c§l9. DOMOVY A SPAWN",
                List.of("§7Nastav domov: §f/sethome <názov>", "§7Návrat: §f/home <názov>", "§7Trading Post: §f/spawn §8• §f/lobby", "§7Ochrana Survival pozemku: §f/res", "", "§c▶ Teleportovať k správcovi"), "tour:residence"));
        if (mode().equals("skyblock")) {
            menu.setItem(30, menuItem(Material.GRASS_BLOCK, "§a§l10. VYTVOR OSTROV",
                    List.of("§7Použi §f/is§7 alebo klikni v menu.", "§7Výzvy: §f/is challenges", "§7Tím: §f/is team", "", "§a▶ Vytvoriť/navštíviť ostrov"), "command:is"));
            menu.setItem(32, menuItem(Material.BEACON, "§d§l11. PROGRES OSTROVA",
                    List.of("§7Level: §f/is level", "§7Rebríček: §f/is top", "§7Nastavenia: §f/is settings", "", "§d▶ Prepočítať level"), "command:is level"));
        } else {
            menu.setItem(30, menuItem(Material.COMPASS, "§a§l10. ZAČNI SURVIVAL",
                    List.of("§7Použi §f/rtp§7 a nájdi bezpečné miesto.", "§7Pozemok zabezpeč claimom.", "§7Získaj prácu cez Jobs menu.", "", "§a▶ Random teleport"), "rtp"));
            menu.setItem(32, menuItem(Material.ENCHANTED_BOOK, "§b§l11. SLIMEFUN A QUESTY",
                    List.of("§7Slimefun guide otvoríš knihou v inventári.", "§7Úlohy: §f/quests", "§7Denná odmena: §f/daily", "", "§b▶ Zobraziť questy"), "command:quests"));
        }
        menu.setItem(34, menuItem(Material.WRITABLE_BOOK, "§f§l12. PRÍKAZY A POMOC",
                List.of("§7Herné menu: §f/menu", "§7Servery: §f/servers", "§7Tutorial: §f/tutorial", "§7Scoreboard: §f/sb"), "none"));
        menu.setItem(36, menuItem(Material.PAPER, "§d§l13. HLASOVANIE",
                List.of("§7Použi §f/vote§7 pre odkazy a odmenu.", "§7Progres spoločnej párty: §f/voteparty", "", "§d▶ Teleportovať k Hlasateľovi"), "tour:vote"));
        menu.setItem(38, menuItem(Material.BOOK, "§5§l14. PRÍBEH SKYBIT",
                List.of("§7Kronikár vysvetlí svet a ďalšie ciele.", "", "§5▶ Teleportovať ku Kronikárovi"), "tour:story"));
        menu.setItem(42, menuItem(Material.IRON_PICKAXE, "§b§l15. MINE RUSH",
                List.of("§7Vyťaž 5 000 blokov za 12 hodín.", "§7Získaj peniaze, SkyCoins, XP a kľúče.", "", "§b▶ Teleportovať k Banskému majstrovi"), "tour:mine"));
        menu.setItem(40, menuItem(Material.RECOVERY_COMPASS, "§d§lSERVER MENU",
                List.of("§7Prepni Survival, SkyBlock alebo Lobby.", "", "§d▶ Vybrať server"), "servers"));
        player.openInventory(menu);
    }

    private void openStory(Player player) {
        Inventory menu = Bukkit.createInventory(null, 54, STORY_TITLE);
        fill(menu, Material.PURPLE_STAINED_GLASS_PANE);
        menu.setItem(10, menuItem(Material.BOOK, "§d§lI. PÁD NEBESKÉHO JADRA",
                List.of("§7SkyBit vznikol z úlomkov jadra,", "§7ktoré spojilo divočinu a nebeské ostrovy.", "", "§fTvoja cesta začína na Trading Poste."), "none"));
        menu.setItem(12, menuItem(Material.IRON_PICKAXE, "§b§lII. SKÚŠKA BANÍKOV",
                List.of("§7Banský majster hľadá vytrvalých hráčov.", "§7Vyťaž 5 000 blokov počas 12 hodín.", "", "§b▶ Zobraziť Mine Rush"), "command:minerush"));
        menu.setItem(14, menuItem(Material.GRASS_BLOCK, "§a§lIII. NOVÝ DOMOV",
                List.of("§7Survivalisti chránia územie cez §f/res§7.", "§7SkyBlockeri budujú vlastný ostrov cez §f/is§7.", "", "§a▶ Otvoriť hráčske menu"), "player_menu"));
        menu.setItem(16, menuItem(Material.NETHER_STAR, "§6§lIV. CESTA K EMPEROROVI",
                List.of("§7Questy, eventy a hlasovanie dávajú SkyCoins.", "§7Postúp cez päť hodností až na Emperor.", "", "§6▶ Otvoriť VIP Shop"), "command:ranks"));
        menu.setItem(21, menuItem(Material.TRIPWIRE_HOOK, "§3§lV. PÄŤ PEČATÍ",
                List.of("§7Basic, Vote, Rare, Epic a Legendary.", "§7Zbieraj kľúče a odomykaj odmeny.", "", "§3▶ Otvoriť crates"), "crates"));
        menu.setItem(23, menuItem(Material.PAPER, "§d§lVI. HLAS SIETE",
                List.of("§7Každý hlas posúva spoločnú Vote Party.", "§7Po desiatich hlasoch získajú odmenu všetci.", "", "§d▶ Hlasovať"), "command:vote"));
        menu.setItem(32, menuItem(Material.KNOWLEDGE_BOOK, "§b§lPOKRAČOVAŤ V TUTORIALE",
                List.of("§7Pozri si všetky systémy krok za krokom.", "", "§b▶ Otvoriť tutorial"), "tutorial"));
        player.openInventory(menu);
    }

    private void tutorialTeleport(Player player, String destination) {
        World world = Bukkit.getWorld(getConfig().getString("world", "world"));
        if (world == null) { player.sendMessage("§cTutorial miesto nie je načítané."); return; }
        List<Location> locations = npcLocations(world);
        Location target;
        String title;
        String hint;
        switch (destination) {
            case "crates" -> { target = new Location(world, 28.5, 150.0, 8.5, 0f, 0f); title = "§6§lCRATES"; hint = "§fKlikni na farebný shulker alebo použi §e/crates§f."; }
            case "warps" -> { target = locations.get(2).clone().add(0, 0, 2); title = "§d§lPLAYER WARPY"; hint = "§fWarp vytvoríš cez §d/pwarp set <názov>§f."; }
            case "residence" -> { target = locations.get(7).clone().add(0, 0, 2); title = "§a§lOCHRANA POZEMKU"; hint = mode().equals("skyblock") ? "§fOstrov chráni §a/is settings§f." : "§fSprávu claimu otvoríš cez §a/res§f."; }
            case "vote" -> { target = locations.get(6).clone().add(0, 0, 2); title = "§d§lVOTE PARTY"; hint = "§fHlasovacie odkazy zobrazíš cez §d/vote§f."; }
            case "story" -> { target = locations.get(5).clone().add(0, 0, 2); title = "§5§lKRONIKÁR"; hint = "§fKlikni na Kronikára a otvor príbeh SkyBit."; }
            case "mine" -> { target = locations.get(4).clone().add(0, 0, 2); title = "§b§lMINE RUSH"; hint = "§fProgres výzvy zobrazíš cez §b/minerush§f."; }
            default -> { target = spawn(world); title = "§b§lVITAJ NA SKYBITE"; hint = "§fSprievodca otvorí teleportovací §b/tutorial§f."; }
        }
        player.closeInventory();
        player.teleport(target);
        updateLocalDay(player);
        player.sendTitle(title, "§7Tutorial ťa priviedol na správne miesto", 10, 45, 15);
        player.sendMessage("§8[§bTutorial§8] " + hint);
    }

    private String crateTier(String tier) {
        String value = tier == null ? "basic" : tier.toLowerCase(Locale.ROOT);
        return List.of("basic", "vote", "rare", "epic", "legendary").contains(value) ? value : "basic";
    }

    private String crateTierName(String tier) {
        return switch (crateTier(tier)) {
            case "vote" -> "VOTE";
            case "rare" -> "RARE";
            case "epic" -> "EPIC";
            case "legendary" -> "LEGENDARY";
            default -> "BASIC";
        };
    }

    private String crateTierColor(String tier) {
        return switch (crateTier(tier)) {
            case "vote" -> "§a";
            case "rare" -> "§3";
            case "epic" -> "§d";
            case "legendary" -> "§6";
            default -> "§b";
        };
    }

    private ItemStack crateKey(String tier, int amount) {
        String normalized = crateTier(tier);
        Material material = switch (normalized) {
            case "vote" -> Material.EMERALD;
            case "rare" -> Material.ECHO_SHARD;
            case "epic" -> Material.AMETHYST_SHARD;
            case "legendary" -> Material.NETHER_STAR;
            default -> Material.TRIPWIRE_HOOK;
        };
        ItemStack key = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = key.getItemMeta();
        String type = mode().equals("skyblock") ? "SKYBLOCK" : "SURVIVAL";
        meta.setDisplayName(crateTierColor(normalized) + "§l" + crateTierName(normalized) + " " + type + " KEY");
        meta.setLore(List.of("§8SkyBit " + crateTierName(normalized) + " odmena", "", "§7Použi cez §f/crates§7."));
        meta.getPersistentDataContainer().set(crateKey, PersistentDataType.STRING, mode() + ":" + normalized);
        key.setItemMeta(meta);
        return key;
    }

    private boolean isCrateKey(ItemStack item, String tier) {
        if (item == null || !item.hasItemMeta()) return false;
        String type = item.getItemMeta().getPersistentDataContainer().get(crateKey, PersistentDataType.STRING);
        if (type == null) return false;
        String expected = mode() + ":" + crateTier(tier);
        return type.equals(expected) || (crateTier(tier).equals("basic") && type.equals(mode()));
    }

    private int countKeys(Player player, String tier) {
        if (excellentCratesEnabled() && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            String value = PlaceholderAPI.setPlaceholders(player,
                    "%excellentcrates_openings_available_" + crateTier(tier) + "%");
            try { return Integer.parseInt(value.replaceAll("[^0-9]", "")); }
            catch (NumberFormatException ignored) { return 0; }
        }
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (isCrateKey(item, tier)) count += item.getAmount();
        }
        return count;
    }

    private boolean consumeKey(Player player, String tier) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (!isCrateKey(item, tier)) continue;
            if (item.getAmount() <= 1) player.getInventory().setItem(slot, null);
            else item.setAmount(item.getAmount() - 1);
            return true;
        }
        return false;
    }

    private void giveKeys(Player player, String tier, int amount) {
        if (excellentCratesEnabled()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "excellentcrates:crates key give "
                    + player.getName() + " " + crateTier(tier) + " " + Math.max(1, amount) + " -sf");
            return;
        }
        player.getInventory().addItem(crateKey(tier, amount)).values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
    }

    private boolean excellentCratesEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("ExcellentCrates");
    }

    private void openCrates(Player player) {
        Inventory menu = Bukkit.createInventory(null, 54, CRATES_TITLE);
        fill(menu, mode().equals("skyblock") ? Material.PURPLE_STAINED_GLASS_PANE : Material.CYAN_STAINED_GLASS_PANE);
        menu.setItem(10, menuItem(Material.CHEST, "§b§lBASIC CRATE",
                List.of("§7Bežné suroviny, peniaze a SkyCoins.", "§7Tvoje kľúče: §f" + countKeys(player, "basic"), "", "§b▶ Otvoriť"), "open_crate:basic"));
        menu.setItem(12, menuItem(Material.EMERALD_BLOCK, "§a§lVOTE CRATE",
                List.of("§7Odmeny za podporu servera.", "§7Tvoje kľúče: §f" + countKeys(player, "vote"), "", "§a▶ Otvoriť"), "open_crate:vote"));
        menu.setItem(14, menuItem(Material.ENDER_CHEST, "§3§lRARE CRATE",
                List.of("§7Lepšie suroviny, väčšie odmeny a kľúče.", "§7Tvoje kľúče: §f" + countKeys(player, "rare"), "", "§d▶ Otvoriť"), "open_crate:rare"));
        menu.setItem(16, menuItem(Material.RESPAWN_ANCHOR, "§d§lEPIC CRATE",
                List.of("§7Vzácne predmety a veľké SkyCoin odmeny.", "§7Tvoje kľúče: §f" + countKeys(player, "epic"), "", "§d▶ Otvoriť"), "open_crate:epic"));
        menu.setItem(22, menuItem(Material.BEACON, "§6§lLEGENDARY CRATE",
                List.of("§7Najvzácnejšie odmeny a veľa SkyCoins.", "§7Tvoje kľúče: §f" + countKeys(player, "legendary"), "", "§6▶ Otvoriť"), "open_crate:legendary"));
        menu.setItem(39, menuItem(Material.BOOK, "§f§lAKO ZÍSKAŤ KĽÚČE?",
                List.of("§7Daily odmeny a questy", "§7Eventy a playtime odmeny", "§7Crates môžu dať lepší kľúč", "§7VIP hodnosti majú bonusové odmeny"), "none"));
        menu.setItem(41, menuItem(Material.SUNFLOWER, "§e§lKEY SHOP ZA SKYCOINS",
                List.of("§7Kúp Basic, Vote, Rare, Epic", "§7alebo Legendary kľúč za SkyCoins.", "", "§e▶ Otvoriť Key Shop"), "keyshop"));
        player.openInventory(menu);
    }

    private void openKeyShop(Player player) {
        Inventory menu = Bukkit.createInventory(null, 45, KEY_SHOP_TITLE);
        fill(menu, Material.YELLOW_STAINED_GLASS_PANE);
        String[] tiers = {"basic", "vote", "rare", "epic", "legendary"};
        int[] slots = {10, 12, 14, 16, 22};
        int[] prices = {25, 50, 100, 200, 400};
        Material[] materials = {Material.TRIPWIRE_HOOK, Material.EMERALD, Material.ECHO_SHARD, Material.AMETHYST_SHARD, Material.NETHER_STAR};
        for (int index = 0; index < tiers.length; index++) {
            String tier = tiers[index];
            menu.setItem(slots[index], menuItem(materials[index], crateTierColor(tier) + "§l" + crateTierName(tier) + " KEY",
                    List.of("§7Cena: §e" + prices[index] + " SkyCoins", "§7Tvoje kľúče: §f" + countKeys(player, tier), "", "§e▶ Kúpiť 1 kľúč"), "buy_key:" + tier));
        }
        menu.setItem(40, menuItem(Material.ARROW, "§f§lSPÄŤ NA CRATES", List.of("§7Vrátiť sa na výber crates."), "crates"));
        player.openInventory(menu);
    }

    private void openCrate(Player player, String tier) {
        String normalized = crateTier(tier);
        if (excellentCratesEnabled()) {
            player.closeInventory();
            player.performCommand("excellentcrates:crates open " + normalized);
            return;
        }
        if (!consumeKey(player, normalized)) {
            player.sendMessage("§cNemáš " + crateTierName(normalized) + " crate kľúč pre tento server.");
            return;
        }
        int roll = ThreadLocalRandom.current().nextInt(100);
        String reward;
        if (normalized.equals("legendary")) {
            if (roll < 20) reward = giveMoneyReward(player, mode().equals("skyblock") ? 15000 : 20000);
            else if (roll < 40) reward = giveCoinsReward(player, 100);
            else if (roll < 58) reward = giveItemReward(player, Material.DIAMOND, mode().equals("skyblock") ? 12 : 16, (mode().equals("skyblock") ? 12 : 16) + "× Diamond");
            else if (roll < 73) reward = giveItemReward(player, Material.NETHERITE_INGOT, 2, "2× Netherite Ingot");
            else if (roll < 88) reward = giveItemReward(player, Material.ENCHANTED_GOLDEN_APPLE, 1, "1× Enchanted Golden Apple");
            else { giveKeys(player, "legendary", 2); reward = "2× Legendary Key"; }
        } else if (normalized.equals("epic")) {
            if (roll < 22) reward = giveMoneyReward(player, mode().equals("skyblock") ? 9000 : 11000);
            else if (roll < 44) reward = giveCoinsReward(player, 65);
            else if (roll < 64) reward = giveItemReward(player, Material.DIAMOND, mode().equals("skyblock") ? 8 : 10, (mode().equals("skyblock") ? 8 : 10) + "× Diamond");
            else if (roll < 82) reward = giveItemReward(player, Material.NETHERITE_SCRAP, 3, "3× Netherite Scrap");
            else if (roll < 95) { giveKeys(player, "rare", 2); reward = "2× Rare Key"; }
            else { giveKeys(player, "legendary", 1); reward = "1× Legendary Key"; }
        } else if (normalized.equals("rare")) {
            if (roll < 22) reward = giveMoneyReward(player, mode().equals("skyblock") ? 5000 : 6500);
            else if (roll < 42) reward = giveCoinsReward(player, 35);
            else if (roll < 62) reward = giveItemReward(player, Material.DIAMOND, mode().equals("skyblock") ? 4 : 6, (mode().equals("skyblock") ? 4 : 6) + "× Diamond");
            else if (roll < 80) reward = giveItemReward(player, Material.GOLDEN_APPLE, 5, "5× Golden Apple");
            else if (roll < 95) { giveKeys(player, "basic", 3); reward = "3× Basic Key"; }
            else { giveKeys(player, "legendary", 1); reward = "1× Legendary Key"; }
        } else if (normalized.equals("vote")) {
            if (roll < 28) reward = giveMoneyReward(player, mode().equals("skyblock") ? 2200 : 3000);
            else if (roll < 52) reward = giveCoinsReward(player, 20);
            else if (roll < 72) reward = giveItemReward(player, Material.IRON_INGOT, 24, "24× Iron Ingot");
            else if (roll < 90) reward = giveItemReward(player, Material.DIAMOND, 3, "3× Diamond");
            else { giveKeys(player, "rare", 1); reward = "1× Rare Key"; }
        } else if (mode().equals("skyblock")) {
            if (roll < 25) reward = giveItemReward(player, Material.COBBLESTONE, 64, "64× Cobblestone");
            else if (roll < 45) reward = giveItemReward(player, Material.DIRT, 32, "32× Dirt");
            else if (roll < 65) reward = giveItemReward(player, Material.OAK_LOG, 32, "32× Oak Log");
            else if (roll < 80) reward = giveMoneyReward(player, 1000);
            else if (roll < 91) reward = giveItemReward(player, Material.DIAMOND, 1, "1× Diamond");
            else if (roll < 97) reward = giveCoinsReward(player, 12);
            else { giveKeys(player, "rare", 1); reward = "1× Rare Key"; }
        } else {
            if (roll < 25) reward = giveMoneyReward(player, 500);
            else if (roll < 48) reward = giveItemReward(player, Material.IRON_INGOT, 16, "16× Iron Ingot");
            else if (roll < 68) reward = giveItemReward(player, Material.COAL, 32, "32× Coal");
            else if (roll < 82) reward = giveItemReward(player, Material.DIAMOND, 2, "2× Diamond");
            else if (roll < 92) reward = giveItemReward(player, Material.GOLDEN_APPLE, 2, "2× Golden Apple");
            else if (roll < 97) reward = giveCoinsReward(player, 12);
            else { giveKeys(player, "rare", 1); reward = "1× Rare Key"; }
        }
        player.closeInventory();
        player.sendTitle(crateTierColor(normalized) + "§l" + crateTierName(normalized) + " CRATE", "§f" + reward, 10, 50, 15);
        player.sendMessage("§8[§6Crates§8] §7Získal si §f" + reward + "§7 z " + crateTierColor(normalized) + crateTierName(normalized) + "§7 crate.");
    }

    private String giveMoneyReward(Player player, int amount) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "eco give " + player.getName() + " " + amount);
        return "$" + amount;
    }

    private String giveCoinsReward(Player player, int amount) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "skybitadmin coins " + player.getName() + " " + amount);
        return amount + " SkyCoins";
    }

    private String giveItemReward(Player player, Material material, int amount, String label) {
        ItemStack reward = new ItemStack(material, amount);
        player.getInventory().addItem(reward).values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        return label;
    }

    private void saveWarps() {
        try {
            warps.save(warpsFile);
        } catch (IOException exception) {
            getLogger().severe("Player warps could not be saved: " + exception.getMessage());
        }
    }

    private void openPlayerWarps(Player player) {
        Inventory menu = Bukkit.createInventory(null, 54, WARPS_TITLE);
        fill(menu, Material.PURPLE_STAINED_GLASS_PANE);
        if (warps.getConfigurationSection("warps") != null) {
            int slot = 0;
            for (String key : warps.getConfigurationSection("warps").getKeys(false)) {
                while (slot < 45 && slot % 9 == 8) slot++;
                if (slot >= 45) break;
                String base = "warps." + key;
                String display = warps.getString(base + ".name", key);
                String owner = warps.getString(base + ".owner-name", "hráč");
                menu.setItem(slot++, menuItem(Material.ENDER_PEARL, "§d§l" + display,
                        List.of("§7Majiteľ: §f" + owner, "§7Svet: §f" + warps.getString(base + ".world", "world"), "", "§d▶ Teleportovať"), "pwarp:" + key));
            }
        }
        menu.setItem(49, menuItem(Material.ANVIL, "§a§lVYTVOR PLAYER WARP",
                List.of("§7Postav sa na miesto a použi:", "§f/pwarp set <názov>", "", "§8Hráč: 2 warpy • VIP: 5 warpov"), "none"));
        player.openInventory(menu);
    }

    private void setPlayerWarp(Player player, String name) {
        if (!name.matches("[A-Za-z0-9_-]{1,16}")) {
            player.sendMessage("§cNázov musí mať 1–16 znakov: písmená, čísla, _ alebo -.");
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        int owned = 0;
        if (warps.getConfigurationSection("warps") != null) {
            for (String existing : warps.getConfigurationSection("warps").getKeys(false)) {
                if (player.getUniqueId().toString().equals(warps.getString("warps." + existing + ".owner"))) owned++;
            }
        }
        boolean updatingOwn = player.getUniqueId().toString().equals(warps.getString("warps." + key + ".owner"));
        int limit = player.hasPermission("skybitspawn.build") ? 20 : player.hasPermission("essentials.kits.vip") ? 5 : 2;
        if (!updatingOwn && owned >= limit) {
            player.sendMessage("§cDosiahol si limit " + limit + " player warpov.");
            return;
        }
        if (warps.contains("warps." + key) && !updatingOwn && !player.hasPermission("skybitspawn.build")) {
            player.sendMessage("§cTento názov už používa iný hráč.");
            return;
        }
        Location location = player.getLocation();
        String base = "warps." + key;
        warps.set(base + ".name", name);
        warps.set(base + ".owner", player.getUniqueId().toString());
        warps.set(base + ".owner-name", player.getName());
        warps.set(base + ".world", location.getWorld().getName());
        warps.set(base + ".x", location.getX());
        warps.set(base + ".y", location.getY());
        warps.set(base + ".z", location.getZ());
        warps.set(base + ".yaw", location.getYaw());
        warps.set(base + ".pitch", location.getPitch());
        saveWarps();
        player.sendMessage("§8[§dPlayerWarps§8] §aWarp §f" + name + " §abol uložený.");
    }

    private void deletePlayerWarp(Player player, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        String owner = warps.getString("warps." + key + ".owner");
        if (owner == null) {
            player.sendMessage("§cTaký player warp neexistuje.");
            return;
        }
        if (!owner.equals(player.getUniqueId().toString()) && !player.hasPermission("skybitspawn.build")) {
            player.sendMessage("§cTento player warp ti nepatrí.");
            return;
        }
        warps.set("warps." + key, null);
        saveWarps();
        player.sendMessage("§8[§dPlayerWarps§8] §7Warp §f" + name + " §7bol odstránený.");
    }

    private void visitPlayerWarp(Player player, String key) {
        String base = "warps." + key.toLowerCase(Locale.ROOT);
        World world = Bukkit.getWorld(warps.getString(base + ".world", ""));
        if (world == null || !warps.contains(base)) {
            player.sendMessage("§cPlayer warp nie je dostupný.");
            return;
        }
        Location location = new Location(world, warps.getDouble(base + ".x"), warps.getDouble(base + ".y"),
                warps.getDouble(base + ".z"), (float) warps.getDouble(base + ".yaw"), (float) warps.getDouble(base + ".pitch"));
        player.teleport(location);
        player.sendMessage("§8[§dPlayerWarps§8] §7Teleport na §f" + warps.getString(base + ".name", key) + "§7.");
    }

    private void randomTeleport(Player player) {
        if (!mode().equals("survival")) {
            player.sendMessage("§cRTP je dostupné iba na Survival serveri.");
            return;
        }
        long now = System.currentTimeMillis();
        long remaining = 60_000L - (now - rtpCooldowns.getOrDefault(player.getUniqueId(), 0L));
        if (remaining > 0 && !player.hasPermission("skybitspawn.build")) {
            player.sendMessage("§cRTP môžeš použiť o " + ((remaining + 999) / 1000) + " sekúnd.");
            return;
        }
        World world = Bukkit.getWorld(getConfig().getString("world", "world"));
        if (world == null) return;
        for (int attempt = 0; attempt < 16; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            double distance = ThreadLocalRandom.current().nextDouble(1000, 5000);
            int x = (int) Math.round(Math.cos(angle) * distance);
            int z = (int) Math.round(Math.sin(angle) * distance);
            Block surface = world.getHighestBlockAt(x, z);
            Material type = surface.getType();
            if (!type.isSolid() || type == Material.MAGMA_BLOCK || type == Material.CACTUS || type == Material.LAVA) continue;
            Location target = new Location(world, x + 0.5, surface.getY() + 1.0, z + 0.5);
            if (!target.getBlock().isEmpty() || !target.clone().add(0, 1, 0).getBlock().isEmpty()) continue;
            player.teleport(target);
            rtpCooldowns.put(player.getUniqueId(), now);
            player.sendTitle("§a§lRANDOM TELEPORT", "§f" + x + "§7, §f" + z, 10, 40, 10);
            return;
        }
        player.sendMessage("§cNenašlo sa bezpečné miesto. Skús RTP znova.");
    }

    private void openServerMenu(Player player) {
        Inventory menu = Bukkit.createInventory(null, 27, SERVER_MENU_TITLE);
        fill(menu, Material.GRAY_STAINED_GLASS_PANE);
        menu.setItem(11, menuItem(Material.RESPAWN_ANCHOR, "§b§lSKYBIT §fSURVIVAL",
                List.of("§8Slimefun survival", "", "§7Ekonomika • Jobs • Questy", "§7Trading • Eventy • Claimy", "", "§b▶ Pripojiť"), "server:skybit"));
        menu.setItem(13, menuItem(Material.NETHER_STAR, "§f§lHLAVNÁ LOBBY",
                List.of("§8SkyBit Network", "", "§7Centrálny výber herných režimov", "", "§f▶ Pripojiť"), "server:lobby"));
        menu.setItem(15, menuItem(Material.END_STONE, "§d§lSKYBIT §fSKYBLOCK",
                List.of("§8Ostrovný progres", "", "§7Ostrovy • Výzvy • Rebríčky", "§7Trading • Tímy", "", "§d▶ Pripojiť"), "server:skyblock"));
        player.openInventory(menu);
    }

    private void sendServer(Player player, String server) {
        if (server.equalsIgnoreCase("lobby")) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeUTF("Connect");
        output.writeUTF(server);
        player.sendPluginMessage(this, "BungeeCord", output.toByteArray());
    }

    private void dispatch(Player player, String command) {
        player.closeInventory();
        Bukkit.getScheduler().runTask(this, () -> Bukkit.dispatchCommand(player, command));
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!title.equals(SKYBLOCK_MENU_TITLE) && !title.equals(SURVIVAL_MENU_TITLE) && !title.equals(SERVER_MENU_TITLE)
                && !title.equals(TUTORIAL_TITLE) && !title.equals(CRATES_TITLE) && !title.equals(KEY_SHOP_TITLE)
                && !title.equals(WARPS_TITLE) && !title.equals(STORY_TITLE)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        String action = item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;
        if (action.equals("skyblock_menu")) openSkyBlockMenu(player);
        else if (action.equals("player_menu")) {
            if (mode().equals("skyblock")) openSkyBlockMenu(player); else openSurvivalMenu(player);
        }
        else if (action.equals("servers")) openServerMenu(player);
        else if (action.equals("tutorial")) openTutorial(player);
        else if (action.equals("story")) openStory(player);
        else if (action.equals("crates")) openCrates(player);
        else if (action.equals("keyshop")) openKeyShop(player);
        else if (action.startsWith("open_crate:")) openCrate(player, action.substring("open_crate:".length()));
        else if (action.startsWith("buy_key:")) dispatch(player, "buykey " + action.substring("buy_key:".length()));
        else if (action.startsWith("tour:")) tutorialTeleport(player, action.substring("tour:".length()));
        else if (action.equals("warps")) openPlayerWarps(player);
        else if (action.equals("rtp")) { player.closeInventory(); randomTeleport(player); }
        else if (action.equals("spawn")) {
            player.closeInventory();
            World world = Bukkit.getWorld(getConfig().getString("world", "world"));
            if (world != null) player.teleport(spawn(world));
        } else if (action.startsWith("command:")) dispatch(player, action.substring("command:".length()));
        else if (action.startsWith("pwarp:")) {
            player.closeInventory();
            visitPlayerWarp(player, action.substring("pwarp:".length()));
        }
        else if (action.startsWith("server:")) {
            player.closeInventory();
            sendServer(player, action.substring("server:".length()));
        }
    }

    @EventHandler
    public void onNavigatorUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() != null) {
            String tier = crateStations.get(blockKey(event.getClickedBlock().getLocation()));
            if (tier != null) {
                if (excellentCratesEnabled()) return;
                event.setCancelled(true);
                openCrate(event.getPlayer(), tier);
                return;
            }
        }
        if (!hasAction(event.getItem(), "skyblock_menu")) return;
        event.setCancelled(true);
        openSkyBlockMenu(event.getPlayer());
    }

    @EventHandler
    public void onNavigatorDrop(PlayerDropItemEvent event) {
        if (hasAction(event.getItemDrop().getItemStack(), "skyblock_menu")) event.setCancelled(true);
    }

    @EventHandler
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager npc) || !npc.getScoreboardTags().contains("skybit_spawn_npc")) return;
        event.setCancelled(true);
        String action = npc.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;
        handleNpcAction(event.getPlayer(), action);
    }

    @EventHandler
    public void onCitizensNpcClick(NPCRightClickEvent event) {
        String action = event.getNPC().data().get("skybit_action", "");
        if (action.isBlank()) return;
        event.setCancelled(true);
        handleNpcAction(event.getClicker(), action);
    }

    private void handleNpcAction(Player player, String action) {
        switch (action) {
            case "tutorial" -> openTutorial(player);
            case "crates" -> openCrates(player);
            case "warps" -> openPlayerWarps(player);
            case "story" -> openStory(player);
            case "minerush" -> dispatch(player, "minerush");
            case "vote" -> dispatch(player, "vote");
            case "residence" -> dispatch(player, "res");
            case "player_menu" -> {
                if (mode().equals("skyblock")) openSkyBlockMenu(player); else openSurvivalMenu(player);
            }
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        updateLocalDay(event.getPlayer());
        if (mode().equals("skyblock")) {
            if (event.getPlayer().getWorld().getName().equals(getConfig().getString("world", "world"))) giveSkyBlockNavigator(event.getPlayer());
            else removeSkyBlockNavigator(event.getPlayer());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("skybitkey")) return handleKeyCommand(sender, args);
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Tento príkaz je iba pre hráča.");
            return true;
        }
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "servers" -> openServerMenu(player);
            case "lobby" -> sendServer(player, "lobby");
            case "islandmenu" -> openSkyBlockMenu(player);
            case "tutorial" -> openTutorial(player);
            case "crates" -> openCrates(player);
            case "keyshop" -> openKeyShop(player);
            case "rtp" -> randomTeleport(player);
            case "pwarp" -> {
                if (args.length == 0 || args[0].equalsIgnoreCase("list")) openPlayerWarps(player);
                else if (args[0].equalsIgnoreCase("set") && args.length >= 2) setPlayerWarp(player, args[1]);
                else if (args[0].equalsIgnoreCase("delete") && args.length >= 2) deletePlayerWarp(player, args[1]);
                else if (args[0].equalsIgnoreCase("visit") && args.length >= 2) visitPlayerWarp(player, args[1]);
                else player.sendMessage("§7Použi: §f/pwarp [list|set <názov>|delete <názov>|visit <názov>]");
            }
            case "res" -> handleResidence(player, args);
            case "menu" -> {
                if (mode().equals("skyblock")) openSkyBlockMenu(player);
                else openSurvivalMenu(player);
            }
            default -> { return false; }
        }
        return true;
    }

    private boolean handleKeyCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("skybitspawn.build")) { sender.sendMessage("§cNemáš oprávnenie."); return true; }
        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage("§7Použi: §f/skybitkey give <hráč> <basic|vote|rare|epic|legendary> <počet>"); return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { sender.sendMessage("§cHráč nie je online."); return true; }
        try {
            String tier = args.length >= 4 ? crateTier(args[2]) : "basic";
            int amount = Math.max(1, Math.min(64, Integer.parseInt(args.length >= 4 ? args[3] : args[2])));
            giveKeys(target, tier, amount);
            sender.sendMessage("§a" + amount + "× " + crateTierName(tier) + " kľúč bol odovzdaný hráčovi " + target.getName() + ".");
        } catch (NumberFormatException exception) { sender.sendMessage("§cNeplatný počet."); }
        return true;
    }

    private void handleResidence(Player player, String[] args) {
        if (mode().equals("skyblock")) {
            player.sendMessage("§dNa SkyBlocku je tvoj ostrov automaticky chránený. Použi §f/is settings§d.");
            return;
        }
        if (args.length == 0) {
            player.sendMessage("§a§lSKYBIT RESIDENCE §8• §7ochrana cez GriefPrevention");
            player.sendMessage("§f/res create §8• §7vytvoriť claim");
            player.sendMessage("§f/res list §8• §7tvoje claimy");
            player.sendMessage("§f/res trust <hráč> §8• §f/res untrust <hráč>");
            player.sendMessage("§f/res info §8• §f/res delete");
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String target = args.length >= 2 ? " " + args[1] : "";
        switch (sub) {
            case "create" -> Bukkit.dispatchCommand(player, "claim");
            case "list" -> Bukkit.dispatchCommand(player, "claimslist");
            case "trust" -> Bukkit.dispatchCommand(player, "trust" + target);
            case "untrust" -> Bukkit.dispatchCommand(player, "untrust" + target);
            case "info" -> Bukkit.dispatchCommand(player, "trustlist");
            case "delete" -> Bukkit.dispatchCommand(player, "abandonclaim");
            default -> player.sendMessage("§7Použi: §f/res [create|list|trust|untrust|info|delete]");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        if (event.isBedSpawn() || event.isAnchorSpawn()) return;
        World world = Bukkit.getWorld(getConfig().getString("world", "world"));
        if (world != null) {
            event.setRespawnLocation(spawn(world));
            Bukkit.getScheduler().runTask(this, () -> updateLocalDay(event.getPlayer()));
        }
    }

    private void updateLocalDay(Player player) {
        if (protectedLocation(player.getLocation())) player.setPlayerTime(6000L, false);
        else player.resetPlayerTime();
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock() || event.getTo().getWorld() == null) return;
        if (!event.getTo().getWorld().getName().equals(getConfig().getString("world", "world"))) {
            event.getPlayer().resetPlayerTime();
            return;
        }
        if (event.getTo().getY() < getConfig().getDouble("void-rescue-y", -55.0)) {
            event.getPlayer().teleport(spawn(event.getTo().getWorld()));
            return;
        }
        updateLocalDay(event.getPlayer());
    }

    @EventHandler public void onBreak(BlockBreakEvent event) {
        if (crateStations.containsKey(blockKey(event.getBlock().getLocation()))) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cSkyBit crate sa nedá zničiť.");
            return;
        }
        if (protectedLocation(event.getBlock().getLocation()) && !canBuild(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler public void onPlace(BlockPlaceEvent event) {
        if (protectedLocation(event.getBlock().getLocation()) && !canBuild(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (protectedLocation(event.getBlock().getLocation()) && !canBuild(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler public void onBucketFill(PlayerBucketFillEvent event) {
        if (protectedLocation(event.getBlock().getLocation()) && !canBuild(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player && protectedLocation(event.getEntity().getLocation())) event.setCancelled(true);
    }

    @EventHandler public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!protectedLocation(event.getLocation())) return;
        if ((event.getEntity() instanceof Villager || event.getEntity() instanceof Llama)
                && event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) return;
        event.setCancelled(true);
    }

    @EventHandler public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> protectedLocation(block.getLocation()));
    }

    @EventHandler public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> protectedLocation(block.getLocation()));
    }
}
