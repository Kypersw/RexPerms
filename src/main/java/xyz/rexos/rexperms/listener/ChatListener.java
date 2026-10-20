package xyz.rexos.rexperms.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import xyz.rexos.rexperms.RexPermsPlugin;
import xyz.rexos.rexperms.calc.MetaData;
import xyz.rexos.rexperms.inject.PermissibleInjector;
import xyz.rexos.rexperms.inject.RexPermissible;
import xyz.rexos.rexperms.model.User;
import xyz.rexos.rexperms.util.Text;

/**
 * Optional chat formatting (config: chat.enabled).
 *
 * Uses {@link AsyncPlayerChatEvent#setFormat}, which exists on every version
 * from 1.8 to today and leaves the message itself untouched, so it stays
 * compatible with chat signing on modern servers.
 */
public final class ChatListener implements Listener {

    private final RexPermsPlugin plugin;

    public ChatListener(RexPermsPlugin plugin) {
        this.plugin = plugin;
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!plugin.getSettings().chatEnabled()) return;
        Player player = event.getPlayer();
        User user = plugin.getUserManager().get(player.getUniqueId());
        RexPermissible rex = PermissibleInjector.getInjected(player);
        if (user == null || rex == null) return;

        MetaData meta = user.getCachedData().meta(rex.getContext(), plugin.getCalculator());

        if (player.hasPermission(plugin.getSettings().chatColorPermission())) {
            event.setMessage(Text.legacy(event.getMessage()));
        }

        // Placeholders that must survive colour conversion untouched
        String nameToken = "\u0000N\u0000";
        String displayToken = "\u0000D\u0000";
        String messageToken = "\u0000M\u0000";
        String raw = plugin.getSettings().chatFormat()
                .replace("{prefix}", meta.prefix() == null ? "" : meta.prefix())
                .replace("{suffix}", meta.suffix() == null ? "" : meta.suffix())
                .replace("{group}", meta.primaryGroup())
                .replace("{world}", player.getWorld().getName())
                .replace("{name}", nameToken)
                .replace("{displayname}", displayToken)
                .replace("{message}", messageToken);

        // String.format: escape '%' coming from prefixes, then insert the Bukkit arguments
        String format = Text.legacy(raw).replace("%", "%%")
                .replace(nameToken, "%1$s")
                .replace(displayToken, "%1$s")
                .replace(messageToken, "%2$s");
        try {
            event.setFormat(format);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Invalid chat format in config.yml: " + e.getMessage());
        }
    }
}
