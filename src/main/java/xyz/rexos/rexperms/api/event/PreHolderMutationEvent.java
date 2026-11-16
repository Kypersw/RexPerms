package xyz.rexos.rexperms.api.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import xyz.rexos.rexperms.model.HolderType;
import xyz.rexos.rexperms.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Synchronous authorization request before an API holder mutation is applied. */
public final class PreHolderMutationEvent extends Event implements Cancellable {
    public enum Origin {
        PLAYER,
        CONSOLE,
        API,
        VAULT,
        SYSTEM,
        NETWORK
    }

    private static final HandlerList HANDLERS = new HandlerList();
    private final HolderType holderType;
    private final String identifier;
    private final Origin origin;
    private final String actorIdentifier;
    private final List<Node> before;
    private final List<Node> after;
    private final String beforePrimaryGroup;
    private final String afterPrimaryGroup;
    private final String operation =
            xyz.rexos.rexperms.api.mutation.MutationContext.current().operation;

    public String getOperation() {
        return operation;
    }

    private boolean cancelled;

    public PreHolderMutationEvent(
            HolderType holderType,
            String identifier,
            Origin origin,
            String actorIdentifier,
            List<Node> before,
            List<Node> after) {
        this(holderType, identifier, origin, actorIdentifier, before, after, null, null);
    }

    public PreHolderMutationEvent(
            HolderType holderType,
            String identifier,
            Origin origin,
            String actorIdentifier,
            List<Node> before,
            List<Node> after,
            String beforePrimaryGroup,
            String afterPrimaryGroup) {
        this.beforePrimaryGroup = beforePrimaryGroup;
        this.afterPrimaryGroup = afterPrimaryGroup;
        this.holderType = holderType;
        this.identifier = identifier;
        this.origin = origin;
        this.actorIdentifier = actorIdentifier;
        this.before = Collections.unmodifiableList(new ArrayList<Node>(before));
        this.after = Collections.unmodifiableList(new ArrayList<Node>(after));
    }

    public HolderType getHolderType() {
        return holderType;
    }

    public String getIdentifier() {
        return identifier;
    }

    public Origin getOrigin() {
        return origin;
    }

    public String getActorIdentifier() {
        return actorIdentifier;
    }

    public List<Node> getBefore() {
        return before;
    }

    public List<Node> getAfter() {
        return after;
    }

    public String getBeforePrimaryGroup() {
        return beforePrimaryGroup;
    }

    public String getAfterPrimaryGroup() {
        return afterPrimaryGroup;
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
