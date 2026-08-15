package sk.skybit.auth;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

@Plugin(
    id = "skybit-auth",
    name = "SkyBitAuth",
    version = "1.0.0",
    description = "Secure registration and login gate for the SkyBit offline-mode network",
    authors = {"SkyBit"}
)
public final class SkyBitAuth {
    private static final int MIN_PASSWORD = 8;
    private static final int MAX_PASSWORD = 64;
    private static final int MAX_FAILURES = 5;
    private static final long LOCK_MILLIS = 30_000L;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> promptThrottle = new ConcurrentHashMap<>();
    private PasswordStore passwords;

    @Inject
    public SkyBitAuth(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            passwords = new PasswordStore(dataDirectory);
        } catch (Exception exception) {
            logger.error("Cannot initialize the SkyBitAuth account store", exception);
            return;
        }
        register("register", new RegisterCommand());
        register("login", new LoginCommand(), "log", "l");
        register("changepassword", new ChangePasswordCommand(), "changepass", "cp");
        register("logout", new LogoutCommand());
        logger.info("SkyBitAuth is ready. Passwords use salted PBKDF2-HMAC-SHA256 hashes.");
    }

    private void register(String name, SimpleCommand command, String... aliases) {
        CommandManager manager = proxy.getCommandManager();
        CommandMeta meta = manager.metaBuilder(name).aliases(aliases).plugin(this).build();
        manager.register(meta, command);
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        authenticated.remove(player.getUniqueId());
        proxy.getScheduler().buildTask(this, () -> prompt(player)).delay(Duration.ofMillis(500)).schedule();
        proxy.getScheduler().buildTask(this, () -> {
            if (player.isActive() && !authenticated.contains(player.getUniqueId())) {
                player.disconnect(Component.text("Čas na prihlásenie vypršal. Pripoj sa znova.", NamedTextColor.RED));
            }
        }).delay(Duration.ofMinutes(2)).schedule();
    }

    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        if (!authenticated.contains(event.getPlayer().getUniqueId())) {
            proxy.getServer("lobby").ifPresent(event::setInitialServer);
        }
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (!authenticated.contains(event.getPlayer().getUniqueId())
                && !event.getOriginalServer().getServerInfo().getName().equalsIgnoreCase("lobby")) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            prompt(event.getPlayer());
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        authenticated.remove(uuid);
        promptThrottle.remove(uuid);
    }

    private void prompt(Player player) {
        long now = System.currentTimeMillis();
        if (now - promptThrottle.getOrDefault(player.getUniqueId(), 0L) < 1500L) return;
        promptThrottle.put(player.getUniqueId(), now);
        if (passwords != null && passwords.exists(player.getUsername())) {
            message(player, "Použi /login <heslo>", NamedTextColor.YELLOW);
        } else {
            message(player, "Použi /register <heslo> <heslo>", NamedTextColor.AQUA);
        }
    }

    private void completeAuthentication(Player player) {
        if (!player.isActive()) return;
        authenticated.add(player.getUniqueId());
        attempts.remove(player.getUsername().toLowerCase(Locale.ROOT));
        message(player, "Úspešne prihlásený. Pripájam ťa do lobby...", NamedTextColor.GREEN);
        proxy.getServer("lobby").ifPresent(server -> player.createConnectionRequest(server).fireAndForget());
    }

    private boolean validPassword(String password) {
        return password.length() >= MIN_PASSWORD && password.length() <= MAX_PASSWORD && password.chars().noneMatch(Character::isWhitespace);
    }

    private long lockRemaining(String username) {
        Attempt attempt = attempts.get(username.toLowerCase(Locale.ROOT));
        if (attempt == null) return 0L;
        return Math.max(0L, attempt.lockedUntil - System.currentTimeMillis());
    }

    private void recordFailure(String username) {
        String key = username.toLowerCase(Locale.ROOT);
        attempts.compute(key, (ignored, current) -> {
            long now = System.currentTimeMillis();
            if (current == null || current.lockedUntil > 0 && current.lockedUntil <= now) current = new Attempt(0, 0L);
            int failures = current.failures + 1;
            return failures >= MAX_FAILURES ? new Attempt(0, now + LOCK_MILLIS) : new Attempt(failures, 0L);
        });
    }

    private void message(Player player, String text, NamedTextColor color) {
        player.sendMessage(Component.text("[SkyBit] ", NamedTextColor.AQUA).append(Component.text(text, color)));
    }

    private abstract class PlayerCommand implements SimpleCommand {
        @Override
        public final void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) {
                invocation.source().sendMessage(Component.text("Tento príkaz môže použiť iba hráč."));
                return;
            }
            execute(player, invocation.arguments());
        }

        abstract void execute(Player player, String[] arguments);
    }

    private final class RegisterCommand extends PlayerCommand {
        @Override
        void execute(Player player, String[] arguments) {
            if (passwords == null) { message(player, "Autentizácia nie je dostupná.", NamedTextColor.RED); return; }
            if (authenticated.contains(player.getUniqueId())) { message(player, "Už si prihlásený.", NamedTextColor.YELLOW); return; }
            if (passwords.exists(player.getUsername())) { message(player, "Účet už existuje. Použi /login <heslo>.", NamedTextColor.RED); return; }
            if (arguments.length != 2) { message(player, "Použitie: /register <heslo> <heslo>", NamedTextColor.YELLOW); return; }
            if (!arguments[0].equals(arguments[1])) { message(player, "Heslá sa nezhodujú.", NamedTextColor.RED); return; }
            if (!validPassword(arguments[0])) { message(player, "Heslo musí mať 8–64 znakov a nesmie obsahovať medzery.", NamedTextColor.RED); return; }
            CompletableFuture.runAsync(() -> {
                try {
                    if (passwords.create(player.getUsername(), arguments[0])) completeAuthentication(player);
                    else message(player, "Účet už existuje.", NamedTextColor.RED);
                } catch (Exception exception) {
                    logger.error("Could not register account {}", player.getUsername(), exception);
                    message(player, "Registrácia zlyhala. Skús to neskôr.", NamedTextColor.RED);
                }
            });
        }
    }

    private final class LoginCommand extends PlayerCommand {
        @Override
        void execute(Player player, String[] arguments) {
            if (passwords == null) { message(player, "Autentizácia nie je dostupná.", NamedTextColor.RED); return; }
            if (authenticated.contains(player.getUniqueId())) { message(player, "Už si prihlásený.", NamedTextColor.YELLOW); return; }
            if (arguments.length != 1) { message(player, "Použitie: /login <heslo>", NamedTextColor.YELLOW); return; }
            if (!passwords.exists(player.getUsername())) { message(player, "Účet neexistuje. Použi /register <heslo> <heslo>.", NamedTextColor.RED); return; }
            long remaining = lockRemaining(player.getUsername());
            if (remaining > 0) { message(player, "Priveľa pokusov. Počkaj " + Math.max(1, remaining / 1000) + " sekúnd.", NamedTextColor.RED); return; }
            CompletableFuture.runAsync(() -> {
                if (passwords.verify(player.getUsername(), arguments[0])) completeAuthentication(player);
                else {
                    recordFailure(player.getUsername());
                    message(player, "Nesprávne heslo.", NamedTextColor.RED);
                }
            });
        }
    }

    private final class ChangePasswordCommand extends PlayerCommand {
        @Override
        void execute(Player player, String[] arguments) {
            if (!authenticated.contains(player.getUniqueId())) { prompt(player); return; }
            if (arguments.length != 2) { message(player, "Použitie: /changepassword <staré> <nové>", NamedTextColor.YELLOW); return; }
            if (!validPassword(arguments[1])) { message(player, "Nové heslo musí mať 8–64 znakov bez medzier.", NamedTextColor.RED); return; }
            CompletableFuture.runAsync(() -> {
                try {
                    if (passwords.change(player.getUsername(), arguments[0], arguments[1])) message(player, "Heslo bolo zmenené.", NamedTextColor.GREEN);
                    else message(player, "Staré heslo nie je správne.", NamedTextColor.RED);
                } catch (Exception exception) {
                    logger.error("Could not change password for {}", player.getUsername(), exception);
                    message(player, "Heslo sa nepodarilo zmeniť.", NamedTextColor.RED);
                }
            });
        }
    }

    private final class LogoutCommand extends PlayerCommand {
        @Override
        void execute(Player player, String[] arguments) {
            authenticated.remove(player.getUniqueId());
            player.disconnect(Component.text("Bol si bezpečne odhlásený zo SkyBit siete.", NamedTextColor.AQUA));
        }
    }

    private record Attempt(int failures, long lockedUntil) {}
}
