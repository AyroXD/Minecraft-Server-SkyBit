package sk.skybit.core;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WebBridge {
    private static final Pattern ORDER = Pattern.compile("\\{[^{}]*\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"[^{}]*\\\"playerName\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"[^{}]*\\\"skycoins\\\"\\s*:\\s*(\\d+)[^{}]*}");
    private final SkyBitCore plugin;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final Set<String> fulfilled = new HashSet<>();
    private final SecureRandom random = new SecureRandom();
    private final File ledgerFile;
    private YamlConfiguration ledger;
    private int taskId = -1;
    private String baseUrl;
    private String secret;
    private String siteAccessToken;

    WebBridge(SkyBitCore plugin) {
        this.plugin = plugin;
        this.ledgerFile = new File(plugin.getDataFolder(), "web-orders.yml");
        this.ledger = YamlConfiguration.loadConfiguration(ledgerFile);
        fulfilled.addAll(ledger.getStringList("fulfilled"));
    }

    void start() {
        if (!plugin.getConfig().getBoolean("web-bridge.enabled", false)) return;
        baseUrl = plugin.getConfig().getString("web-bridge.url", "").replaceAll("/+$", "");
        secret = plugin.getConfig().getString("web-bridge.secret", "");
        siteAccessToken = plugin.getConfig().getString("web-bridge.site-access-token", "");
        if (!baseUrl.startsWith("https://") || secret.length() < 24) {
            plugin.getLogger().warning("Web bridge is enabled but URL or secret is invalid; bridge stays disabled.");
            return;
        }
        long fallbackSeconds = Math.max(1, plugin.getConfig().getLong("web-bridge.sync-minutes", 5)) * 60L;
        long seconds = Math.max(5, plugin.getConfig().getLong("web-bridge.sync-seconds", fallbackSeconds));
        taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::syncSafely, 20L, seconds * 20L).getTaskId();
        plugin.getLogger().info("SkyBit website bridge enabled for " + plugin.serverMode() + " (every " + seconds + "s).");
    }

    void stop() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
    }

    private void syncSafely() {
        try {
            pushSnapshot();
            pullOrders();
        } catch (Exception exception) {
            plugin.getLogger().warning("Website sync failed: " + exception.getMessage());
        }
    }

    private void pushSnapshot() throws IOException, InterruptedException {
        StringBuilder players = new StringBuilder("[");
        boolean first = true;
        for (PlayerProfile profile : plugin.profiles()) {
            org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(profile.uuid);
            if (!first) players.append(',');
            first = false;
            players.append('{')
                    .append("\"uuid\":\"").append(profile.uuid).append("\",")
                    .append("\"username\":\"").append(json(profile.name)).append("\",")
                    .append("\"level\":").append(profile.level).append(',')
                    .append("\"skycoins\":").append(profile.coins).append(',')
                    .append("\"playtimeMinutes\":").append(profile.activeSeconds / 60).append(',')
                    .append("\"blocksMined\":").append(profile.blocksMined).append('}');
            int end = players.length() - 1;
            players.deleteCharAt(end)
                    .append(',').append("\"money\":").append(Math.round(plugin.balance(offline))).append(',')
                    .append("\"role\":\"").append(json(plugin.roleName(offline))).append("\",")
                    .append("\"serverPassTier\":").append(plugin.serverPassTier(offline)).append(',')
                    .append("\"online\":").append(offline.isOnline()).append('}');
        }
        players.append(']');
        double tps = Bukkit.getTPS().length == 0 ? 20.0 : Bukkit.getTPS()[0];
        String body = "{\"mode\":\"" + json(plugin.serverMode()) + "\",\"online\":" + Bukkit.getOnlinePlayers().size()
                + ",\"maxPlayers\":" + Bukkit.getMaxPlayers() + ",\"tps\":" + String.format(java.util.Locale.US, "%.2f", tps)
                + ",\"players\":" + players + "}";
        HttpResponse<String> response = send("/api/server/sync", "POST", body);
        if (response.statusCode() / 100 != 2) throw new IOException("snapshot HTTP " + response.statusCode());
    }

    private void pullOrders() throws IOException, InterruptedException {
        HttpResponse<String> response = send("/api/server/orders", "GET", null);
        if (response.statusCode() / 100 != 2) throw new IOException("orders HTTP " + response.statusCode());
        Matcher matcher = ORDER.matcher(response.body());
        while (matcher.find()) {
            String id = matcher.group(1);
            if (fulfilled.contains(id)) { acknowledge(id); continue; }
            String playerName = matcher.group(2);
            long coins = Long.parseLong(matcher.group(3));
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!fulfilled.add(id)) return;
                plugin.grantWebCoins(playerName, coins);
                ledger.set("fulfilled", fulfilled.stream().sorted().toList());
                try { ledger.save(ledgerFile); }
                catch (IOException exception) { plugin.getLogger().severe("Cannot save web order ledger: " + exception.getMessage()); }
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> acknowledge(id));
            });
        }
    }

    private void acknowledge(String id) {
        try {
            HttpResponse<String> response = send("/api/server/orders", "POST", "{\"id\":\"" + json(id) + "\"}");
            if (response.statusCode() / 100 != 2) plugin.getLogger().warning("Order acknowledgement failed for " + id + ": HTTP " + response.statusCode());
        } catch (Exception exception) {
            plugin.getLogger().warning("Order acknowledgement failed for " + id + ": " + exception.getMessage());
        }
    }

    private HttpResponse<String> send(String path, String method, String body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15)).header("X-SkyBit-Key", secret).header("Accept", "application/json");
        if (siteAccessToken != null && !siteAccessToken.isBlank())
            builder.header("OAI-Sites-Authorization", "Bearer " + siteAccessToken);
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json; charset=utf-8")
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    String createLinkCode(org.bukkit.entity.Player player) {
        if (taskId == -1 || player == null) return null;
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder(6);
        for (int index = 0; index < 6; index++) code.append(alphabet.charAt(random.nextInt(alphabet.length())));
        String value = code.toString();
        String body = "{\"codeHash\":\"" + sha256(value) + "\",\"playerUuid\":\"" + player.getUniqueId()
                + "\",\"playerName\":\"" + json(player.getName()) + "\",\"role\":\"" + json(plugin.roleName(player)) + "\"}";
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpResponse<String> response = send("/api/server/link-code", "POST", body);
                if (response.statusCode() / 100 != 2) plugin.getLogger().warning("Web link code rejected: HTTP " + response.statusCode());
            } catch (Exception exception) { plugin.getLogger().warning("Web link code failed: " + exception.getMessage()); }
        });
        return value;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
}
