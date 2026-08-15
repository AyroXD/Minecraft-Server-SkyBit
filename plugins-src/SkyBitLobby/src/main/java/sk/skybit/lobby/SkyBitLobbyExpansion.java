package sk.skybit.lobby;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class SkyBitLobbyExpansion extends PlaceholderExpansion {
    private final SkyBitLobby plugin;

    SkyBitLobbyExpansion(SkyBitLobby plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String getIdentifier() { return "skybitlobby"; }
    @Override public @NotNull String getAuthor() { return "SkyBit"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        return params.equalsIgnoreCase("rank") ? plugin.rankPrefix(player) : null;
    }
}
