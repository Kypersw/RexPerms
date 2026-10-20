package xyz.rexos.rexperms.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import xyz.rexos.rexperms.model.ContextSet;
import xyz.rexos.rexperms.model.Group;
import xyz.rexos.rexperms.model.HolderType;
import xyz.rexos.rexperms.model.Node;
import xyz.rexos.rexperms.model.PermissionHolder;
import xyz.rexos.rexperms.model.Track;
import xyz.rexos.rexperms.model.User;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * SQL storage (MySQL, MariaDB, SQLite). Use MySQL/MariaDB for networks
 * (BungeeCord/Velocity): every server shares the database and
 * {@link SyncPoller} propagates changes.
 *
 * Statements are deliberately portable: old servers (1.8–1.12) ship
 * SQLite and MySQL drivers from 2015, which lack modern upsert syntax.
 */
public final class SqlStorage implements Storage {

    public enum Dialect {
        MYSQL, MARIADB, SQLITE;

        boolean isSqlite() {
            return this == SQLITE;
        }
    }

    /** Connection settings (from config.yml). */
    public static final class Credentials {
        final String host;
        final int port;
        final String database;
        final String username;
        final String password;
        final int poolSize;
        final boolean useSsl;

        public Credentials(String host, int port, String database, String username, String password,
                           int poolSize, boolean useSsl) {
            this.host = host;
            this.port = port;
            this.database = database;
            this.username = username;
            this.password = password;
            this.poolSize = poolSize;
            this.useSsl = useSsl;
        }
    }

    /** One network sync row. */
    public static final class SyncMessage {
        private final long id;
        private final String origin;
        private final String type;
        private final String target;

        public SyncMessage(long id, String origin, String type, String target) {
            this.id = id;
            this.origin = origin;
            this.type = type;
            this.target = target;
        }

        public long id() {
            return id;
        }

        public String origin() {
            return origin;
        }

        public String type() {
            return type;
        }

        public String target() {
            return target;
        }

        @Override
        public String toString() {
            return type + ":" + target;
        }
    }

    private final Dialect dialect;
    private final Credentials credentials;
    private final File sqliteFile;
    private final String p; // table prefix
    private final Logger logger;
    private HikariDataSource dataSource;

    public SqlStorage(Dialect dialect, Credentials credentials, File dataFolder, String tablePrefix, Logger logger) {
        this.dialect = dialect;
        this.credentials = credentials;
        this.sqliteFile = new File(dataFolder, "rexperms.db");
        this.p = tablePrefix == null || tablePrefix.trim().isEmpty() ? "rexperms_" : tablePrefix;
        this.logger = logger;
    }

    @Override
    public String getName() {
        switch (dialect) {
            case MYSQL:
                return "MySQL";
            case MARIADB:
                return "MariaDB";
            default:
                return "SQLite";
        }
    }

    public Dialect getDialect() {
        return dialect;
    }

    @Override
    public void init() throws SQLException {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("RexPerms-Pool");
        if (dialect.isSqlite()) {
            sqliteFile.getParentFile().mkdirs();
            cfg.setDriverClassName("org.sqlite.JDBC");
            cfg.setJdbcUrl("jdbc:sqlite:" + sqliteFile.getAbsolutePath());
            // SQLite allows a single writer
            cfg.setMaximumPoolSize(1);
            // Old drivers do not implement Connection#isValid
            cfg.setConnectionTestQuery("SELECT 1");
        } else {
            // Newer servers ship com.mysql.cj, 1.8–1.12 ship the legacy com.mysql.jdbc driver
            String driver = dialect == Dialect.MARIADB
                    ? firstAvailable("org.mariadb.jdbc.Driver", "com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver")
                    : firstAvailable("com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver", "org.mariadb.jdbc.Driver");
            if (driver == null) throw new SQLException("No MySQL/MariaDB JDBC driver found on this server");
            String protocol = driver.startsWith("org.mariadb") ? "mariadb" : "mysql";
            cfg.setDriverClassName(driver);
            cfg.setJdbcUrl("jdbc:" + protocol + "://" + credentials.host + ":" + credentials.port + "/"
                    + credentials.database);
            cfg.setUsername(credentials.username);
            cfg.setPassword(credentials.password);
            cfg.setMaximumPoolSize(Math.max(2, credentials.poolSize));
            cfg.setMinimumIdle(Math.min(2, credentials.poolSize));
            cfg.setMaxLifetime(1_800_000L);
            cfg.setConnectionTimeout(5_000L);
            cfg.addDataSourceProperty("useSSL", String.valueOf(credentials.useSsl));
            cfg.addDataSourceProperty("cachePrepStmts", "true");
            cfg.addDataSourceProperty("prepStmtCacheSize", "250");
            cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            cfg.addDataSourceProperty("useServerPrepStmts", "true");
            cfg.addDataSourceProperty("rewriteBatchedStatements", "true");
            cfg.addDataSourceProperty("characterEncoding", "utf8");
            cfg.addDataSourceProperty("useUnicode", "true");
            cfg.addDataSourceProperty("allowPublicKeyRetrieval", "true");
        }
        dataSource = new HikariDataSource(cfg);
        createTables();
    }

    private static String firstAvailable(String... classes) {
        for (String c : classes) {
            try {
                Class.forName(c);
                return c;
            } catch (ClassNotFoundException ignored) {
                // try next
            }
        }
        return null;
    }

    private void createTables() throws SQLException {
        String autoId = dialect.isSqlite() ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT AUTO_INCREMENT PRIMARY KEY";
        String engine = dialect.isSqlite() ? "" : " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        String[] ddl = {
                "CREATE TABLE IF NOT EXISTS " + p + "players ("
                        + "uuid VARCHAR(36) NOT NULL PRIMARY KEY, "
                        + "username VARCHAR(16) NOT NULL, "
                        + "primary_group VARCHAR(36) NOT NULL DEFAULT 'default')" + engine,
                "CREATE TABLE IF NOT EXISTS " + p + "user_nodes ("
                        + "id " + autoId + ", "
                        + "uuid VARCHAR(36) NOT NULL, "
                        + "node_key VARCHAR(512) NOT NULL, "
                        + "node_value TINYINT NOT NULL DEFAULT 1, "
                        + "contexts VARCHAR(255) NOT NULL DEFAULT '', "
                        + "expiry BIGINT NOT NULL DEFAULT 0)" + engine,
                "CREATE TABLE IF NOT EXISTS " + p + "groups ("
                        + "name VARCHAR(36) NOT NULL PRIMARY KEY)" + engine,
                "CREATE TABLE IF NOT EXISTS " + p + "group_nodes ("
                        + "id " + autoId + ", "
                        + "name VARCHAR(36) NOT NULL, "
                        + "node_key VARCHAR(512) NOT NULL, "
                        + "node_value TINYINT NOT NULL DEFAULT 1, "
                        + "contexts VARCHAR(255) NOT NULL DEFAULT '', "
                        + "expiry BIGINT NOT NULL DEFAULT 0)" + engine,
                "CREATE TABLE IF NOT EXISTS " + p + "tracks ("
                        + "name VARCHAR(36) NOT NULL PRIMARY KEY, "
                        + "group_list TEXT NOT NULL)" + engine,
                "CREATE TABLE IF NOT EXISTS " + p + "actions ("
                        + "id " + autoId + ", "
                        + "time BIGINT NOT NULL, "
                        + "actor_name VARCHAR(100) NOT NULL, "
                        + "target_type CHAR(1) NOT NULL, "
                        + "target_name VARCHAR(100) NOT NULL, "
                        + "description TEXT NOT NULL)" + engine,
                "CREATE TABLE IF NOT EXISTS " + p + "messages ("
                        + "id " + autoId + ", "
                        + "time BIGINT NOT NULL, "
                        + "origin VARCHAR(64) NOT NULL, "
                        + "type VARCHAR(16) NOT NULL, "
                        + "target VARCHAR(64) NOT NULL)" + engine
        };
        String[] indexes = {
                "CREATE INDEX " + p + "user_nodes_uuid ON " + p + "user_nodes (uuid)",
                "CREATE INDEX " + p + "group_nodes_name ON " + p + "group_nodes (name)",
                "CREATE INDEX " + p + "players_username ON " + p + "players (username)",
                "CREATE INDEX " + p + "actions_target ON " + p + "actions (target_name)"
        };
        Connection c = dataSource.getConnection();
        try {
            Statement st = c.createStatement();
            try {
                for (String sql : ddl) st.execute(sql);
            } finally {
                st.close();
            }
            // MySQL has no "CREATE INDEX IF NOT EXISTS": ignore "already exists"
            for (String sql : indexes) {
                Statement is = c.createStatement();
                try {
                    is.execute(dialect.isSqlite() ? sql.replace("CREATE INDEX", "CREATE INDEX IF NOT EXISTS") : sql);
                } catch (SQLException ignored) {
                    // index exists
                } finally {
                    is.close();
                }
            }
        } finally {
            c.close();
        }
    }

    @Override
    public void shutdown() {
        if (dataSource != null) dataSource.close();
    }

    // ------------------------------------------------------------------ users

    @Override
    public boolean loadUser(User user) throws SQLException {
        String uuid = user.getUniqueId().toString();
        Connection c = dataSource.getConnection();
        try {
            boolean exists = false;
            PreparedStatement ps = c.prepareStatement(
                    "SELECT username, primary_group FROM " + p + "players WHERE uuid = ?");
            try {
                ps.setString(1, uuid);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    exists = true;
                    if (user.getUsername() == null) user.setUsername(rs.getString(1));
                    user.setPrimaryGroup(rs.getString(2));
                }
                rs.close();
            } finally {
                ps.close();
            }
            List<Node> nodes = readNodes(c, "user_nodes", "uuid", uuid);
            if (!nodes.isEmpty()) exists = true;
            user.replaceNodes(nodes);
            return exists;
        } finally {
            c.close();
        }
    }

    @Override
    public void saveUser(User user) throws SQLException {
        String uuid = user.getUniqueId().toString();
        Connection c = dataSource.getConnection();
        try {
            c.setAutoCommit(false);
            try {
                writeNodes(c, "user_nodes", "uuid", uuid, user.isDefaultOnly() ? new ArrayList<Node>() : user.getNodes());
                if (user.getUsername() != null) {
                    upsertPlayer(c, uuid, user.getUsername(), user.getPrimaryGroup());
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } finally {
            c.close();
        }
    }

    @Override
    public void deleteUser(UUID uuid) throws SQLException {
        update("DELETE FROM " + p + "user_nodes WHERE uuid = ?", uuid.toString());
    }

    @Override
    public void saveUsername(UUID uuid, String username) throws SQLException {
        Connection c = dataSource.getConnection();
        try {
            // Someone else used to have this name (name change): free it
            PreparedStatement free = c.prepareStatement(
                    "UPDATE " + p + "players SET username = ? WHERE username = ? AND uuid <> ?");
            try {
                free.setString(1, "~" + Long.toHexString(System.nanoTime()).substring(0, 8));
                free.setString(2, username);
                free.setString(3, uuid.toString());
                free.executeUpdate();
            } finally {
                free.close();
            }
            PreparedStatement upd = c.prepareStatement("UPDATE " + p + "players SET username = ? WHERE uuid = ?");
            int changed;
            try {
                upd.setString(1, username);
                upd.setString(2, uuid.toString());
                changed = upd.executeUpdate();
            } finally {
                upd.close();
            }
            if (changed == 0) {
                PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO " + p + "players (uuid, username, primary_group) VALUES (?, ?, 'default')");
                try {
                    ins.setString(1, uuid.toString());
                    ins.setString(2, username);
                    ins.executeUpdate();
                } finally {
                    ins.close();
                }
            }
        } finally {
            c.close();
        }
    }

    private void upsertPlayer(Connection c, String uuid, String username, String primaryGroup) throws SQLException {
        PreparedStatement upd = c.prepareStatement(
                "UPDATE " + p + "players SET username = ?, primary_group = ? WHERE uuid = ?");
        int changed;
        try {
            upd.setString(1, username);
            upd.setString(2, primaryGroup);
            upd.setString(3, uuid);
            changed = upd.executeUpdate();
        } finally {
            upd.close();
        }
        if (changed == 0) {
            PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO " + p + "players (uuid, username, primary_group) VALUES (?, ?, ?)");
            try {
                ins.setString(1, uuid);
                ins.setString(2, username);
                ins.setString(3, primaryGroup);
                ins.executeUpdate();
            } finally {
                ins.close();
            }
        }
    }

    @Override
    public UUID lookupUuid(String username) throws SQLException {
        String v = queryString("SELECT uuid FROM " + p + "players WHERE LOWER(username) = ? LIMIT 1",
                username.toLowerCase(Locale.ROOT));
        return v == null ? null : UUID.fromString(v);
    }

    @Override
    public String lookupUsername(UUID uuid) throws SQLException {
        return queryString("SELECT username FROM " + p + "players WHERE uuid = ?", uuid.toString());
    }

    @Override
    public List<HeldNode> searchUserNodes(PermissionHolder.NodeFilter filter) throws SQLException {
        List<HeldNode> out = new ArrayList<HeldNode>();
        String sql = "SELECT n.uuid, pl.username, n.node_key, n.node_value, n.contexts, n.expiry FROM "
                + p + "user_nodes n LEFT JOIN " + p + "players pl ON pl.uuid = n.uuid";
        Connection c = dataSource.getConnection();
        try {
            PreparedStatement ps = c.prepareStatement(sql);
            try {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Node n = toNode(rs.getString(3), rs.getInt(4), rs.getString(5), rs.getLong(6));
                    if (n != null && filter.test(n)) {
                        String name = rs.getString(2);
                        out.add(new HeldNode(HolderType.USER, rs.getString(1), name == null ? rs.getString(1) : name, n));
                    }
                }
                rs.close();
            } finally {
                ps.close();
            }
        } finally {
            c.close();
        }
        return out;
    }

    // ------------------------------------------------------------------ groups

    @Override
    public Map<String, List<Node>> loadAllGroups() throws SQLException {
        Map<String, List<Node>> out = new LinkedHashMap<String, List<Node>>();
        Connection c = dataSource.getConnection();
        try {
            PreparedStatement ps = c.prepareStatement("SELECT name FROM " + p + "groups");
            try {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) out.put(rs.getString(1).toLowerCase(Locale.ROOT), new ArrayList<Node>());
                rs.close();
            } finally {
                ps.close();
            }
            ps = c.prepareStatement("SELECT name, node_key, node_value, contexts, expiry FROM " + p
                    + "group_nodes ORDER BY id");
            try {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    List<Node> list = out.get(rs.getString(1).toLowerCase(Locale.ROOT));
                    if (list == null) continue; // orphan row
                    Node n = toNode(rs.getString(2), rs.getInt(3), rs.getString(4), rs.getLong(5));
                    if (n != null) list.add(n);
                }
                rs.close();
            } finally {
                ps.close();
            }
        } finally {
            c.close();
        }
        return out;
    }

    @Override
    public List<Node> loadGroup(String name) throws SQLException {
        String lower = name.toLowerCase(Locale.ROOT);
        if (queryString("SELECT name FROM " + p + "groups WHERE name = ?", lower) == null) return null;
        Connection c = dataSource.getConnection();
        try {
            return readNodes(c, "group_nodes", "name", lower);
        } finally {
            c.close();
        }
    }

    @Override
    public void saveGroup(Group group) throws SQLException {
        Connection c = dataSource.getConnection();
        try {
            c.setAutoCommit(false);
            try {
                PreparedStatement check = c.prepareStatement("SELECT name FROM " + p + "groups WHERE name = ?");
                boolean exists;
                try {
                    check.setString(1, group.getName());
                    ResultSet rs = check.executeQuery();
                    exists = rs.next();
                    rs.close();
                } finally {
                    check.close();
                }
                if (!exists) {
                    PreparedStatement ins = c.prepareStatement("INSERT INTO " + p + "groups (name) VALUES (?)");
                    try {
                        ins.setString(1, group.getName());
                        ins.executeUpdate();
                    } finally {
                        ins.close();
                    }
                }
                writeNodes(c, "group_nodes", "name", group.getName(), group.getNodes());
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } finally {
            c.close();
        }
    }

    @Override
    public void deleteGroup(String name) throws SQLException {
        String lower = name.toLowerCase(Locale.ROOT);
        update("DELETE FROM " + p + "group_nodes WHERE name = ?", lower);
        update("DELETE FROM " + p + "groups WHERE name = ?", lower);
    }

    @Override
    public List<HeldNode> searchGroupNodes(PermissionHolder.NodeFilter filter) throws SQLException {
        List<HeldNode> out = new ArrayList<HeldNode>();
        for (Map.Entry<String, List<Node>> e : loadAllGroups().entrySet()) {
            for (Node n : e.getValue()) {
                if (filter.test(n)) out.add(new HeldNode(HolderType.GROUP, e.getKey(), e.getKey(), n));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ tracks

    @Override
    public Map<String, List<String>> loadAllTracks() throws SQLException {
        Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
        Connection c = dataSource.getConnection();
        try {
            PreparedStatement ps = c.prepareStatement("SELECT name, group_list FROM " + p + "tracks");
            try {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    String list = rs.getString(2);
                    List<String> groups = list == null || list.trim().isEmpty()
                            ? new ArrayList<String>()
                            : new ArrayList<String>(Arrays.asList(list.split(",")));
                    out.put(rs.getString(1).toLowerCase(Locale.ROOT), groups);
                }
                rs.close();
            } finally {
                ps.close();
            }
        } finally {
            c.close();
        }
        return out;
    }

    @Override
    public void saveTrack(Track track) throws SQLException {
        StringBuilder sb = new StringBuilder();
        for (String g : track.getGroups()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(g);
        }
        String list = sb.toString();
        if (update("UPDATE " + p + "tracks SET group_list = ? WHERE name = ?", list, track.getName()) == 0) {
            update("INSERT INTO " + p + "tracks (name, group_list) VALUES (?, ?)", track.getName(), list);
        }
    }

    @Override
    public void deleteTrack(String name) throws SQLException {
        update("DELETE FROM " + p + "tracks WHERE name = ?", name.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ audit log

    @Override
    public void logAction(LoggedAction a) throws SQLException {
        Connection c = dataSource.getConnection();
        try {
            PreparedStatement ps = c.prepareStatement("INSERT INTO " + p + "actions "
                    + "(time, actor_name, target_type, target_name, description) VALUES (?, ?, ?, ?, ?)");
            try {
                ps.setLong(1, a.time());
                ps.setString(2, truncate(a.actorName(), 100));
                ps.setString(3, String.valueOf(a.targetType()));
                ps.setString(4, truncate(a.targetName(), 100));
                ps.setString(5, a.description());
                ps.executeUpdate();
            } finally {
                ps.close();
            }
        } finally {
            c.close();
        }
    }

    @Override
    public List<LoggedAction> recentActions(int limit, String targetFilter) throws SQLException {
        String sql = "SELECT time, actor_name, target_type, target_name, description FROM " + p + "actions"
                + (targetFilter != null ? " WHERE LOWER(target_name) = ?" : "")
                + " ORDER BY id DESC LIMIT " + Math.max(1, Math.min(limit, 10_000));
        List<LoggedAction> out = new ArrayList<LoggedAction>();
        Connection c = dataSource.getConnection();
        try {
            PreparedStatement ps = c.prepareStatement(sql);
            try {
                if (targetFilter != null) ps.setString(1, targetFilter.toLowerCase(Locale.ROOT));
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    String t = rs.getString(3);
                    out.add(new LoggedAction(rs.getLong(1), rs.getString(2), t == null || t.isEmpty() ? '?' : t.charAt(0),
                            rs.getString(4), rs.getString(5)));
                }
                rs.close();
            } finally {
                ps.close();
            }
        } finally {
            c.close();
        }
        return out;
    }

    // ------------------------------------------------------------------ network sync

    public void pushMessage(String origin, String type, String target) throws SQLException {
        Connection c = dataSource.getConnection();
        try {
            PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + p + "messages (time, origin, type, target) VALUES (?, ?, ?, ?)");
            try {
                ps.setLong(1, System.currentTimeMillis() / 1000L);
                ps.setString(2, origin);
                ps.setString(3, type);
                ps.setString(4, target);
                ps.executeUpdate();
            } finally {
                ps.close();
            }
        } finally {
            c.close();
        }
    }

    public long latestMessageId() throws SQLException {
        String v = queryString("SELECT MAX(id) FROM " + p + "messages");
        return v == null ? 0L : Long.parseLong(v);
    }

    public List<SyncMessage> pollMessages(long afterId) throws SQLException {
        List<SyncMessage> out = new ArrayList<SyncMessage>();
        Connection c = dataSource.getConnection();
        try {
            PreparedStatement ps = c.prepareStatement(
                    "SELECT id, origin, type, target FROM " + p + "messages WHERE id > ? ORDER BY id LIMIT 500");
            try {
                ps.setLong(1, afterId);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    out.add(new SyncMessage(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
                rs.close();
            } finally {
                ps.close();
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** Deletes sync rows older than an hour. */
    public void cleanupMessages() throws SQLException {
        Connection c = dataSource.getConnection();
        try {
            PreparedStatement ps = c.prepareStatement("DELETE FROM " + p + "messages WHERE time < ?");
            try {
                ps.setLong(1, System.currentTimeMillis() / 1000L - 3600L);
                ps.executeUpdate();
            } finally {
                ps.close();
            }
        } finally {
            c.close();
        }
    }

    // ------------------------------------------------------------------ helpers

    private int update(String sql, String... params) throws SQLException {
        Connection c = dataSource.getConnection();
        try {
            PreparedStatement ps = c.prepareStatement(sql);
            try {
                for (int i = 0; i < params.length; i++) ps.setString(i + 1, params[i]);
                return ps.executeUpdate();
            } finally {
                ps.close();
            }
        } finally {
            c.close();
        }
    }

    private String queryString(String sql, String... params) throws SQLException {
        Connection c = dataSource.getConnection();
        try {
            PreparedStatement ps = c.prepareStatement(sql);
            try {
                for (int i = 0; i < params.length; i++) ps.setString(i + 1, params[i]);
                ResultSet rs = ps.executeQuery();
                try {
                    return rs.next() ? rs.getString(1) : null;
                } finally {
                    rs.close();
                }
            } finally {
                ps.close();
            }
        } finally {
            c.close();
        }
    }

    private List<Node> readNodes(Connection c, String table, String column, String id) throws SQLException {
        List<Node> out = new ArrayList<Node>();
        PreparedStatement ps = c.prepareStatement("SELECT node_key, node_value, contexts, expiry FROM "
                + p + table + " WHERE " + column + " = ? ORDER BY id");
        try {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Node n = toNode(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getLong(4));
                if (n != null) out.add(n);
            }
            rs.close();
        } finally {
            ps.close();
        }
        return out;
    }

    /** Rewrites all rows of one holder (inside the caller's transaction). */
    private void writeNodes(Connection c, String table, String column, String id, List<Node> nodes)
            throws SQLException {
        PreparedStatement del = c.prepareStatement("DELETE FROM " + p + table + " WHERE " + column + " = ?");
        try {
            del.setString(1, id);
            del.executeUpdate();
        } finally {
            del.close();
        }
        if (nodes.isEmpty()) return;
        PreparedStatement ins = c.prepareStatement("INSERT INTO " + p + table
                + " (" + column + ", node_key, node_value, contexts, expiry) VALUES (?, ?, ?, ?, ?)");
        try {
            for (Node n : nodes) {
                ins.setString(1, id);
                ins.setString(2, n.key());
                ins.setInt(3, n.value() ? 1 : 0);
                ins.setString(4, n.contexts().serialize());
                ins.setLong(5, n.expiry());
                ins.addBatch();
            }
            ins.executeBatch();
        } finally {
            ins.close();
        }
    }

    private Node toNode(String key, int value, String contexts, long expiry) {
        try {
            return Node.of(key, value != 0, Math.max(0L, expiry), ContextSet.parse(contexts));
        } catch (RuntimeException e) {
            logger.warning("Skipped invalid node in database: " + key + " (" + e.getMessage() + ")");
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
