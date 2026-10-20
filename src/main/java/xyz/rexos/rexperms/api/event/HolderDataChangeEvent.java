package xyz.rexos.rexperms.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import xyz.rexos.rexperms.model.HolderType;

/**
 * Fired on the main thread whenever a user's or group's data changes
 * (command, API, expiry, network sync). Scoreboard/tablist plugins can listen
 * to refresh prefixes.
 */
public final class HolderDataChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final HolderType holderType;
    private final String identifier;
    private final String description;

    public HolderDataChangeEvent(HolderType holderType, String identifier, String description) {
        this.holderType = holderType;
        this.identifier = identifier;
        this.description = description;
    }

    public HolderType getHolderType() {
        return holderType;
    }

    /** UUID string for users, group name for groups. */
    public String getIdentifier() {
        return identifier;
    }

    /** Human-readable description of the change. */
    public String getDescription() {
        return description;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
