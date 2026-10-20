package xyz.rexos.rexperms.config;

import org.bukkit.configuration.file.FileConfiguration;
import xyz.rexos.rexperms.storage.SqlStorage;

import java.util.Locale;

/** Typed, immutable view of config.yml. Refreshed by /rp reload. */
public final class Settings {

    private final String server;
    private final String storageType;
    private final String tablePrefix;
    private final SqlStorage.Credentials credentials;
    private final boolean syncEnabled;
    private final int syncIntervalSeconds;
    private final boolean applyWildcards;
    private final boolean applyChildPermissions;
    private final boolean applyDefaultPermissions;
    private final boolean defaultGroupFallback;
    private final boolean storedPrimaryGroup;
    private final boolean opHasAll;
    private final boolean contextWorld;
    private final boolean contextGamemode;
    private final boolean contextDimension;
    private final boolean chatEnabled;
    private final String chatFormat;
    private final String chatColorPermission;
    private final boolean tablistEnabled;
    private final String tablistFormat;
    private final boolean logNotify;
    private final boolean logConsole;
    private final int expiryCheckInterval;

    private Settings(FileConfiguration c) {
        this.server = c.getString("server", "global").trim().toLowerCase(Locale.ROOT);
        this.storageType = c.getString("storage.type", "yaml").trim().toLowerCase(Locale.ROOT);
        this.tablePrefix = c.getString("storage.table-prefix", "rexperms_");
        this.credentials = new SqlStorage.Credentials(
                c.getString("storage.mysql.host", "localhost"),
                c.getInt("storage.mysql.port", 3306),
                c.getString("storage.mysql.database", "minecraft"),
                c.getString("storage.mysql.username", "root"),
                c.getString("storage.mysql.password", ""),
                c.getInt("storage.mysql.pool-size", 8),
                c.getBoolean("storage.mysql.use-ssl", false));
        this.syncEnabled = c.getBoolean("sync.enabled", true);
        this.syncIntervalSeconds = Math.max(1, c.getInt("sync.interval-seconds", 3));
        this.applyWildcards = c.getBoolean("permissions.apply-wildcards", true);
        this.applyChildPermissions = c.getBoolean("permissions.apply-bukkit-child-permissions", true);
        this.applyDefaultPermissions = c.getBoolean("permissions.apply-bukkit-default-permissions", true);
        this.defaultGroupFallback = c.getBoolean("permissions.default-group-fallback", true);
        this.storedPrimaryGroup = "stored".equalsIgnoreCase(
                c.getString("permissions.primary-group-calculation", "parents-by-weight"));
        this.opHasAll = c.getBoolean("permissions.op-has-all-permissions", false);
        this.contextWorld = c.getBoolean("contexts.world", true);
        this.contextGamemode = c.getBoolean("contexts.gamemode", false);
        this.contextDimension = c.getBoolean("contexts.dimension", false);
        this.chatEnabled = c.getBoolean("chat.enabled", false);
        this.chatFormat = c.getString("chat.format", "{prefix}{name}{suffix}&8 » &7{message}");
        this.chatColorPermission = c.getString("chat.color-permission", "rexperms.chat.color");
        this.tablistEnabled = c.getBoolean("tablist.enabled", false);
        this.tablistFormat = c.getString("tablist.format", "{prefix}{name}{suffix}");
        this.logNotify = c.getBoolean("log.notify", true);
        this.logConsole = c.getBoolean("log.console", true);
        this.expiryCheckInterval = Math.max(1, c.getInt("expiry-check-interval", 5));
    }

    public static Settings load(FileConfiguration c) {
        return new Settings(c);
    }

    public boolean hasServerContext() {
        return !server.isEmpty() && !server.equals("global");
    }

    public String server() {
        return server;
    }

    public String storageType() {
        return storageType;
    }

    public String tablePrefix() {
        return tablePrefix;
    }

    public SqlStorage.Credentials credentials() {
        return credentials;
    }

    public boolean syncEnabled() {
        return syncEnabled;
    }

    public int syncIntervalSeconds() {
        return syncIntervalSeconds;
    }

    public boolean applyWildcards() {
        return applyWildcards;
    }

    public boolean applyChildPermissions() {
        return applyChildPermissions;
    }

    public boolean applyDefaultPermissions() {
        return applyDefaultPermissions;
    }

    public boolean defaultGroupFallback() {
        return defaultGroupFallback;
    }

    public boolean storedPrimaryGroup() {
        return storedPrimaryGroup;
    }

    public boolean opHasAll() {
        return opHasAll;
    }

    public boolean contextWorld() {
        return contextWorld;
    }

    public boolean contextGamemode() {
        return contextGamemode;
    }

    public boolean contextDimension() {
        return contextDimension;
    }

    public boolean chatEnabled() {
        return chatEnabled;
    }

    public String chatFormat() {
        return chatFormat;
    }

    public String chatColorPermission() {
        return chatColorPermission;
    }

    public boolean tablistEnabled() {
        return tablistEnabled;
    }

    public String tablistFormat() {
        return tablistFormat;
    }

    public boolean logNotify() {
        return logNotify;
    }

    public boolean logConsole() {
        return logConsole;
    }

    public int expiryCheckInterval() {
        return expiryCheckInterval;
    }
}
