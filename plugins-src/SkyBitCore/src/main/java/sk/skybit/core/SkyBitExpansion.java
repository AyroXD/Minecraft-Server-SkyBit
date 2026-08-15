package sk.skybit.core;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.function.ToLongFunction;

final class SkyBitExpansion extends PlaceholderExpansion {
    private final SkyBitCore plugin;

    SkyBitExpansion(SkyBitCore plugin) { this.plugin = plugin; }

    @Override public @NotNull String getIdentifier() { return "skybit"; }
    @Override public @NotNull String getAuthor() { return "SkyBit"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        PlayerProfile p = player == null ? null : plugin.profile(player.getUniqueId(), player.getName());
        if (p != null) {
            return switch (params.toLowerCase()) {
                case "level" -> String.valueOf(p.level);
                case "xp" -> String.valueOf(p.xp);
                case "coins" -> String.valueOf(p.coins);
                case "money" -> plugin.formattedMoney(player);
                case "rank" -> plugin.rankPrefix(player);
                case "mine_blocks" -> String.valueOf(plugin.mineRushBlocks(player));
                case "mine_goal" -> String.valueOf(plugin.mineRushGoal());
                case "mine_time" -> plugin.mineRushRemaining();
                case "pass_tier" -> String.valueOf(plugin.serverPassTier(player));
                case "pass_xp" -> String.valueOf(plugin.serverPassXp(player));
                case "playtime_minutes" -> String.valueOf(p.activeSeconds / 60);
                case "kills" -> String.valueOf(p.mobsKilled);
                case "mined" -> String.valueOf(p.blocksMined);
                case "fish" -> String.valueOf(p.fishCaught);
                case "tag" -> plugin.activeTag(player, p);
                default -> top(params.toLowerCase());
            };
        }
        return top(params.toLowerCase());
    }

    private String top(String params) {
        String[] bits = params.split("_");
        if (bits.length != 4 || !bits[0].equals("top")) return null;
        int rank;
        try { rank = Integer.parseInt(bits[2]); } catch (NumberFormatException e) { return null; }
        ToLongFunction<PlayerProfile> metric = switch (bits[1]) {
            case "level" -> p -> p.level;
            case "playtime" -> p -> p.activeSeconds;
            case "kills" -> p -> p.mobsKilled;
            case "miners" -> p -> p.blocksMined;
            case "fishers" -> p -> p.fishCaught;
            default -> null;
        };
        if (metric == null || rank < 1) return null;
        List<PlayerProfile> profiles = plugin.profiles().stream()
                .sorted(Comparator.comparingLong(metric).reversed()).toList();
        if (rank > profiles.size()) return bits[3].equals("name") ? "---" : "0";
        PlayerProfile selected = profiles.get(rank - 1);
        return bits[3].equals("name") ? selected.name : String.valueOf(metric.applyAsLong(selected));
    }
}
