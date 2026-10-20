package xyz.rexos.rexperms.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import xyz.rexos.rexperms.RexPermsPlugin;
import xyz.rexos.rexperms.inject.PermissibleInjector;
import xyz.rexos.rexperms.inject.RexPermissible;

/** Watches state changes that affect contexts (world, game mode). */
public final class PlayerStateListener implements Listener {

    private final RexPermsPlugin plugin;

    public PlayerStateListener(RexPermsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (!plugin.getSettings().contextGamemode()) return;
        final Player player = event.getPlayer();
        // The game mode has not changed yet during the event; refresh a tick later
        Bukkit.getScheduler().runTask(plugin, () -> refresh(player));
    }

    private void refresh(Player player) {
        RexPermissible rex = PermissibleInjector.getInjected(player);
        if (rex != null) rex.refreshContext();
        plugin.getService().refreshPlayer(player);
    }
}
