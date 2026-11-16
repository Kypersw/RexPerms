package xyz.rexos.rexperms.api.event;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import xyz.rexos.rexperms.model.HolderType;
import xyz.rexos.rexperms.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A proposed mutation of a user's or group's nodes, before it is applied.
 * Listeners may veto the entire change. This event must be fired on the main thread.
 *
 * <p>Actor attribution is intentionally explicit: SYSTEM is not CONSOLE.
 * Plugins should never treat an unknown API caller as the console.</p>
 */
public final class PreHolderMutationEvent extends Event implements Cancellable {
    public enum Origin { PLAYER, CONSOLE, API, VAULT, SYSTEM, NETWORK }
    private static final HandlerList HANDLERS = new HandlerList();

    private final HolderType holderType;
    private final String identifier;
    private final Origin origin;
    private final String actorIdentifier;
    private final List<Node> before;
    private final List<Node> after;
    private boolean cancelled;

    public PreHolderMutationEvent(HolderType holderType, String identifier,
                                  Origin origin, String actorIdentifier,
                                  List<Node> before, List<Node> after) {
        this.holderType = holderType;
        this.identifier = identifier;
        this.origin = origin;
        this.actorIdentifier = actorIdentifier;
        this.before = Collections.unmodifiableList(new ArrayList<Node>(before));
        this.after = Collections.unmodifiableList(new ArrayList<Node>(after));
    }

    public HolderType getHolderType() { return holderType; }
    public String getIdentifier() { return identifier; }
    public Origin getOrigin() { return origin; }
    /** UUID for players; null for unattributed operations. */
    public String getActorIdentifier() { return actorIdentifier; }
    public List<Node> getBefore() { return before; }
    public List<Node> getAfter() { return after; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
