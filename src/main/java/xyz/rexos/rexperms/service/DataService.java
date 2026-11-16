package xyz.rexos.rexperms.service;

import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import xyz.rexos.rexperms.RexPermsPlugin;
import xyz.rexos.rexperms.api.event.HolderDataChangeEvent;
import xyz.rexos.rexperms.api.event.PreAdministrativeOperationEvent;
import xyz.rexos.rexperms.calc.MetaData;
import xyz.rexos.rexperms.config.Messages;
import xyz.rexos.rexperms.inject.PermissibleInjector;
import xyz.rexos.rexperms.inject.RexPermissible;
import xyz.rexos.rexperms.model.ContextSet;
import xyz.rexos.rexperms.model.Group;
import xyz.rexos.rexperms.model.HolderType;
import xyz.rexos.rexperms.model.Node;
import xyz.rexos.rexperms.model.NodeType;
import xyz.rexos.rexperms.model.PermissionHolder;
import xyz.rexos.rexperms.model.Track;
import xyz.rexos.rexperms.model.User;
import xyz.rexos.rexperms.storage.HeldNode;
import xyz.rexos.rexperms.storage.LoggedAction;
import xyz.rexos.rexperms.storage.SqlStorage;
import xyz.rexos.rexperms.storage.Storage;
import xyz.rexos.rexperms.storage.StorageExecutor;
import xyz.rexos.rexperms.storage.SyncPoller;
import xyz.rexos.rexperms.util.ServerVersion;
import xyz.rexos.rexperms.util.Text;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Every workflow between memory and storage: loading, saving, the post-change pipeline (log,
 * notify, network sync, refreshing players), expiry and bulk group operations.
 *
 * <p>Rule: in-memory objects are changed on the main thread; storage work runs asynchronously on
 * the {@link StorageExecutor}.
 */
public final class DataService {
    private final Set<PermissionHolder> issued =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(
                            new java.util.WeakHashMap<PermissionHolder, Boolean>()));

    private User managedOffline(User source) {
        User user = new User(source, plugin.getEpoch());
        issued.add(user);
        return user;
    }

    /**
     * Storage-thread hydration. Always reads the configured backend, never caller-supplied data.
     */
    public User loadStoredUserSnapshot(UUID uuid, String name) throws Exception {
        if (Bukkit.isPrimaryThread())
            throw new IllegalStateException("Storage snapshot must load asynchronously");
        User user = plugin.getUserManager().create(uuid, name);
        storage().storage().loadUser(user);
        return managedOffline(user);
    }

    public static final String CONSOLE_NAME = "Console";
    public static final String SYSTEM_NAME = "System";

    private final RexPermsPlugin plugin;

    public DataService(RexPermsPlugin plugin) {
        this.plugin = plugin;
    }

    private StorageExecutor storage() {
        return plugin.getStorage();
    }

    // ================================================================== startup

    public void loadAllBlocking() throws Exception {
        Map<String, List<Node>> groups =
                storage()
                        .submit(
                                new StorageExecutor.Task<Map<String, List<Node>>>() {
                                    @Override
                                    public Map<String, List<Node>> run(Storage s) throws Exception {
                                        return s.loadAllGroups();
                                    }
                                })
                        .get(60, TimeUnit.SECONDS);
        applyGroups(groups);

        Map<String, List<String>> tracks =
                storage()
                        .submit(
                                new StorageExecutor.Task<Map<String, List<String>>>() {
                                    @Override
                                    public Map<String, List<String>> run(Storage s)
                                            throws Exception {
                                        return s.loadAllTracks();
                                    }
                                })
                        .get(60, TimeUnit.SECONDS);
        applyTracks(tracks);

        if (!plugin.getGroupManager().exists("default")) {
            final Group def = plugin.getGroupManager().getOrCreate("default");
            storage().run(s -> s.saveGroup(def)).get(30, TimeUnit.SECONDS);
            plugin.getLogger().info("Created the 'default' group.");
        }
    }

    private void applyGroups(Map<String, List<Node>> groups) {
        for (Map.Entry<String, List<Node>> e : groups.entrySet()) {
            plugin.getGroupManager().getOrCreate(e.getKey()).replaceNodes(e.getValue());
        }
        Set<String> keep = new HashSet<String>(groups.keySet());
        if (plugin.getGroupManager().exists("default")) keep.add("default");
        plugin.getGroupManager().retainOnly(keep);
    }

    private void applyTracks(Map<String, List<String>> tracks) {
        for (Map.Entry<String, List<String>> e : tracks.entrySet()) {
            plugin.getTrackManager().getOrCreate(e.getKey()).setGroups(e.getValue());
        }
        plugin.getTrackManager().retainOnly(tracks.keySet());
    }

    // ================================================================== loading users

    /** Called during login (async pre-login event); blocks. */
    public User loadUserBlocking(final UUID uuid, final String username) throws Exception {
        User existing = plugin.getUserManager().get(uuid);
        if (existing != null) {
            existing.setUsername(username);
            return existing;
        }
        final User user = plugin.getUserManager().create(uuid, username);
        storage().submit(s -> s.loadUser(user)).get(15, TimeUnit.SECONDS);
        user.setUsername(username);
        boolean addedDefault = ensureDefaultGroup(user);
        if (username != null) {
            storage().run(s -> s.saveUsername(uuid, username));
        }
        if (addedDefault) storage().run(s -> s.saveUser(user));
        CompletableFuture<User> registered = new CompletableFuture<User>();
        plugin.mainThread()
                .execute(
                        () -> {
                            try {
                                plugin.mutate(
                                        new xyz.rexos.rexperms.api.mutation.MutationContext(
                                                xyz.rexos.rexperms.api.event.PreHolderMutationEvent
                                                        .Origin.SYSTEM,
                                                null,
                                                "LOAD_USER"),
                                        () -> {
                                            if (!plugin.getUserManager().isLoaded(uuid))
                                                plugin.getUserManager().register(user);
                                            return null;
                                        });
                                registered.complete(plugin.getUserManager().get(uuid));
                            } catch (Throwable ex) {
                                registered.completeExceptionally(ex);
                            }
                        });
        return registered.get(15, TimeUnit.SECONDS);
    }

    /** Gives "group.default" to a user with no groups at all. */
    public boolean ensureDefaultGroup(User user) {
        if (!user.getParentNodes().isEmpty()) return false;
        user.setNode(Node.inheritance("default", ContextSet.EMPTY));
        return true;
    }

    /** For players already online when the plugin (re)loads. */
    public void loadAndInjectOnline(final Player player) {
        final UUID uuid = player.getUniqueId();
        final String name = player.getName();
        // loadUserBlocking waits on storage itself, so it must not run on the storage
        // thread (YAML is single-threaded and would deadlock).
        Bukkit.getScheduler()
                .runTaskAsynchronously(
                        plugin,
                        () -> {
                            try {
                                final User user = loadUserBlocking(uuid, name);
                                plugin.mainThread()
                                        .execute(
                                                () -> {
                                                    if (player.isOnline()) inject(player, user);
                                                });
                            } catch (Exception e) {
                                plugin.getLogger()
                                        .log(Level.SEVERE, "Could not load data for " + name, e);
                            }
                        });
    }

    public boolean inject(Player player, User user) {
        try {
            PermissibleInjector.inject(player, new RexPermissible(player, user, plugin));
            refreshPlayer(player);
            return true;
        } catch (ReflectiveOperationException e) {
            plugin.getLogger()
                    .log(
                            Level.SEVERE,
                            "Could not inject the permission handler for " + player.getName(),
                            e);
            return false;
        }
    }

    /**
     * Returns the user: the loaded one if online, otherwise a temporary copy from storage (a fresh
     * user with the default group if there is no record).
     */
    public CompletableFuture<User> loadUser(final UUID uuid, final String nameHint) {
        User loaded = plugin.getUserManager().get(uuid);
        if (loaded != null) return CompletableFuture.completedFuture(loaded);
        return storage()
                .submit(
                        s -> {
                            User u = plugin.getUserManager().create(uuid, nameHint);
                            s.loadUser(u);
                            if (u.getUsername() == null) u.setUsername(s.lookupUsername(uuid));
                            ensureDefaultGroup(u);
                            return managedOffline(u);
                        });
    }

    /**
     * Finds a user by name or UUID. Completes with null if not found. Order: online players -> UUID
     * string -> storage name cache.
     */
    public CompletableFuture<User> lookupUser(final String input) {
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            User u = plugin.getUserManager().get(online.getUniqueId());
            if (u != null) return CompletableFuture.completedFuture(u);
        }
        UUID parsed = parseUuid(input);
        if (parsed != null) return loadUser(parsed, null);

        if (!input.matches("^[A-Za-z0-9_]{1,16}$")) return CompletableFuture.completedFuture(null);

        return storage()
                .submit(s -> s.lookupUuid(input))
                .thenCompose(
                        uuid -> {
                            if (uuid != null) return loadUser(uuid, input);
                            return CompletableFuture.completedFuture((User) null);
                        });
    }

    private static UUID parseUuid(String s) {
        try {
            if (s.length() == 36) return UUID.fromString(s);
            if (s.length() == 32) {
                return UUID.fromString(
                        s.replaceFirst(
                                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                                "$1-$2-$3-$4-$5"));
            }
        } catch (IllegalArgumentException ignored) {
            // not a UUID
        }
        return null;
    }

    public void unloadUser(UUID uuid) {
        plugin.mutate(
                new xyz.rexos.rexperms.api.mutation.MutationContext(
                        xyz.rexos.rexperms.api.event.PreHolderMutationEvent.Origin.SYSTEM,
                        null,
                        "UNLOAD_USER"),
                () -> {
                    plugin.getUserManager().unregister(uuid);
                    return null;
                });
    }

    // ================================================================== saving

    public CompletableFuture<Void> save(PermissionHolder holder) {
        boolean managed =
                holder instanceof Group
                        ? plugin.getGroupManager().get(holder.getIdentifier()) == holder
                        : holder instanceof User
                                && (plugin.getUserManager().get(((User) holder).getUniqueId())
                                                == holder
                                        || issued.contains(holder));
        if (holder.getEpoch() != plugin.getEpoch() || !managed)
            throw new SecurityException("Cannot persist detached holders; use the mutation API");
        return deferredWrite(
                () -> {
                    if (holder instanceof User) {
                        User snapshot =
                                new User((User) holder, new xyz.rexos.rexperms.model.DataEpoch());
                        return storage().run(st -> st.saveUser(snapshot));
                    }
                    Group snapshot =
                            new Group(
                                    ((Group) holder).getName(),
                                    new xyz.rexos.rexperms.model.DataEpoch());
                    snapshot.replaceNodes(holder.getNodes());
                    return storage().run(st -> st.saveGroup(snapshot));
                });
    }

    private CompletableFuture<Void> deferredWrite(
            java.util.function.Supplier<CompletableFuture<Void>> write) {
        CompletableFuture<Void> result = new CompletableFuture<Void>();
        plugin.mutations().onRollback(result::completeExceptionally);
        plugin.mutations()
                .afterCommit(
                        () -> {
                            try {
                                write.get()
                                        .whenComplete(
                                                (v, error) -> {
                                                    if (error == null) result.complete(null);
                                                    else result.completeExceptionally(error);
                                                });
                            } catch (Throwable error) {
                                result.completeExceptionally(error);
                            }
                        });
        return result;
    }

    public CompletableFuture<Void> saveTrack(final Track track) {
        if (plugin.getTrackManager().get(track.getName()) != track)
            throw new SecurityException("Cannot persist detached tracks");
        return deferredWrite(
                () -> {
                    Track snapshot = new Track(track.getName());
                    snapshot.setGroups(track.getGroups());
                    return storage().run(st -> st.saveTrack(snapshot));
                });
    }

    /**
     * The standard post-change pipeline: save -> publish to the network -> log -> notify staff ->
     * refresh affected players -> fire event.
     */
    public CompletableFuture<Void> commit(
            final PermissionHolder holder, CommandSender actor, final String description) {
        final String type = holder.getType() == HolderType.USER ? "user" : "group";
        final String id = holder.getIdentifier();
        CompletableFuture<Void> saved = save(holder).thenRun(() -> publish(type, id));

        logAction(
                actorName(actor),
                holder.getType() == HolderType.USER ? 'U' : 'G',
                holder.getFriendlyName(),
                description,
                actor);

        // Vault/API calls may arrive async; Bukkit events and player updates belong on the main
        // thread
        plugin.mutations()
                .afterCommit(
                        () ->
                                plugin.mainThread()
                                        .execute(
                                                () -> {
                                                    if (holder instanceof User) {
                                                        refreshUser((User) holder);
                                                    } else {
                                                        refreshAllOnline();
                                                    }
                                                    Bukkit.getPluginManager()
                                                            .callEvent(
                                                                    new HolderDataChangeEvent(
                                                                            holder.getType(),
                                                                            id,
                                                                            description));
                                                }));
        return saved;
    }

    public CompletableFuture<Void> commitTrack(
            final Track track, CommandSender actor, String description) {
        logAction(actorName(actor), 'T', track.getName(), description, actor);
        return saveTrack(track).thenRun(() -> publish("track", track.getName()));
    }

    public CompletableFuture<Void> deleteTrack(String name, CommandSender actor) {
        return plugin.mutate(
                xyz.rexos.rexperms.api.mutation.MutationContext.sender(actor, "DELETETRACK"),
                () -> deleteTrackInternal(name, actor));
    }

    private CompletableFuture<Void> deleteTrackInternal(String name, CommandSender actor) {
        if (!authorizeLifecycle(
                PreAdministrativeOperationEvent.Operation.TRACK_DELETE, actor, name, null))
            return deniedLifecycle();
        final String lower = name.toLowerCase(Locale.ROOT);
        plugin.getTrackManager().unregister(lower);
        logAction(actorName(actor), 'T', lower, "deleted", actor);
        return storage().run(s -> s.deleteTrack(lower)).thenRun(() -> publish("track", lower));
    }

    public static String actorName(CommandSender sender) {
        if (sender == null) return SYSTEM_NAME;
        return sender instanceof Player ? sender.getName() : CONSOLE_NAME;
    }

    private void publish(String type, String target) {
        plugin.mutations()
                .afterCommit(
                        () -> {
                            SyncPoller poller = plugin.getSyncPoller();
                            if (poller != null) poller.publish(type, target);
                        });
    }

    // ================================================================== log & notify

    public void logAction(
            String actor,
            char targetType,
            String targetName,
            String description,
            final CommandSender exclude) {
        plugin.mutations()
                .afterCommit(
                        () -> {
                            final LoggedAction action =
                                    new LoggedAction(
                                            System.currentTimeMillis() / 1000L,
                                            actor,
                                            targetType,
                                            targetName,
                                            description);
                            storage().run(s -> s.logAction(action));

                            if (plugin.getSettings().logConsole()) {
                                plugin.getLogger()
                                        .info(
                                                "[Log] "
                                                        + actor
                                                        + " -> "
                                                        + targetName
                                                        + ": "
                                                        + description);
                            }
                            if (plugin.getSettings().logNotify()) {
                                final Component msg =
                                        plugin.getMessages()
                                                .prefixed(
                                                        "log.notify",
                                                        Messages.text("actor", actor),
                                                        Messages.text("target", targetName),
                                                        Messages.text("action", description));
                                plugin.mainThread()
                                        .execute(
                                                () -> {
                                                    for (Player p : Bukkit.getOnlinePlayers()) {
                                                        if (p != exclude
                                                                && p.hasPermission(
                                                                        "rexperms.log.notify")) {
                                                            plugin.getMessages().send(p, msg);
                                                        }
                                                    }
                                                });
                            }
                        });
    }

    // ================================================================== refreshing players

    public void refreshUser(User user) {
        plugin.mutations()
                .afterCommit(
                        () -> {
                            Player p = Bukkit.getPlayer(user.getUniqueId());
                            if (p != null) refreshPlayer(p);
                        });
    }

    public void refreshAllOnline() {
        plugin.mutations()
                .afterCommit(
                        () -> {
                            for (Player p : Bukkit.getOnlinePlayers()) refreshPlayer(p);
                        });
    }

    /** Refreshes the command tree (tab completion) and the tab list name. Main thread. */
    public void refreshPlayer(final Player player) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> refreshPlayer(player));
            return;
        }
        if (!player.isOnline()) return;
        ServerVersion.updateCommands(player);
        if (plugin.getSettings().tablistEnabled()) applyTablist(player);
    }

    private void applyTablist(Player player) {
        User user = plugin.getUserManager().get(player.getUniqueId());
        RexPermissible rex = PermissibleInjector.getInjected(player);
        if (user == null || rex == null) return;
        MetaData meta = user.getCachedData().meta(rex.getContext(), plugin.getCalculator());
        String raw =
                plugin.getSettings()
                        .tablistFormat()
                        .replace("{prefix}", meta.prefix() == null ? "" : meta.prefix())
                        .replace("{suffix}", meta.suffix() == null ? "" : meta.suffix())
                        .replace("{group}", meta.primaryGroup())
                        .replace("{name}", player.getName());
        try {
            player.setPlayerListName(Text.legacy(raw));
        } catch (IllegalArgumentException e) {
            // very old servers limit the list name length; fall back to the plain name
            player.setPlayerListName(null);
        }
    }

    // ================================================================== expiry

    /** Called periodically on the main thread. */
    public void checkExpiry() {
        long now = System.currentTimeMillis() / 1000L;
        List<PermissionHolder> holders =
                new ArrayList<PermissionHolder>(plugin.getUserManager().all());
        holders.addAll(plugin.getGroupManager().all());
        for (PermissionHolder holder : holders) {
            try {
                plugin.mutate(
                        new xyz.rexos.rexperms.api.mutation.MutationContext(
                                xyz.rexos.rexperms.api.event.PreHolderMutationEvent.Origin.SYSTEM,
                                null,
                                "EXPIRE"),
                        () -> {
                            List<Node> expired = holder.removeExpired(now);
                            if (!expired.isEmpty())
                                commit(holder, null, "expired: " + describeNodes(expired));
                            return null;
                        });
            } catch (SecurityException denied) {
                plugin.getLogger().fine("Expiry denied: " + holder.getIdentifier());
            }
        }
    }

    private static String describeNodes(List<Node> nodes) {
        List<String> parts = new ArrayList<String>();
        for (Node n : nodes) parts.add(n.key() + Text.contextSuffix(n.contexts()));
        return Text.join(", ", parts);
    }

    // ================================================================== network sync

    /** Called on a storage thread. */
    private void networkMutation(Runnable action) {
        try {
            plugin.mutate(
                    new xyz.rexos.rexperms.api.mutation.MutationContext(
                            xyz.rexos.rexperms.api.event.PreHolderMutationEvent.Origin.NETWORK,
                            null,
                            "NETWORK_SYNC"),
                    () -> {
                        action.run();
                        return null;
                    });
        } catch (SecurityException denied) {
            plugin.getLogger().warning("Network mutation rejected by authorization policy");
        }
    }

    public void handleSyncMessage(final SqlStorage.SyncMessage message) {
        Storage s = storage().storage();
        try {
            String type = message.type();
            if (type.equals("user")) {
                UUID uuid = UUID.fromString(message.target());
                final User user = plugin.getUserManager().get(uuid);
                if (user != null) {
                    // Load into a temporary object, then copy over on the main thread
                    final User fresh = plugin.getUserManager().create(uuid, user.getUsername());
                    s.loadUser(fresh);
                    Bukkit.getScheduler()
                            .runTask(
                                    plugin,
                                    () ->
                                            networkMutation(
                                                    () -> {
                                                        user.replaceNodes(fresh.getNodes());
                                                        user.setPrimaryGroup(
                                                                fresh.getPrimaryGroup());
                                                        ensureDefaultGroup(user);
                                                        refreshUser(user);
                                                    }));
                }
            } else if (type.equals("group")) {
                final List<Node> nodes = s.loadGroup(message.target());
                Bukkit.getScheduler()
                        .runTask(
                                plugin,
                                () ->
                                        networkMutation(
                                                () -> {
                                                    if (nodes == null) {
                                                        plugin.getGroupManager()
                                                                .unregister(message.target());
                                                    } else {
                                                        plugin.getGroupManager()
                                                                .getOrCreate(message.target())
                                                                .replaceNodes(nodes);
                                                    }
                                                    refreshAllOnline();
                                                }));
            } else if (type.equals("track")) {
                final Map<String, List<String>> tracks = s.loadAllTracks();
                Bukkit.getScheduler()
                        .runTask(plugin, () -> networkMutation(() -> applyTracks(tracks)));
            } else if (type.equals("full")) {
                fullReloadOnStorageThread(
                        s,
                        new xyz.rexos.rexperms.api.mutation.MutationContext(
                                xyz.rexos.rexperms.api.event.PreHolderMutationEvent.Origin.NETWORK,
                                null,
                                "NETWORK_SYNC"));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not apply sync message: " + message, e);
        }
    }

    /** /rp sync: reloads everything from storage. */
    public CompletableFuture<Void> fullReload() {
        final xyz.rexos.rexperms.api.mutation.MutationContext actor =
                xyz.rexos.rexperms.api.mutation.MutationContext.current();
        return storage()
                .run(
                        s ->
                                fullReloadOnStorageThread(
                                        s,
                                        new xyz.rexos.rexperms.api.mutation.MutationContext(
                                                actor.origin, actor.actor, "FULL_RELOAD")));
    }

    private void fullReloadOnStorageThread(
            Storage s, xyz.rexos.rexperms.api.mutation.MutationContext context) throws Exception {
        final Map<String, List<Node>> groups = s.loadAllGroups();
        final Map<String, List<String>> tracks = s.loadAllTracks();
        final List<User> fresh = new ArrayList<User>();
        for (User u : new ArrayList<User>(plugin.getUserManager().all())) {
            User copy = plugin.getUserManager().create(u.getUniqueId(), u.getUsername());
            s.loadUser(copy);
            fresh.add(copy);
        }
        final CompletableFuture<Void> applied = new CompletableFuture<Void>();
        Bukkit.getScheduler()
                .runTask(
                        plugin,
                        () -> {
                            try {
                                plugin.mutate(
                                        context,
                                        () -> {
                                            applyGroups(groups);
                                            applyTracks(tracks);
                                            for (User copy : fresh) {
                                                User live =
                                                        plugin.getUserManager()
                                                                .get(copy.getUniqueId());
                                                if (live == null) continue;
                                                live.replaceNodes(copy.getNodes());
                                                live.setPrimaryGroup(copy.getPrimaryGroup());
                                                ensureDefaultGroup(live);
                                            }
                                            refreshAllOnline();
                                            return null;
                                        });
                                applied.complete(null);
                            } catch (Throwable t) {
                                applied.completeExceptionally(t);
                            }
                        });
        applied.get(30, TimeUnit.SECONDS);
    }

    public void broadcastFullSync() {
        publish("full", "*");
    }

    /** Lifecycle authorization must run before registering or changing any live holder. */
    public boolean authorizeLifecycle(
            PreAdministrativeOperationEvent.Operation operation,
            CommandSender actor,
            String target,
            String destination) {
        if (!org.bukkit.Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "Administrative lifecycle changes must run on the server thread");
        }
        xyz.rexos.rexperms.api.mutation.MutationContext attribution =
                actor == null
                        ? xyz.rexos.rexperms.api.mutation.MutationContext.current()
                        : xyz.rexos.rexperms.api.mutation.MutationContext.sender(
                                actor, operation.name());
        PreAdministrativeOperationEvent.Origin origin =
                PreAdministrativeOperationEvent.Origin.valueOf(attribution.origin.name());
        String actorId = attribution.actor;
        PreAdministrativeOperationEvent event =
                new PreAdministrativeOperationEvent(
                        operation, origin, actorId, target, destination);
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    private static <T> CompletableFuture<T> deniedLifecycle() {
        CompletableFuture<T> denied = new CompletableFuture<T>();
        denied.completeExceptionally(
                new SecurityException("Administrative lifecycle operation denied"));
        return denied;
    }

    // ================================================================== groups

    public CompletableFuture<Group> createGroup(String name, CommandSender actor) {
        return plugin.mutate(
                xyz.rexos.rexperms.api.mutation.MutationContext.sender(actor, "CREATEGROUP"),
                () -> createGroupInternal(name, actor));
    }

    private CompletableFuture<Group> createGroupInternal(String name, CommandSender actor) {
        if (!authorizeLifecycle(
                PreAdministrativeOperationEvent.Operation.GROUP_CREATE, actor, name, null))
            return deniedLifecycle();
        final Group group = plugin.getGroupManager().getOrCreate(name);
        logAction(actorName(actor), 'G', group.getName(), "created", actor);
        return save(group)
                .thenApply(
                        v -> {
                            publish("group", group.getName());
                            return group;
                        });
    }

    /**
     * Deletes a group and cleans up every reference (users, other groups, tracks). Offline users
     * are updated in storage as well.
     *
     * @return number of offline users updated
     */
    public CompletableFuture<Integer> deleteGroup(String name, CommandSender actor) {
        return plugin.mutate(
                xyz.rexos.rexperms.api.mutation.MutationContext.sender(actor, "DELETEGROUP"),
                () -> deleteGroupInternal(name, actor));
    }

    private CompletableFuture<Integer> deleteGroupInternal(String name, CommandSender actor) {
        return rewriteGroup(name, null, actor);
    }

    /** Copies a group (including its nodes) under a new name. */
    public CompletableFuture<Group> cloneGroup(Group source, String newName, CommandSender actor) {
        return plugin.mutate(
                xyz.rexos.rexperms.api.mutation.MutationContext.sender(actor, "CLONEGROUP"),
                () -> cloneGroupInternal(source, newName, actor));
    }

    private CompletableFuture<Group> cloneGroupInternal(
            Group source, String newName, CommandSender actor) {
        if (!authorizeLifecycle(
                PreAdministrativeOperationEvent.Operation.GROUP_CLONE,
                actor,
                source.getName(),
                newName)) return deniedLifecycle();
        final Group target = plugin.getGroupManager().getOrCreate(newName);
        target.replaceNodes(source.getNodes());
        logAction(
                actorName(actor), 'G', target.getName(), "cloned from " + source.getName(), actor);
        return save(target)
                .thenApply(
                        v -> {
                            publish("group", target.getName());
                            return target;
                        });
    }

    /**
     * Renames a group: creates the new group, rewrites every "group.old" reference to "group.new",
     * then deletes the old one.
     */
    public CompletableFuture<Integer> renameGroup(
            Group source, String newName, CommandSender actor) {
        return plugin.mutate(
                xyz.rexos.rexperms.api.mutation.MutationContext.sender(actor, "RENAMEGROUP"),
                () -> renameGroupInternal(source, newName, actor));
    }

    private CompletableFuture<Integer> renameGroupInternal(
            Group source, String newName, CommandSender actor) {
        return rewriteGroup(source.getName(), newName, actor);
    }

    /** Preload offline snapshots, then authorize the complete rewrite before enqueueing writes. */
    private CompletableFuture<Integer> rewriteGroup(
            String oldName, String destination, CommandSender actor) {
        final String old = oldName.toLowerCase(Locale.ROOT);
        final String replacement =
                destination == null ? null : destination.toLowerCase(Locale.ROOT);
        if ("default".equals(old))
            throw new IllegalArgumentException("Cannot delete or rename default");
        if (replacement != null
                && (!Group.isValidName(replacement)
                        || plugin.getGroupManager().exists(replacement)))
            throw new IllegalArgumentException("Invalid or existing destination");
        return storage()
                .submit(
                        st -> {
                            Map<UUID, User> originals = new java.util.LinkedHashMap<UUID, User>();
                            for (HeldNode node : st.searchUserNodes(n -> true)) {
                                UUID uuid = UUID.fromString(node.holderId());
                                if (originals.containsKey(uuid)) continue;
                                User user = plugin.getUserManager().create(uuid, node.holderName());
                                st.loadUser(user);
                                if (old.equals(user.getPrimaryGroup())
                                        || user.findNode(n -> n.key().equals("group." + old))
                                                != null) originals.put(uuid, user);
                            }
                            return originals;
                        })
                .thenComposeAsync(
                        originals ->
                                plugin.mutate(
                                        xyz.rexos.rexperms.api.mutation.MutationContext.sender(
                                                actor, "GROUP_REWRITE"),
                                        () -> {
                                            PreAdministrativeOperationEvent.Operation operation =
                                                    replacement == null
                                                            ? PreAdministrativeOperationEvent
                                                                    .Operation.GROUP_DELETE
                                                            : PreAdministrativeOperationEvent
                                                                    .Operation.GROUP_RENAME;
                                            if (!authorizeLifecycle(
                                                    operation, actor, old, replacement))
                                                return DataService.<Integer>deniedLifecycle();
                                            Group source = plugin.getGroupManager().get(old);
                                            if (source == null)
                                                throw new IllegalArgumentException(
                                                        "Group no longer exists");
                                            List<CompletableFuture<?>> writes =
                                                    new ArrayList<CompletableFuture<?>>();
                                            if (replacement != null) {
                                                if (plugin.getGroupManager().exists(replacement))
                                                    throw new IllegalArgumentException(
                                                            "Destination now exists");
                                                Group target =
                                                        plugin.getGroupManager()
                                                                .getOrCreate(replacement);
                                                target.replaceNodes(source.getNodes());
                                                writes.add(save(target));
                                            }
                                            List<PermissionHolder> holders =
                                                    new ArrayList<PermissionHolder>(
                                                            plugin.getGroupManager().all());
                                            holders.addAll(plugin.getUserManager().all());
                                            for (Map.Entry<UUID, User> e : originals.entrySet())
                                                if (!plugin.getUserManager().isLoaded(e.getKey()))
                                                    holders.add(managedOffline(e.getValue()));
                                            int changedUsers = 0;
                                            for (PermissionHolder holder : holders) {
                                                if (holder == source) continue;
                                                boolean changed =
                                                        replacement == null
                                                                ? holder.removeIf(
                                                                                n ->
                                                                                        n.key()
                                                                                                .equals(
                                                                                                        "group."
                                                                                                                + old))
                                                                        > 0
                                                                : replaceInheritance(
                                                                        holder,
                                                                        "group." + old,
                                                                        replacement);
                                                if (holder instanceof User) {
                                                    User user = (User) holder;
                                                    if (old.equals(user.getPrimaryGroup())) {
                                                        user.setPrimaryGroup(
                                                                replacement == null
                                                                        ? "default"
                                                                        : replacement);
                                                        changed = true;
                                                    }
                                                    if (changed) {
                                                        ensureDefaultGroup(user);
                                                        changedUsers++;
                                                    }
                                                }
                                                if (changed) {
                                                    writes.add(save(holder));
                                                    final String kind =
                                                            holder instanceof User
                                                                    ? "user"
                                                                    : "group";
                                                    final String id = holder.getIdentifier();
                                                    plugin.mutations()
                                                            .afterCommit(() -> publish(kind, id));
                                                }
                                            }
                                            for (Track track : plugin.getTrackManager().all()) {
                                                int index = track.indexOf(old);
                                                if (index < 0) continue;
                                                track.remove(old);
                                                if (replacement != null)
                                                    track.insert(replacement, index + 1);
                                                writes.add(saveTrack(track));
                                            }
                                            plugin.getGroupManager().unregister(old);
                                            writes.add(storage().run(st -> st.deleteGroup(old)));
                                            final int count = changedUsers;
                                            return CompletableFuture.allOf(
                                                            writes.toArray(
                                                                    new CompletableFuture<?>[0]))
                                                    .thenApplyAsync(
                                                            v -> {
                                                                publish("group", old);
                                                                if (replacement != null)
                                                                    publish("group", replacement);
                                                                broadcastFullSync();
                                                                refreshAllOnline();
                                                                logAction(
                                                                        actorName(actor),
                                                                        'G',
                                                                        old,
                                                                        replacement == null
                                                                                ? "deleted"
                                                                                : "renamed to "
                                                                                        + replacement,
                                                                        actor);
                                                                return count;
                                                            },
                                                            plugin.mainThread());
                                        }),
                        plugin.mainThread());
    }

    private static boolean replaceInheritance(
            PermissionHolder holder, final String oldKey, String newGroup) {
        List<Node> matches = new ArrayList<Node>();
        for (Node n : holder.getNodes(NodeType.INHERITANCE)) {
            if (n.key().equals(oldKey)) matches.add(n);
        }
        if (matches.isEmpty()) return false;
        holder.removeIf(n -> n.key().equals(oldKey));
        for (Node n : matches) {
            holder.setNode(Node.of("group." + newGroup, n.value(), n.expiry(), n.contexts()));
        }
        return true;
    }

    // ================================================================== tracks

    /**
     * Moves the user one group up the track. Only permanent inheritance nodes with exactly the
     * given contexts are considered.
     */
    public TrackResult promote(User user, Track track, ContextSet ctx) {
        List<String> groups = track.getGroups();
        if (groups.isEmpty()) return TrackResult.of(TrackResult.Outcome.EMPTY_TRACK);
        List<String> current = groupsOnTrack(user, track, ctx);
        if (current.size() > 1) return TrackResult.of(TrackResult.Outcome.AMBIGUOUS);

        if (current.isEmpty()) {
            String first = groups.get(0);
            if (!plugin.getGroupManager().exists(first))
                return TrackResult.of(TrackResult.Outcome.GROUP_MISSING);
            user.setNode(Node.inheritance(first, ctx));
            return new TrackResult(TrackResult.Outcome.ADDED_TO_FIRST, null, first);
        }

        String from = current.get(0);
        String to = track.next(from);
        if (to == null) return new TrackResult(TrackResult.Outcome.END_OF_TRACK, from, null);
        if (!plugin.getGroupManager().exists(to))
            return TrackResult.of(TrackResult.Outcome.GROUP_MISSING);

        user.unsetNode(Node.inheritance(from, ctx));
        user.setNode(Node.inheritance(to, ctx));
        if (ctx.isEmpty() && from.equals(user.getPrimaryGroup())) user.setPrimaryGroup(to);
        return new TrackResult(TrackResult.Outcome.SUCCESS, from, to);
    }

    public TrackResult demote(User user, Track track, ContextSet ctx) {
        if (track.getGroups().isEmpty()) return TrackResult.of(TrackResult.Outcome.EMPTY_TRACK);
        List<String> current = groupsOnTrack(user, track, ctx);
        if (current.size() > 1) return TrackResult.of(TrackResult.Outcome.AMBIGUOUS);
        if (current.isEmpty()) return TrackResult.of(TrackResult.Outcome.NOT_ON_TRACK);

        String from = current.get(0);
        String to = track.previous(from);
        user.unsetNode(Node.inheritance(from, ctx));
        if (to == null) {
            if (ctx.isEmpty() && from.equals(user.getPrimaryGroup()))
                user.setPrimaryGroup("default");
            ensureDefaultGroup(user);
            return new TrackResult(TrackResult.Outcome.REMOVED_FROM_FIRST, from, null);
        }
        if (!plugin.getGroupManager().exists(to)) {
            user.setNode(Node.inheritance(from, ctx)); // roll back
            return TrackResult.of(TrackResult.Outcome.GROUP_MISSING);
        }
        user.setNode(Node.inheritance(to, ctx));
        if (ctx.isEmpty() && from.equals(user.getPrimaryGroup())) user.setPrimaryGroup(to);
        return new TrackResult(TrackResult.Outcome.SUCCESS, from, to);
    }

    private static List<String> groupsOnTrack(User user, Track track, ContextSet ctx) {
        List<String> out = new ArrayList<String>();
        for (Node n : user.getParentNodes()) {
            if (!n.value() || n.isTemporary() || !n.contexts().equals(ctx)) continue;
            String g = n.groupName();
            if (track.contains(g) && !out.contains(g)) out.add(g);
        }
        return out;
    }
}
