package sk.skybit.lobby;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.LookClose;
import net.milkbowl.vault.chat.Chat;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class SkyBitLobby extends JavaPlugin implements Listener {
    private static final int SURFACE_Y = 110;
    private static final String SERVER_MENU_TITLE = "§0☁ SKYBIT • SERVERS";
    private static final LocationData DEFAULT_SPAWN = new LocationData(0.5, 101, 0.5, 180, 0);
    private final Map<UUID, Long> portalCooldown = new HashMap<>();
    private NamespacedKey actionKey;
    private Chat chat;

    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        return worldName.equals("world") && getConfig().getBoolean("generate-void-world", false) ? new VoidGenerator() : null;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        actionKey = new NamespacedKey(this, "lobby_action");
        RegisteredServiceProvider<Chat> chatRegistration = getServer().getServicesManager().getRegistration(Chat.class);
        chat = chatRegistration == null ? null : chatRegistration.getProvider();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        Bukkit.getScheduler().runTaskLater(this, this::prepareLobby, 20L);
        Bukkit.getScheduler().runTaskLater(this, this::initializeRoles, 80L);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) new SkyBitLobbyExpansion(this).register();
    }

    String rankPrefix(OfflinePlayer player) {
        if (player == null || chat == null) return "§8[§7HRÁČ§8]§7";
        String world = player.isOnline() && player.getPlayer() != null ? player.getPlayer().getWorld().getName() : null;
        String value = chat.getPlayerPrefix(world, player);
        return value == null || value.isBlank() ? "§8[§7HRÁČ§8]§7" : org.bukkit.ChatColor.translateAlternateColorCodes('&', value);
    }

    private void initializeRoles() {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) return;
        String[] commands = {
                "lp creategroup vip", "lp creategroup knight", "lp creategroup baron", "lp creategroup king", "lp creategroup emperor", "lp creategroup helper", "lp creategroup admin", "lp creategroup owner",
                "lp group default meta setprefix 0 &8[&7HRÁČ&8]&7",
                "lp group vip parent set default", "lp group vip setweight 20", "lp group vip meta setprefix 20 &8[&6&lVIP&8]&6",
                "lp group knight parent set vip", "lp group knight setweight 25", "lp group knight meta setprefix 25 &8[&f&lKNIGHT&8]&f",
                "lp group baron parent set knight", "lp group baron setweight 30", "lp group baron meta setprefix 30 &8[&d&lBARON&8]&d",
                "lp group king parent set baron", "lp group king setweight 40", "lp group king meta setprefix 40 &8[&e&lKING&8]&e",
                "lp group emperor parent set king", "lp group emperor setweight 45", "lp group emperor meta setprefix 45 &8[&5&lEMPEROR&8]&5",
                "lp group helper parent set default", "lp group helper setweight 50", "lp group helper meta setprefix 50 &8[&3&lHELPER&8]&3",
                "lp group admin parent set helper", "lp group admin setweight 80", "lp group admin meta setprefix 80 &8[&c&lADMIN&8]&c",
                "lp group owner parent set admin", "lp group owner setweight 100", "lp group owner meta setprefix 100 &8[&4&lOWNER&8]&4",
                "lp group owner permission set * true", "lp user Ayro parent set owner"
        };
        for (String command : commands) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        getLogger().info("LuckPerms roles synchronized for lobby.");
    }

    private void prepareLobby() {
        World world = Bukkit.getWorld("world");
        if (world == null) {
            getLogger().severe("Lobby world 'world' was not loaded.");
            return;
        }
        configureWorld(world);
        if (getConfig().getBoolean("build-generated-map", false) && !getConfig().getBoolean("map-built", false)) {
            buildMap(world);
            getConfig().set("map-built", true);
            saveConfig();
            getLogger().info("SkyBit floating lobby map was generated successfully.");
        }
        world.setSpawnLocation(spawn(world));
        refreshWelcomeDisplay(world);
        refreshLobbyNpcs(world);
    }

    private void configureWorld(World world) {
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setTime(6000L);
        world.setStorm(false);
        world.setThundering(false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.FALL_DAMAGE, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
    }

    private Location spawn(World world) {
        return new Location(
                world,
                getConfig().getDouble("spawn.x", DEFAULT_SPAWN.x),
                getConfig().getDouble("spawn.y", DEFAULT_SPAWN.y),
                getConfig().getDouble("spawn.z", DEFAULT_SPAWN.z),
                (float) getConfig().getDouble("spawn.yaw", DEFAULT_SPAWN.yaw),
                (float) getConfig().getDouble("spawn.pitch", DEFAULT_SPAWN.pitch));
    }

    private void refreshWelcomeDisplay(World world) {
        world.getEntitiesByClass(TextDisplay.class).stream()
                .filter(entity -> entity.getScoreboardTags().contains("skybit_lobby_welcome"))
                .forEach(TextDisplay::remove);
        TextDisplay display = world.spawn(spawn(world).add(0, 3.3, 0), TextDisplay.class);
        display.setText("§b§lSKYBIT NETWORK\n§fSURVIVAL §8• §dSKYBLOCK\n§7Vyber si režim cez kompas");
        display.setBillboard(Display.Billboard.CENTER);
        display.setSeeThrough(true);
        display.setShadowed(true);
        display.setBackgroundColor(Color.fromARGB(155, 4, 10, 22));
        display.setViewRange(40.0f);
        display.setPersistent(true);
        display.addScoreboardTag("skybit_lobby_welcome");
    }

    private Location standLocation(World world, double xOffset, double zOffset) {
        Location configured = spawn(world).add(xOffset, 0, zOffset);
        int x = configured.getBlockX();
        int z = configured.getBlockZ();
        int startY = spawn(world).getBlockY() + 6;
        int minimumY = spawn(world).getBlockY() - 10;
        for (int y = startY; y >= minimumY; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.getType().isSolid()) continue;
            double feetY = block.getBoundingBox().getMaxY();
            if (!world.getBlockAt(x, y + 1, z).isPassable() || !world.getBlockAt(x, y + 2, z).isPassable()) continue;
            Location result = new Location(world, x + 0.5, feetY, z + 0.5);
            result.setDirection(spawn(world).toVector().subtract(result.toVector()));
            return result;
        }
        Location fallback = configured.add(0, 1, 0);
        fallback.setDirection(spawn(world).toVector().subtract(fallback.toVector()));
        return fallback;
    }

    private void refreshLobbyNpcs(World world) {
        world.getEntitiesByClass(Villager.class).stream()
                .filter(entity -> entity.getScoreboardTags().contains("skybit_lobby_npc"))
                .forEach(Villager::remove);
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        List<NPC> previous = new ArrayList<>();
        for (NPC npc : registry) if (npc.data().has("skybit_lobby_action")) previous.add(npc);
        previous.forEach(NPC::destroy);
        double z = getConfig().getDouble("npc-stands.z-offset", -14.0);
        createCitizensLobbyNpc(registry, standLocation(world, getConfig().getDouble("npc-stands.left-x", -9.0), z),
                "§b§lSURVIVAL §7• Klikni pre pripojenie", "server:skybit");
        createCitizensLobbyNpc(registry, standLocation(world, getConfig().getDouble("npc-stands.center-x", 0.0), z),
                "§f§lSERVER MENU §7• Vyber režim", "menu");
        createCitizensLobbyNpc(registry, standLocation(world, getConfig().getDouble("npc-stands.right-x", 9.0), z),
                "§d§lSKYBLOCK §7• Klikni pre pripojenie", "server:skyblock");
        registry.saveToStore();
        getLogger().info("Citizens lobby network refreshed: 3 interactive NPCs.");
    }

    private void createCitizensLobbyNpc(NPCRegistry registry, Location location, String name, String action) {
        location.getChunk().load();
        NPC npc = registry.createNPC(EntityType.VILLAGER, name);
        npc.setProtected(true);
        npc.setUseMinecraftAI(false);
        npc.setAlwaysUseNameHologram(true);
        npc.data().setPersistent("skybit_lobby_action", action);
        configurePlayerTracking(npc);
        npc.spawn(location);
        if (npc.getEntity() instanceof Villager villager) {
            villager.setProfession(switch (action) {
                case "server:skybit" -> Villager.Profession.TOOLSMITH;
                case "server:skyblock" -> Villager.Profession.FARMER;
                default -> Villager.Profession.LIBRARIAN;
            });
            villager.setVillagerLevel(5);
            villager.setSilent(true);
            villager.setCollidable(false);
            villager.setRemoveWhenFarAway(false);
        }
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

    private void spawnLobbyNpc(Location location, String name, Villager.Profession profession, String action) {
        Villager npc = location.getWorld().spawn(location, Villager.class);
        npc.setCustomName(name);
        npc.setCustomNameVisible(true);
        npc.setProfession(profession);
        npc.setVillagerLevel(5);
        npc.setAI(false);
        npc.setSilent(true);
        npc.setInvulnerable(true);
        npc.setCollidable(false);
        npc.setRemoveWhenFarAway(false);
        npc.setPersistent(true);
        npc.addScoreboardTag("skybit_lobby_npc");
        npc.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        getLogger().info("Lobby NPC '" + action + "' placed at " + npc.getLocation().getBlockX() + ", "
                + String.format("%.1f", npc.getLocation().getY()) + ", " + npc.getLocation().getBlockZ() + ".");
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

    private void openServerMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, SERVER_MENU_TITLE);
        ItemStack filler = menuItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), "none");
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
        inventory.setItem(11, menuItem(Material.RESPAWN_ANCHOR, "§b§lSKYBIT SURVIVAL",
                List.of("§7Slimefun • Jobs • Questy", "", "§b▶ Pripojiť"), "server:skybit"));
        inventory.setItem(13, menuItem(Material.NETHER_STAR, "§f§lHLAVNÁ LOBBY",
                List.of("§7Nachádzaš sa v hlavnej lobby."), "none"));
        inventory.setItem(15, menuItem(Material.END_STONE, "§d§lSKYBIT SKYBLOCK",
                List.of("§7Ostrovy • Výzvy • Rebríčky", "", "§d▶ Pripojiť"), "server:skyblock"));
        player.openInventory(inventory);
    }

    private void buildMap(World world) {
        buildIsland(world);
        buildPlaza(world);
        buildPortal(world, 38, Material.CYAN_CONCRETE, Material.LIGHT_BLUE_STAINED_GLASS, "SKYBIT SURVIVAL", "Slimefun • Ekonomika • Questy");
        buildPortal(world, -38, Material.PURPLE_CONCRETE, Material.MAGENTA_STAINED_GLASS, "SKYBLOCK", "Ostrovy • Výzvy • Rebríčky");
        buildWelcomeMonument(world);
        buildTrees(world);
        buildLamps(world);
        buildClouds(world);
    }

    private void buildIsland(World world) {
        for (int y = 86; y <= SURFACE_Y; y++) {
            double radius = 12.0 + (y - 86) * 1.5;
            radius = Math.min(radius, 48.0);
            int r = (int) Math.ceil(radius);
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    double distance = Math.sqrt(x * x + z * z);
                    if (distance > radius) continue;
                    Material material;
                    if (y == SURFACE_Y) material = distance > 43 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK;
                    else if (y >= SURFACE_Y - 3) material = Material.DIRT;
                    else if ((x * 31 + z * 17 + y) % 13 == 0) material = Material.CALCITE;
                    else if ((x * 11 + z * 23 + y) % 9 == 0) material = Material.ANDESITE;
                    else material = Material.STONE;
                    set(world, x, y, z, material);
                }
            }
        }
        for (int x = -46; x <= 46; x++) {
            for (int z = -46; z <= 46; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > 46 || d < 42 || (x + z) % 3 != 0) continue;
                set(world, x, SURFACE_Y + 1, z, (x + z) % 2 == 0 ? Material.AZALEA : Material.FLOWERING_AZALEA);
            }
        }
    }

    private void buildPlaza(World world) {
        for (int x = -16; x <= 16; x++) {
            for (int z = -16; z <= 16; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d <= 16) set(world, x, SURFACE_Y, z, d > 13 ? Material.SEA_LANTERN : ((x + z) % 2 == 0 ? Material.SMOOTH_QUARTZ : Material.QUARTZ_BRICKS));
            }
        }
        for (int n = 15; n <= 44; n++) {
            for (int w = -3; w <= 3; w++) {
                Material path = Math.abs(w) == 3 ? Material.CYAN_TERRACOTTA : Material.SMOOTH_QUARTZ;
                set(world, n, SURFACE_Y, w, path);
                set(world, -n, SURFACE_Y, w, path == Material.CYAN_TERRACOTTA ? Material.PURPLE_TERRACOTTA : path);
                set(world, w, SURFACE_Y, n, path);
                set(world, w, SURFACE_Y, -n, path == Material.CYAN_TERRACOTTA ? Material.PURPLE_TERRACOTTA : path);
            }
        }
        for (int y = SURFACE_Y + 1; y <= SURFACE_Y + 4; y++) set(world, 0, y, 0, Material.WATER);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            double d = Math.sqrt(x * x + z * z);
            if (d >= 2.5 && d <= 3.5) set(world, x, SURFACE_Y + 1, z, Material.PRISMARINE_BRICKS);
        }
        set(world, 0, SURFACE_Y, 0, Material.SEA_LANTERN);
        set(world, 0, SURFACE_Y + 5, 0, Material.END_ROD);
    }

    private void buildPortal(World world, int x, Material frame, Material glass, String title, String subtitle) {
        int sign = Integer.signum(x);
        for (int z = -5; z <= 5; z++) {
            for (int y = SURFACE_Y + 1; y <= SURFACE_Y + 10; y++) {
                boolean edge = Math.abs(z) >= 4 || y == SURFACE_Y + 10;
                boolean cornerCut = y >= SURFACE_Y + 8 && Math.abs(z) >= 3;
                if (edge && !cornerCut) set(world, x, y, z, frame);
                else if (!edge && !cornerCut && y <= SURFACE_Y + 8) set(world, x, y, z, glass);
            }
        }
        for (int px = x - sign * 3; px != x + sign * 3; px += sign) {
            for (int z = -5; z <= 5; z++) set(world, px, SURFACE_Y, z, Math.abs(z) == 5 ? frame : Material.SMOOTH_QUARTZ);
        }
        addHologram(world, new Location(world, x - sign * 2.5, SURFACE_Y + 12.0, 0), "§b§l" + title + "\n§f" + subtitle + "\n§ePrejdi portálom");
    }

    private void buildWelcomeMonument(World world) {
        for (int y = SURFACE_Y + 1; y <= SURFACE_Y + 7; y++) {
            set(world, 0, y, -34, y % 2 == 0 ? Material.CYAN_CONCRETE : Material.PURPLE_CONCRETE);
        }
        set(world, 0, SURFACE_Y + 8, -34, Material.BEACON);
        addHologram(world, new Location(world, 0.5, SURFACE_Y + 10.5, -34), "§b§l☁ SKYBIT NETWORK ☁\n§fCZ/SK komunita • progres • eventy\n§7Kompasom si vyber herný režim");
        addHologram(world, new Location(world, 0.5, SURFACE_Y + 4.0, 7), "§b§lVITAJ NA SKYBITE\n§fVyber si portál alebo použi server selector");
    }

    private void buildTrees(World world) {
        int[][] positions = {{25, 25}, {-25, 25}, {25, -25}, {-25, -25}};
        for (int[] p : positions) {
            for (int y = 1; y <= 6; y++) set(world, p[0], SURFACE_Y + y, p[1], Material.CHERRY_LOG);
            for (int dx = -4; dx <= 4; dx++) for (int dy = -2; dy <= 3; dy++) for (int dz = -4; dz <= 4; dz++) {
                if (dx * dx + dz * dz + dy * dy * 2 > 18 || (dx == 0 && dz == 0 && dy < 1)) continue;
                set(world, p[0] + dx, SURFACE_Y + 6 + dy, p[1] + dz, Material.CHERRY_LEAVES);
            }
            set(world, p[0] + 2, SURFACE_Y + 1, p[1], Material.LANTERN);
        }
    }

    private void buildLamps(World world) {
        int[][] positions = {{18, 6}, {18, -6}, {-18, 6}, {-18, -6}, {6, 18}, {-6, 18}, {6, -18}, {-6, -18}};
        for (int[] p : positions) {
            set(world, p[0], SURFACE_Y + 1, p[1], Material.POLISHED_DEEPSLATE_WALL);
            set(world, p[0], SURFACE_Y + 2, p[1], Material.POLISHED_DEEPSLATE_WALL);
            set(world, p[0], SURFACE_Y + 3, p[1], Material.SOUL_LANTERN);
        }
    }

    private void buildClouds(World world) {
        int[][] centers = {{19, 100, 37}, {-29, 96, 33}, {31, 94, -31}, {-35, 102, -24}};
        for (int[] c : centers) {
            for (int dx = -5; dx <= 5; dx++) for (int dz = -3; dz <= 3; dz++) {
                if (dx * dx / 25.0 + dz * dz / 9.0 <= 1.0) set(world, c[0] + dx, c[1], c[2] + dz, Material.WHITE_STAINED_GLASS);
            }
        }
    }

    private void addHologram(World world, Location location, String text) {
        TextDisplay display = world.spawn(location, TextDisplay.class);
        display.setText(text);
        display.setBillboard(Display.Billboard.CENTER);
        display.setSeeThrough(true);
        display.setShadowed(true);
        display.setBackgroundColor(Color.fromARGB(120, 5, 12, 24));
        display.setViewRange(48.0f);
        display.setPersistent(true);
    }

    private void set(World world, int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material, false);
    }

    private void sendTo(Player player, String server) {
        long now = System.currentTimeMillis();
        if (now - portalCooldown.getOrDefault(player.getUniqueId(), 0L) < 3000L) return;
        portalCooldown.put(player.getUniqueId(), now);
        ByteArrayDataOutput output = ByteStreams.newDataOutput();
        output.writeUTF("Connect");
        output.writeUTF(server);
        player.sendPluginMessage(this, "BungeeCord", output.toByteArray());
    }

    @EventHandler
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Villager npc)
                || !npc.getScoreboardTags().contains("skybit_lobby_npc")) return;
        event.setCancelled(true);
        String action = npc.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action == null) return;
        handleLobbyNpcAction(event.getPlayer(), action);
    }

    @EventHandler
    public void onCitizensNpcClick(NPCRightClickEvent event) {
        String action = event.getNPC().data().get("skybit_lobby_action", "");
        if (action.isBlank()) return;
        event.setCancelled(true);
        handleLobbyNpcAction(event.getClicker(), action);
    }

    private void handleLobbyNpcAction(Player player, String action) {
        if (action.equals("menu")) openServerMenu(player);
        else if (action.startsWith("server:")) sendTo(player, action.substring("server:".length()));
    }

    @EventHandler
    public void onServerMenuClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(SERVER_MENU_TITLE)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        String action = item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action != null && action.startsWith("server:")) {
            player.closeInventory();
            sendTo(player, action.substring("server:".length()));
        }
    }

    private void syncNetworkRank(Player player) {
        if (player.hasPermission("group.owner") || player.hasPermission("group.admin") || player.hasPermission("group.helper")) return;
        File directory = new File(getDataFolder(), getConfig().getString("network-rank-directory", "../../../../shared-data/ranks"));
        File file = new File(directory, player.getUniqueId() + ".txt");
        if (!file.isFile()) return;
        try {
            String rank = Files.readString(file.toPath(), StandardCharsets.UTF_8).trim().toLowerCase();
            if (List.of("vip", "knight", "baron", "king", "emperor").contains(rank) && !player.hasPermission("group." + rank))
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + player.getName() + " parent set " + rank);
        } catch (IOException exception) {
            getLogger().warning("Network rank could not be read for " + player.getName() + ": " + exception.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.setFlying(false);
        player.setAllowFlight(false);
        Bukkit.getScheduler().runTask(this, () -> {
            World world = Bukkit.getWorld("world");
            if (world == null) return;
            player.teleport(spawn(world));
            player.setGameMode(GameMode.ADVENTURE);
            player.setHealth(player.getMaxHealth());
            player.setFoodLevel(20);
            player.setSaturation(20);
            player.setAllowFlight(false);
            player.setFlying(false);
            player.getInventory().setHeldItemSlot(4);
            syncNetworkRank(player);
            player.sendTitle("§b§lSKYBIT", "§fVitaj v hlavnej lobby", 10, 45, 15);
        });
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) return;
        Location location = event.getTo();
        Player player = event.getPlayer();
        if (!location.getWorld().getName().equals("world")) return;
        if (location.getY() < getConfig().getDouble("void-rescue-y", -55)) {
            player.teleport(spawn(location.getWorld()));
            return;
        }
        if (!getConfig().getBoolean("portals-enabled", false)) return;
        if (location.getY() < SURFACE_Y || location.getY() > SURFACE_Y + 10 || Math.abs(location.getZ()) > 4) return;
        if (location.getX() >= 36 && location.getX() <= 40) sendTo(player, "skybit");
        else if (location.getX() <= -36 && location.getX() >= -40) sendTo(player, "skyblock");
    }

    @EventHandler public void onBreak(BlockBreakEvent e) { if (!e.getPlayer().hasPermission("skybitlobby.build")) e.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent e) { if (!e.getPlayer().hasPermission("skybitlobby.build")) e.setCancelled(true); }
    @EventHandler public void onDamage(EntityDamageEvent e) { if (e.getEntity() instanceof Player) e.setCancelled(true); }
    @EventHandler public void onHunger(FoodLevelChangeEvent e) { e.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { e.setCancelled(true); }
    @EventHandler public void onWeather(WeatherChangeEvent e) { if (e.toWeatherState()) e.setCancelled(true); }
    @EventHandler public void onSpawn(CreatureSpawnEvent e) { e.setCancelled(true); }
    @EventHandler public void onBurn(BlockBurnEvent e) { e.setCancelled(true); }
    @EventHandler public void onDecay(LeavesDecayEvent e) { e.setCancelled(true); }

    private record LocationData(double x, double y, double z, float yaw, float pitch) {}
}
