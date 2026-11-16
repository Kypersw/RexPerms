package xyz.rexos.rexperms.api;

import org.bukkit.event.Event;

/**
 * A fail-closed policy callback. Exceptions veto the complete mutation. Unlike Bukkit listener
 * dispatch, exceptions are propagated to the coordinator.
 */
public interface AuthorizationPolicy {
    void authorize(Event request);
}
