package xyz.rexos.rexperms.api.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Preflight authorization for administrative lifecycle operations. Unlike post-change
 * notifications, cancellation happens before mutation. No event listener is required for normal
 * backwards-compatible operation.
 */
public final class PreAdministrativeOperationEvent extends Event implements Cancellable {
    public enum Operation {
        GROUP_CREATE,
        GROUP_DELETE,
        GROUP_CLONE,
        GROUP_RENAME,
        TRACK_CREATE,
        TRACK_MODIFY,
        TRACK_DELETE
    }

    public enum Origin {
        PLAYER,
        CONSOLE,
        API,
        VAULT,
        SYSTEM,
        NETWORK
    }

    private static final HandlerList HANDLERS = new HandlerList();
    private final Operation operation;
    private final Origin origin;
    private final String actorIdentifier;
    private final String target;
    private final String destination;
    private boolean cancelled;

    public PreAdministrativeOperationEvent(
            Operation operation,
            Origin origin,
            String actorIdentifier,
            String target,
            String destination) {
        this.operation = operation;
        this.origin = origin;
        this.actorIdentifier = actorIdentifier;
        this.target = target;
        this.destination = destination;
    }

    public Operation getOperation() {
        return operation;
    }

    public Origin getOrigin() {
        return origin;
    }

    public String getActorIdentifier() {
        return actorIdentifier;
    }

    public String getTarget() {
        return target;
    }

    public String getDestination() {
        return destination;
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
