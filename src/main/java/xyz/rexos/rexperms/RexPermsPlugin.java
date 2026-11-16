package xyz.rexos.rexperms;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import xyz.rexos.rexperms.api.RexPermsApi;
import xyz.rexos.rexperms.api.RexPermsApiImpl;
import xyz.rexos.rexperms.calc.ChildPermissionProvider;
import xyz.rexos.rexperms.calc.PermissionCalculator;
import xyz.rexos.rexperms.command.RootCommand;
import xyz.rexos.rexperms.config.Messages;
import xyz.rexos.rexperms.config.Settings;
import xyz.rexos.rexperms.context.ContextManager;
import xyz.rexos.rexperms.hook.PlaceholderHook;
import xyz.rexos.rexperms.hook.VaultHook;
import xyz.rexos.rexperms.inject.PermissibleInjector;
import xyz.rexos.rexperms.listener.ChatListener;
import xyz.rexos.rexperms.listener.ConnectionListener;
import xyz.rexos.rexperms.listener.PlayerStateListener;
import xyz.rexos.rexperms.manager.GroupManager;
import xyz.rexos.rexperms.manager.TrackManager;
import xyz.rexos.rexperms.manager.UserManager;
import xyz.rexos.rexperms.model.DataEpoch;
import xyz.rexos.rexperms.model.User;
import xyz.rexos.rexperms.service.DataService;
import xyz.rexos.rexperms.storage.SqlStorage;
import xyz.rexos.rexperms.storage.Storage;
import xyz.rexos.rexperms.storage.StorageExecutor;
import xyz.rexos.rexperms.storage.SyncPoller;
import xyz.rexos.rexperms.storage.YamlStorage;
import xyz.rexos.rexperms.util.ServerVersion;
import xyz.rexos.rexperms.verbose.VerboseHandler;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.logging.Level;

public final class RexPermsPlugin extends JavaPlugin {

    private Settings settings;
    private Messages messages;
    private DataEpoch epoch;
    private final java.util.Map<
                    org.bukkit.plugin.Plugin, xyz.rexos.rexperms.api.AuthorizationPolicy>
            policies =
                    new java.util.LinkedHashMap<
                            org.bukkit.plugin.Plugin, xyz.rexos.rexperms.api.AuthorizationPolicy>();

    public void registerPolicy(
            org.bukkit.plugin.Plugin owner, xyz.rexos.rexperms.api.AuthorizationPolicy policy) {
        if (!Bukkit.isPrimaryThread()
                || owner == null
                || !owner.isEnabled()
                || policies.containsKey(owner))
            throw new IllegalStateException("Invalid policy registration");
        policies.put(owner, java.util.Objects.requireNonNull(policy));
    }

    private void checkPolicies(org.bukkit.event.Event event) {
        for (java.util.Map.Entry<
                        org.bukkit.plugin.Plugin, xyz.rexos.rexperms.api.AuthorizationPolicy>
                entry : policies.entrySet()) {
            if (!entry.getKey().isEnabled())
                throw new SecurityException(
                        "Required authorization policy disabled: " + entry.getKey().getName());
            entry.getValue().authorize(event);
        }
    }

    private xyz.rexos.rexperms.api.mutation.MutationCoordinator mutations;

    public xyz.rexos.rexperms.api.mutation.MutationCoordinator mutations() {
        return mutations;
    }

    public <T> T mutate(
            xyz.rexos.rexperms.api.mutation.MutationContext context,
            java.util.function.Supplier<T> action) {
        final Object[] result = {null};
        context.run(() -> result[0] = mutations.call(action));
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }

    private Object immutableStructure(Object value) {
        if (value instanceof java.util.Map) {
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
            for (Object raw : ((java.util.Map<?, ?>) value).entrySet()) {
                java.util.Map.Entry<?, ?> e = (java.util.Map.Entry<?, ?>) raw;
                out.put(String.valueOf(e.getKey()), immutableStructure(e.getValue()));
            }
            return java.util.Collections.unmodifiableMap(out);
        }
        if (value instanceof xyz.rexos.rexperms.model.PermissionHolder) {
            xyz.rexos.rexperms.model.PermissionHolder h =
                    (xyz.rexos.rexperms.model.PermissionHolder) value;
            return new xyz.rexos.rexperms.api.mutation.HolderSnapshot(
                    h.getNodes(),
                    h instanceof xyz.rexos.rexperms.model.User
                            ? ((xyz.rexos.rexperms.model.User) h).getPrimaryGroup()
                            : null);
        }
        if (value instanceof xyz.rexos.rexperms.model.Track)
            return ((xyz.rexos.rexperms.model.Track) value).getGroups();
        return value;
    }

    private void authorizeMutation(String kind, String id, Object before, Object after) {
        xyz.rexos.rexperms.api.mutation.MutationContext context =
                xyz.rexos.rexperms.api.mutation.MutationContext.current();
        if (before instanceof xyz.rexos.rexperms.api.mutation.HolderSnapshot) {
            xyz.rexos.rexperms.api.mutation.HolderSnapshot a =
                    (xyz.rexos.rexperms.api.mutation.HolderSnapshot) before;
            xyz.rexos.rexperms.api.mutation.HolderSnapshot b =
                    (xyz.rexos.rexperms.api.mutation.HolderSnapshot) after;
            xyz.rexos.rexperms.api.event.PreHolderMutationEvent event =
                    new xyz.rexos.rexperms.api.event.PreHolderMutationEvent(
                            xyz.rexos.rexperms.model.HolderType.valueOf(kind),
                            id,
                            context.origin,
                            context.actor,
                            a.nodes,
                            b.nodes,
                            a.primaryGroup,
                            b.primaryGroup);
            Bukkit.getPluginManager().callEvent(event);
            checkPolicies(event);
            if (event.isCancelled()) throw new SecurityException("Cancelled " + kind + ":" + id);
        } else {
            xyz.rexos.rexperms.api.event.PreStructuralMutationEvent event =
                    new xyz.rexos.rexperms.api.event.PreStructuralMutationEvent(
                            kind,
                            id,
                            immutableStructure(before),
                            immutableStructure(after),
                            context);
            Bukkit.getPluginManager().callEvent(event);
            checkPolicies(event);
            if (event.isCancelled()) throw new SecurityException("Cancelled " + kind + ":" + id);
        }
    }

    private GroupManager groupManager;
    private UserManager userManager;
    private TrackManager trackManager;
    private PermissionCalculator calculator;
    private ContextManager contextManager;
    private StorageExecutor storage;
    private SyncPoller syncPoller;
    private VerboseHandler verboseHandler;
    private DataService service;
    private VaultHook vaultHook;
    private PlaceholderHook placeholderHook;
    private RexPermsApiImpl api;

    private final Executor mainExecutor =
            runnable -> {
                if (Bukkit.isPrimaryThread()) {
                    runnable.run();
                } else if (isEnabled()) {
                    Bukkit.getScheduler().runTask(this, runnable);
                }
            };

    private BukkitTask syncTask;
    private BukkitTask expiryTask;
    private BukkitTask verboseTask;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onLoad() {
        saveDefaultConfig();
        settings = Settings.load(getConfig());
        messages = new Messages(this);

        mutations =
                new xyz.rexos.rexperms.api.mutation.MutationCoordinator(
                        this::authorizeMutation,
                        () -> {
                            if (!Bukkit.isPrimaryThread())
                                throw new IllegalStateException("Mutation requires server thread");
                        });
        epoch = new DataEpoch(mutations);
        groupManager = new GroupManager(epoch);
        userManager = new UserManager(epoch);
        trackManager = new TrackManager(mutations);
        calculator = new PermissionCalculator(groupManager);
        contextManager = new ContextManager(settings);
        applyCalculatorSettings();

        // Register the Vault provider early: plugins that ask Vault for a
        // permission service in their onEnable should find RexPerms.
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            try {
                vaultHook = new VaultHook(this);
                vaultHook.register();
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "Could not register the Vault integration", t);
                vaultHook = null;
            }
        }
    }

    @Override
    public void onEnable() {
        long start = System.currentTimeMillis();
        if (ServerVersion.isFolia()) {
            getLogger().severe("Folia is not supported. Please use Paper, Purpur or Spigot.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        messages.enable();
        messages.load();
        verboseHandler = new VerboseHandler(messages);

        try {
            Storage backend = createStorage();
            backend.init();
            int threads = backend instanceof YamlStorage ? 1 : 4;
            storage = new StorageExecutor(backend, threads, getLogger(), mutations);
        } catch (Throwable e) {
            getLogger()
                    .log(
                            Level.SEVERE,
                            "Could not start the storage! Disabling the plugin. "
                                    + "Check the 'storage' section of config.yml.",
                            e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        service = new DataService(this);
        try {
            service.loadAllBlocking();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not load groups/tracks! Disabling the plugin.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        startSync();

        getServer().getPluginManager().registerEvents(new ConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerStateListener(this), this);
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);

        PluginCommand cmd = getCommand("rexperms");
        if (cmd != null) {
            RootCommand root = new RootCommand(this);
            cmd.setExecutor(root);
            cmd.setTabCompleter(root);
        }

        api = new RexPermsApiImpl(this);
        getServer()
                .getServicesManager()
                .register(RexPermsApi.class, api, this, ServicePriority.Normal);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                placeholderHook = new PlaceholderHook(this);
                placeholderHook.register();
                getLogger().info("PlaceholderAPI integration enabled.");
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "PlaceholderAPI integration failed", t);
            }
        }
        if (vaultHook != null) getLogger().info("Vault integration enabled (Permission + Chat).");

        startTasks();

        // After /reload or a plugin manager restart, load players who are already online
        for (Player player : Bukkit.getOnlinePlayers()) {
            service.loadAndInjectOnline(player);
        }

        getLogger()
                .info(
                        "RexPerms "
                                + getDescription().getVersion()
                                + " enabled on "
                                + Bukkit.getName()
                                + " "
                                + ServerVersion.describe()
                                + ". Storage: "
                                + storage.storage().getName()
                                + ", "
                                + groupManager.all().size()
                                + " groups, "
                                + trackManager.all().size()
                                + " tracks ("
                                + (System.currentTimeMillis() - start)
                                + " ms)");
    }

    @Override
    public void onDisable() {
        if (syncTask != null) syncTask.cancel();
        if (expiryTask != null) expiryTask.cancel();
        if (verboseTask != null) verboseTask.cancel();

        for (Player player : Bukkit.getOnlinePlayers()) {
            PermissibleInjector.uninject(player);
        }
        if (placeholderHook != null) {
            try {
                placeholderHook.unregister();
            } catch (Throwable ignored) {
                // PlaceholderAPI may already be gone
            }
        }
        if (vaultHook != null) vaultHook.unregister();
        getServer().getServicesManager().unregisterAll(this);

        if (storage != null) {
            // Make sure nothing is left unsaved
            for (final User user : userManager.all()) {
                storage.run(s -> s.saveUser(user));
            }
            storage.shutdown();
        }
        if (messages != null) messages.disable();
    }

    /** /rp reload: re-reads config.yml and messages.yml. */
    public void reloadSettings() {
        reloadConfig();
        String previousStorage = settings.storageType();
        settings = Settings.load(getConfig());
        messages.load();
        contextManager.update(settings);
        applyCalculatorSettings();
        epoch.bump();
        if (!previousStorage.equals(settings.storageType())) {
            getLogger().warning("A storage type change only takes effect after a server restart.");
        }
        startTasks();
        service.refreshAllOnline();
    }

    // ------------------------------------------------------------------ internal setup

    private Storage createStorage() {
        String type = settings.storageType();
        if (type.equals("mysql")) {
            return new SqlStorage(
                    SqlStorage.Dialect.MYSQL,
                    settings.credentials(),
                    getDataFolder(),
                    settings.tablePrefix(),
                    getLogger());
        }
        if (type.equals("mariadb")) {
            return new SqlStorage(
                    SqlStorage.Dialect.MARIADB,
                    settings.credentials(),
                    getDataFolder(),
                    settings.tablePrefix(),
                    getLogger());
        }
        if (type.equals("sqlite")) {
            return new SqlStorage(
                    SqlStorage.Dialect.SQLITE,
                    settings.credentials(),
                    getDataFolder(),
                    settings.tablePrefix(),
                    getLogger());
        }
        if (!type.equals("yaml") && !type.equals("yml")) {
            getLogger().warning("Unknown storage type '" + type + "', using YAML.");
        }
        return new YamlStorage(getDataFolder(), getLogger());
    }

    private void applyCalculatorSettings() {
        calculator.setApplyWildcards(settings.applyWildcards());
        calculator.setApplyChildPermissions(settings.applyChildPermissions());
        calculator.setDefaultGroupFallback(settings.defaultGroupFallback());
        calculator.setStoredPrimaryGroup(settings.storedPrimaryGroup());
        calculator.setChildProvider(
                new ChildPermissionProvider() {
                    @Override
                    public Map<String, Boolean> children(String permission) {
                        Permission p = Bukkit.getPluginManager().getPermission(permission);
                        if (p == null) return Collections.emptyMap();
                        Map<String, Boolean> children = p.getChildren();
                        return children.isEmpty()
                                ? Collections.<String, Boolean>emptyMap()
                                : new HashMap<String, Boolean>(children);
                    }
                });
    }

    private void startSync() {
        if (syncPoller != null) return;
        if (settings.syncEnabled() && storage.storage() instanceof SqlStorage) {
            SqlStorage sql = (SqlStorage) storage.storage();
            if (sql.getDialect() == SqlStorage.Dialect.SQLITE) return;
            syncPoller = new SyncPoller(sql, service::handleSyncMessage, getLogger());
            try {
                syncPoller.start();
                getLogger().info("Network sync enabled (SQL polling).");
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Could not start network sync", e);
                syncPoller = null;
            }
        }
    }

    private void startTasks() {
        if (syncTask != null) syncTask.cancel();
        if (expiryTask != null) expiryTask.cancel();
        if (verboseTask != null) verboseTask.cancel();

        if (syncPoller != null) {
            long ticks = settings.syncIntervalSeconds() * 20L;
            syncTask =
                    Bukkit.getScheduler()
                            .runTaskTimer(
                                    this, () -> storage.run(s -> syncPoller.poll()), ticks, ticks);
        }
        long expiryTicks = settings.expiryCheckInterval() * 20L;
        expiryTask =
                Bukkit.getScheduler()
                        .runTaskTimer(this, () -> service.checkExpiry(), expiryTicks, expiryTicks);
        verboseTask =
                Bukkit.getScheduler().runTaskTimer(this, () -> verboseHandler.flush(), 1L, 1L);
    }

    // ------------------------------------------------------------------ accessors

    public Settings getSettings() {
        return settings;
    }

    public Messages getMessages() {
        return messages;
    }

    public DataEpoch getEpoch() {
        return epoch;
    }

    public GroupManager getGroupManager() {
        return groupManager;
    }

    public UserManager getUserManager() {
        return userManager;
    }

    public TrackManager getTrackManager() {
        return trackManager;
    }

    public PermissionCalculator getCalculator() {
        return calculator;
    }

    public ContextManager getContextManager() {
        return contextManager;
    }

    public StorageExecutor getStorage() {
        return storage;
    }

    public SyncPoller getSyncPoller() {
        return syncPoller;
    }

    public VerboseHandler getVerboseHandler() {
        return verboseHandler;
    }

    public DataService getService() {
        return service;
    }

    public RexPermsApi getApi() {
        return api;
    }

    /** Executor that runs on the main thread (for CompletableFuture.thenAcceptAsync). */
    public Executor mainThread() {
        return mainExecutor;
    }
}
