package xyz.rexos.rexperms.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Fired when a user is promoted or demoted on a track. */
public final class UserTrackChangeEvent extends Event {

    public enum Action {PROMOTE, DEMOTE}

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID user;
    private final String username;
    private final String track;
    private final Action action;
    private final String fromGroup;
    private final String toGroup;

    public UserTrackChangeEvent(UUID user, String username, String track, Action action,
                                String fromGroup, String toGroup) {
        this.user = user;
        this.username = username;
        this.track = track;
        this.action = action;
        this.fromGroup = fromGroup;
        this.toGroup = toGroup;
    }

    public UUID getUser() {
        return user;
    }

    public String getUsername() {
        return username;
    }

    public String getTrack() {
        return track;
    }

    public Action getAction() {
        return action;
    }

    /** Previous group; null if the user just joined the track. */
    public String getFromGroup() {
        return fromGroup;
    }

    /** New group; null if the user left the track. */
    public String getToGroup() {
        return toGroup;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
