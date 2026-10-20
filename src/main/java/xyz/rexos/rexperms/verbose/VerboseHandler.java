package xyz.rexos.rexperms.verbose;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import xyz.rexos.rexperms.config.Messages;

import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live permission check monitor (/rp verbose on [filter]).
 *
 * Checks can happen on any thread (e.g. async chat), so records are queued
 * and flushed on the main thread every tick. When nobody listens,
 * {@link #record} costs next to nothing.
 */
public final class VerboseHandler {

    private static final class Check {
        final String player;
        final String permission;
        final boolean result;
        final String cause;

        Check(String player, String permission, boolean result, String cause) {
            this.player = player;
            this.permission = permission;
            this.result = result;
            this.cause = cause;
        }
    }

    private static final int MAX_QUEUE = 2_000;
    private static final int MAX_PER_TICK = 50;

    private final Map<CommandSender, String> listeners = new ConcurrentHashMap<CommandSender, String>();
    private final Queue<Check> queue = new ConcurrentLinkedQueue<Check>();
    private final AtomicInteger queued = new AtomicInteger();
    private volatile boolean active;
    private final Messages messages;

    public VerboseHandler(Messages messages) {
        this.messages = messages;
    }

    /**
     * @param filter player name or part of a permission; empty = everything
     */
    public void enable(CommandSender sender, String filter) {
        listeners.put(sender, filter == null ? "" : filter.toLowerCase(Locale.ROOT));
        active = true;
    }

    public boolean disable(CommandSender sender) {
        boolean removed = listeners.remove(sender) != null;
        active = !listeners.isEmpty();
        return removed;
    }

    public boolean isListening(CommandSender sender) {
        return listeners.containsKey(sender);
    }

    public void record(String player, String permission, boolean result, String cause) {
        if (!active) return;
        if (queued.get() >= MAX_QUEUE) return;
        queue.add(new Check(player, permission, result, cause));
        queued.incrementAndGet();
    }

    /** Called on the main thread every tick. */
    public void flush() {
        if (!active) {
            if (queued.get() > 0) {
                queue.clear();
                queued.set(0);
            }
            return;
        }
        for (int i = 0; i < MAX_PER_TICK; i++) {
            Check c = queue.poll();
            if (c == null) break;
            queued.decrementAndGet();
            Component line = format(c);
            for (Map.Entry<CommandSender, String> e : listeners.entrySet()) {
                String filter = e.getValue();
                if (filter.isEmpty()
                        || c.player.toLowerCase(Locale.ROOT).equals(filter)
                        || c.permission.contains(filter)) {
                    messages.send(e.getKey(), line);
                }
            }
        }
    }

    private Component format(Check c) {
        Component hover = Component.text()
                .append(Component.text("Source: ", NamedTextColor.GRAY))
                .append(Component.text(c.cause == null ? "-" : c.cause, NamedTextColor.WHITE))
                .build();
        return messages.component("verbose.entry",
                        Messages.text("player", c.player),
                        Messages.text("permission", c.permission),
                        Messages.comp("result", c.result
                                ? Component.text("true", NamedTextColor.GREEN)
                                : Component.text("false", NamedTextColor.RED)))
                .hoverEvent(HoverEvent.showText(hover));
    }

    /** Removes a player who left. */
    public void remove(CommandSender sender) {
        disable(sender);
    }
}
