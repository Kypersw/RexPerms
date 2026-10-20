package xyz.rexos.rexperms.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import xyz.rexos.rexperms.RexPermsPlugin;
import xyz.rexos.rexperms.model.User;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Login / logout lifecycle.
 *
 * <ol>
 *   <li>AsyncPlayerPreLoginEvent: data is loaded from storage (off the main thread)</li>
 *   <li>PlayerLoginEvent: the permission handler is injected into the player</li>
 *   <li>PlayerQuitEvent: the user is unloaded after a short delay</li>
 * </ol>
 * If data cannot be loaded the player is politely refused instead of
 * joining with no permissions.
 */
public final class ConnectionListener implements Listener {

    private final RexPermsPlugin plugin;
    /** Loaded during pre-login but not yet seen in the login event. */
    private final Set<UUID> pendingLogin = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());

    public ConnectionListener(RexPermsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        UUID uuid = event.getUniqueId();
        try {
            plugin.getService().loadUserBlocking(uuid, event.getName());
            pendingLogin.add(uuid);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not load permission data for " + event.getName()
                    + " (" + uuid + ")", e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.getMessages().legacy("login.load-failed"));
        }
    }

    /** If another plugin refuses the pre-login after us, drop the loaded data. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLoginMonitor(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED
                && pendingLogin.remove(event.getUniqueId())
                && Bukkit.getPlayer(event.getUniqueId()) == null) {
            plugin.getService().unloadUser(event.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        pendingLogin.remove(player.getUniqueId());
        User user = plugin.getUserManager().get(player.getUniqueId());
        if (user == null) {
            // Pre-login was skipped (e.g. very fast reconnect): stay on the safe side
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, plugin.getMessages().legacy("login.not-loaded"));
            return;
        }
        if (!plugin.getService().inject(player, user)) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, plugin.getMessages().legacy("login.inject-failed"));
        }
    }

    /** Unload if another plugin refuses the login. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoginMonitor(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            final UUID uuid = event.getPlayer().getUniqueId();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (Bukkit.getPlayer(uuid) == null) plugin.getService().unloadUser(uuid);
            }, 20L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        plugin.getVerboseHandler().remove(player);
        // Short delay so quick reconnects do not reload from storage
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (Bukkit.getPlayer(uuid) == null && !pendingLogin.contains(uuid)) {
                plugin.getService().unloadUser(uuid);
            }
        }, 40L);
    }
}
