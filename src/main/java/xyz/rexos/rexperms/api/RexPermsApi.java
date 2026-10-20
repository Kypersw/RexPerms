package xyz.rexos.rexperms.api;

import org.bukkit.entity.Player;
import xyz.rexos.rexperms.model.ContextSet;
import xyz.rexos.rexperms.model.Group;
import xyz.rexos.rexperms.model.Node;
import xyz.rexos.rexperms.model.User;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * RexPerms API for other plugins.
 *
 * <pre>{@code
 * RexPermsApi api = Bukkit.getServicesManager().load(RexPermsApi.class);
 *
 * // Give 30 days of VIP (e.g. from a store plugin); stacks on existing time
 * long expiry = System.currentTimeMillis() / 1000 + 30L * 86400;
 * api.modifyUser(uuid, user -> user.setNode(
 *         Node.of("group.vip", true, expiry, ContextSet.EMPTY),
 *         TemporaryMergeBehaviour.ACCUMULATE));
 *
 * // Read the prefix
 * String prefix = api.getPrefix(player);
 * }</pre>
 */
public interface RexPermsApi {

    /** Data of a loaded (online) user, or null. */
    User getLoadedUser(UUID uuid);

    /** Loads a user, even when offline. */
    CompletableFuture<User> loadUser(UUID uuid);

    /**
     * Loads the user, applies the change, saves it and publishes it to the
     * network. The action runs on the main thread.
     */
    CompletableFuture<Void> modifyUser(UUID uuid, Consumer<User> action);

    Group getGroup(String name);

    Collection<Group> getGroups();

    /** Changes a group, saves and publishes it. Call on the main thread. */
    CompletableFuture<Void> modifyGroup(Group group, Consumer<Group> action);

    /** Highest priority prefix in the player's current contexts (raw colour codes), or null. */
    String getPrefix(Player player);

    String getSuffix(Player player);

    String getPrimaryGroup(Player player);

    String getMeta(Player player, String key);

    /** Is the player in the group, inheritance included? */
    boolean inheritsGroup(Player player, String group);

    /** Convenience: a permanent global permission node. */
    Node permissionNode(String key, boolean value);

    /** Convenience: the empty (global) context set. */
    ContextSet globalContext();
}
