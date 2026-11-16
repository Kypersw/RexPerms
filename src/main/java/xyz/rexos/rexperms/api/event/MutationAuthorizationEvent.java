package xyz.rexos.rexperms.api.event;

import org.bukkit.command.CommandSender;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import xyz.rexos.rexperms.model.HolderType;

/**
 * A pre-mutation authorization request for integrations.
 *
 * <p>This event is an API building block. It is only effective when dispatched
 * by a mutation path BEFORE any in-memory state changes. Callers must abort
 * the entire operation when cancelled; cancelling a later save is insufficient.
 *
 * <p>A null actor represents an un-attributed operation, NOT the console.
 * Security integrations should deny unknown actors by default.
 */
public final class MutationAuthorizationEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final CommandSender actor;
    private final HolderType targetType;
    private final String targetIdentifier;
    private final String operation;
    private final String detail;
    private boolean cancelled;

    public MutationAuthorizationEvent(CommandSender actor, HolderType targetType,
                                      String targetIdentifier, String operation, String detail) {
        this.actor = actor;
        this.targetType = targetType;
        this.targetIdentifier = targetIdentifier;
        this.operation = operation;
        this.detail = detail;
    }

    /** Null means unknown/system/API, never implicitly console. */
    public CommandSender getActor() { return actor; }

    public HolderType getTargetType() { return targetType; }

    public String getTargetIdentifier() { return targetIdentifier; }

    /** Machine-readable operation key, e.g. user.parent.add. */
    public String getOperation() { return operation; }

    /** Optional human-readable context; not a security decision input. */
    public String getDetail() { return detail; }

    @Override
    public boolean isCancelled() { return cancelled; }

    @Override
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
