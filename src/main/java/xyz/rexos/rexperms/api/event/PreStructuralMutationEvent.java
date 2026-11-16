package xyz.rexos.rexperms.api.event;

import org.bukkit.event.*;

import xyz.rexos.rexperms.api.mutation.MutationContext;

/**
 * Immutable snapshots are lists/maps supplied by the coordinator; cancellation vetoes the whole
 * unit of work.
 */
public final class PreStructuralMutationEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String kind, identifier;
    private final Object before, after;
    private final MutationContext context;
    private boolean cancelled;

    public PreStructuralMutationEvent(
            String kind, String identifier, Object before, Object after, MutationContext context) {
        this.kind = kind;
        this.identifier = identifier;
        this.before = before;
        this.after = after;
        this.context = context;
    }

    public String getKind() {
        return kind;
    }

    public String getIdentifier() {
        return identifier;
    }

    public Object getBefore() {
        return before;
    }

    public Object getAfter() {
        return after;
    }

    public MutationContext getContext() {
        return context;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean value) {
        cancelled = value;
    }

    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
