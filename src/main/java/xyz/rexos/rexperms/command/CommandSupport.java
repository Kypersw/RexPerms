package xyz.rexos.rexperms.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import xyz.rexos.rexperms.RexPermsPlugin;
import xyz.rexos.rexperms.config.Messages;
import xyz.rexos.rexperms.model.ContextSet;
import xyz.rexos.rexperms.model.Node;
import xyz.rexos.rexperms.util.Durations;
import xyz.rexos.rexperms.util.Text;

import java.util.List;

/** Helpers shared by the command classes. */
abstract class CommandSupport {

    static final int PAGE_SIZE = 10;

    interface LineRenderer<T> {
        Component render(T item);
    }

    protected final RexPermsPlugin plugin;

    CommandSupport(RexPermsPlugin plugin) {
        this.plugin = plugin;
    }

    protected Messages msg() {
        return plugin.getMessages();
    }

    /** Permission check; sends a message if missing. The console is always allowed. */
    protected boolean allowed(CommandSender sender, String path) {
        if (sender instanceof ConsoleCommandSender) return true;
        if (sender.hasPermission("rexperms." + path)) return true;
        msg().send(sender, "general.no-permission", Messages.text("permission", "rexperms." + path));
        return false;
    }

    protected void usage(CommandSender sender, String usage) {
        msg().send(sender, "general.usage", Messages.text("usage", usage));
    }

    protected static Component value(boolean v) {
        return v ? Component.text("true", NamedTextColor.GREEN) : Component.text("false", NamedTextColor.RED);
    }

    protected static TagResolver ctx(ContextSet ctx) {
        return Messages.text("context", Text.contextSuffix(ctx));
    }

    protected static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    /** Remaining-time text for temporary nodes; empty for permanent ones. */
    protected static String expiryText(Node n) {
        if (!n.isTemporary()) return "";
        return " (" + Durations.format(n.secondsLeft(nowSeconds())) + " left)";
    }

    /** Sends a paginated list. The header gets page, pages and count placeholders. */
    protected <T> void paginate(CommandSender sender, List<T> items, int page, String header,
                                TagResolver headerResolver, LineRenderer<T> lines) {
        int pages = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int p = Math.max(1, Math.min(page, pages));
        msg().send(sender, header, headerResolver,
                Messages.text("page", p), Messages.text("pages", pages), Messages.text("count", items.size()));
        int start = (p - 1) * PAGE_SIZE;
        for (int i = start; i < Math.min(items.size(), start + PAGE_SIZE); i++) {
            msg().send(sender, lines.render(items.get(i)));
        }
    }

    /** Component that suggests a command on click and explains it on hover. */
    protected static Component clickable(Component base, String suggest, String hover) {
        return base.clickEvent(ClickEvent.suggestCommand(suggest))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }

    protected static int parsePage(Args a, int index) {
        if (!a.has(index)) return 1;
        Integer p = Args.parseInt(a.get(index));
        return p == null ? 1 : p;
    }
}
