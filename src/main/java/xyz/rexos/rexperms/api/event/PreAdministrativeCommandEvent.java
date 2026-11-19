package xyz.rexos.rexperms.api.event;

import org.bukkit.command.CommandSender;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Arrays;

/** Fired synchronously before a RexPerms command is dispatched, including async user lookups. */
public final class PreAdministrativeCommandEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final CommandSender sender;
    private final String[] arguments;
    private boolean cancelled;

    public PreAdministrativeCommandEvent(CommandSender sender, String[] arguments) {
        this.sender = sender;
        this.arguments = Arrays.copyOf(arguments, arguments.length);
    }

    public CommandSender getSender() {
        return sender;
    }

    public String[] getArguments() {
        return Arrays.copyOf(arguments, arguments.length);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean value) {
        cancelled = value;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
