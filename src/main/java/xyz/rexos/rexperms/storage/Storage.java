package xyz.rexos.rexperms.storage;

import xyz.rexos.rexperms.model.Group;
import xyz.rexos.rexperms.model.Node;
import xyz.rexos.rexperms.model.PermissionHolder;
import xyz.rexos.rexperms.model.Track;
import xyz.rexos.rexperms.model.User;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent data layer. Every method blocks and must not be called on the
 * main thread; go through {@link StorageExecutor}.
 */
public interface Storage {

    String getName();

    void init() throws Exception;

    void shutdown();

    // ------------------------------------------------------------------ users

    /**
     * Loads stored data into the given user object.
     *
     * @return true if a record was found
     */
    boolean loadUser(User user) throws Exception;

    void saveUser(User user) throws Exception;

    void deleteUser(UUID uuid) throws Exception;

    /** Name -> UUID cache (last name seen on login). */
    void saveUsername(UUID uuid, String username) throws Exception;

    UUID lookupUuid(String username) throws Exception;

    String lookupUsername(UUID uuid) throws Exception;

    /** Every user (online or not) carrying a matching node. */
    List<HeldNode> searchUserNodes(PermissionHolder.NodeFilter filter) throws Exception;

    // ------------------------------------------------------------------ groups

    /** All groups: name -> nodes. */
    Map<String, List<Node>> loadAllGroups() throws Exception;

    /** @return null if the group does not exist */
    List<Node> loadGroup(String name) throws Exception;

    void saveGroup(Group group) throws Exception;

    void deleteGroup(String name) throws Exception;

    List<HeldNode> searchGroupNodes(PermissionHolder.NodeFilter filter) throws Exception;

    // ------------------------------------------------------------------ tracks

    Map<String, List<String>> loadAllTracks() throws Exception;

    void saveTrack(Track track) throws Exception;

    void deleteTrack(String name) throws Exception;

    // ------------------------------------------------------------------ audit log

    void logAction(LoggedAction action) throws Exception;

    /** Newest first. If targetFilter is not null, only entries for that target. */
    List<LoggedAction> recentActions(int limit, String targetFilter) throws Exception;
}
