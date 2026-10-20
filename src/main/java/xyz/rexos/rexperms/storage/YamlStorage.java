package xyz.rexos.rexperms.storage;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import xyz.rexos.rexperms.model.Group;
import xyz.rexos.rexperms.model.HolderType;
import xyz.rexos.rexperms.model.Node;
import xyz.rexos.rexperms.model.PermissionHolder;
import xyz.rexos.rexperms.model.Track;
import xyz.rexos.rexperms.model.User;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * File based storage. Ideal for single servers; files can be edited by hand
 * (run /rp sync afterwards).
 *
 * <pre>
 * plugins/RexPerms/yaml-storage/
 *   users/&lt;uuid&gt;.yml
 *   groups/&lt;name&gt;.yml
 *   tracks/&lt;name&gt;.yml
 *   uuidcache.yml
 *   actions.log
 * </pre>
 */
public final class YamlStorage implements Storage {

    private final Path usersDir;
    private final Path groupsDir;
    private final Path tracksDir;
    private final Path uuidCacheFile;
    private final Path actionLogFile;
    private final Logger logger;

    /** lower-case name -> uuid */
    private final Map<String, UUID> nameToUuid = new ConcurrentHashMap<String, UUID>();
    private final Map<UUID, String> uuidToName = new ConcurrentHashMap<UUID, String>();

    public YamlStorage(File dataFolder, Logger logger) {
        Path root = dataFolder.toPath().resolve("yaml-storage");
        this.usersDir = root.resolve("users");
        this.groupsDir = root.resolve("groups");
        this.tracksDir = root.resolve("tracks");
        this.uuidCacheFile = root.resolve("uuidcache.yml");
        this.actionLogFile = root.resolve("actions.log");
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "YAML";
    }

    @Override
    public void init() throws IOException {
        Files.createDirectories(usersDir);
        Files.createDirectories(groupsDir);
        Files.createDirectories(tracksDir);
        if (Files.exists(uuidCacheFile)) {
            YamlConfiguration yaml = read(uuidCacheFile);
            for (String key : yaml.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    String name = yaml.getString(key);
                    if (name != null) {
                        uuidToName.put(uuid, name);
                        nameToUuid.put(name.toLowerCase(Locale.ROOT), uuid);
                    }
                } catch (IllegalArgumentException ignored) {
                    // corrupt line
                }
            }
        }
    }

    @Override
    public void shutdown() {
        // nothing to close
    }

    // ------------------------------------------------------------------ users

    @Override
    public boolean loadUser(User user) throws IOException {
        Path file = usersDir.resolve(user.getUniqueId() + ".yml");
        if (!Files.exists(file)) return false;
        YamlConfiguration yaml = read(file);
        if (user.getUsername() == null) user.setUsername(yaml.getString("name"));
        user.replaceNodes(NodeSerializer.fromYaml(yaml.getList("nodes"), logger, file.getFileName().toString()));
        user.setPrimaryGroup(yaml.getString("primary-group", "default"));
        return true;
    }

    @Override
    public void saveUser(User user) throws IOException {
        Path file = usersDir.resolve(user.getUniqueId() + ".yml");
        if (user.isDefaultOnly()) {
            // No need to keep a file for default data
            Files.deleteIfExists(file);
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("uuid", user.getUniqueId().toString());
        yaml.set("name", user.getUsername());
        yaml.set("primary-group", user.getPrimaryGroup());
        yaml.set("nodes", NodeSerializer.toYaml(user.getNodes()));
        write(file, yaml);
    }

    @Override
    public void deleteUser(UUID uuid) throws IOException {
        Files.deleteIfExists(usersDir.resolve(uuid + ".yml"));
    }

    @Override
    public void saveUsername(UUID uuid, String username) throws IOException {
        String lower = username.toLowerCase(Locale.ROOT);
        String old = uuidToName.put(uuid, username);
        UUID prevOwner = nameToUuid.put(lower, uuid);
        if (username.equals(old) && uuid.equals(prevOwner)) return;
        if (old != null && !old.equalsIgnoreCase(username)) nameToUuid.remove(old.toLowerCase(Locale.ROOT), uuid);
        if (prevOwner != null && !prevOwner.equals(uuid)) uuidToName.remove(prevOwner);

        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, String> e : uuidToName.entrySet()) yaml.set(e.getKey().toString(), e.getValue());
        write(uuidCacheFile, yaml);
    }

    @Override
    public UUID lookupUuid(String username) {
        return nameToUuid.get(username.toLowerCase(Locale.ROOT));
    }

    @Override
    public String lookupUsername(UUID uuid) {
        return uuidToName.get(uuid);
    }

    @Override
    public List<HeldNode> searchUserNodes(PermissionHolder.NodeFilter filter) throws IOException {
        List<HeldNode> out = new ArrayList<HeldNode>();
        for (Path file : ymlFiles(usersDir)) {
            String fn = file.getFileName().toString();
            YamlConfiguration yaml = read(file);
            String id = fn.substring(0, fn.length() - 4);
            String name = yaml.getString("name", id);
            for (Node n : NodeSerializer.fromYaml(yaml.getList("nodes"), logger, fn)) {
                if (filter.test(n)) out.add(new HeldNode(HolderType.USER, id, name, n));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ groups

    @Override
    public Map<String, List<Node>> loadAllGroups() throws IOException {
        Map<String, List<Node>> out = new LinkedHashMap<String, List<Node>>();
        for (Path file : ymlFiles(groupsDir)) {
            String fn = file.getFileName().toString();
            String name = fn.substring(0, fn.length() - 4).toLowerCase(Locale.ROOT);
            if (!Group.isValidName(name)) {
                logger.warning("Skipped group file with an invalid name: " + fn);
                continue;
            }
            YamlConfiguration yaml = read(file);
            out.put(name, NodeSerializer.fromYaml(yaml.getList("nodes"), logger, fn));
        }
        return out;
    }

    @Override
    public List<Node> loadGroup(String name) throws IOException {
        Path file = groupsDir.resolve(name.toLowerCase(Locale.ROOT) + ".yml");
        if (!Files.exists(file)) return null;
        return NodeSerializer.fromYaml(read(file).getList("nodes"), logger, file.getFileName().toString());
    }

    @Override
    public void saveGroup(Group group) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("name", group.getName());
        yaml.set("nodes", NodeSerializer.toYaml(group.getNodes()));
        write(groupsDir.resolve(group.getName() + ".yml"), yaml);
    }

    @Override
    public void deleteGroup(String name) throws IOException {
        Files.deleteIfExists(groupsDir.resolve(name.toLowerCase(Locale.ROOT) + ".yml"));
    }

    @Override
    public List<HeldNode> searchGroupNodes(PermissionHolder.NodeFilter filter) throws IOException {
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
    public Map<String, List<String>> loadAllTracks() throws IOException {
        Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
        for (Path file : ymlFiles(tracksDir)) {
            String fn = file.getFileName().toString();
            YamlConfiguration yaml = read(file);
            out.put(fn.substring(0, fn.length() - 4).toLowerCase(Locale.ROOT), yaml.getStringList("groups"));
        }
        return out;
    }

    @Override
    public void saveTrack(Track track) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("name", track.getName());
        yaml.set("groups", new ArrayList<String>(track.getGroups()));
        write(tracksDir.resolve(track.getName() + ".yml"), yaml);
    }

    @Override
    public void deleteTrack(String name) throws IOException {
        Files.deleteIfExists(tracksDir.resolve(name.toLowerCase(Locale.ROOT) + ".yml"));
    }

    // ------------------------------------------------------------------ audit log

    @Override
    public void logAction(LoggedAction a) throws IOException {
        String line = a.time() + "\t" + clean(a.actorName()) + "\t" + a.targetType() + "\t"
                + clean(a.targetName()) + "\t" + clean(a.description()) + System.lineSeparator();
        Writer w = Files.newBufferedWriter(actionLogFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try {
            w.write(line);
        } finally {
            w.close();
        }
    }

    @Override
    public List<LoggedAction> recentActions(int limit, String targetFilter) throws IOException {
        if (!Files.exists(actionLogFile)) return new ArrayList<LoggedAction>();
        Deque<LoggedAction> window = new ArrayDeque<LoggedAction>(limit + 1);
        BufferedReader reader = Files.newBufferedReader(actionLogFile, StandardCharsets.UTF_8);
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] p = line.split("\t", 5);
                if (p.length < 5 || p[2].isEmpty()) continue;
                if (targetFilter != null && !p[3].equalsIgnoreCase(targetFilter)) continue;
                try {
                    window.addLast(new LoggedAction(Long.parseLong(p[0]), p[1], p[2].charAt(0), p[3], p[4]));
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (window.size() > limit) window.removeFirst();
            }
        } finally {
            reader.close();
        }
        List<LoggedAction> out = new ArrayList<LoggedAction>(window);
        Collections.reverse(out);
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static List<Path> ymlFiles(Path dir) throws IOException {
        List<Path> out = new ArrayList<Path>();
        DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.yml");
        try {
            for (Path p : stream) out.add(p);
        } finally {
            stream.close();
        }
        return out;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private YamlConfiguration read(Path file) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException e) {
            throw new IOException("Could not read YAML: " + file + " (" + e.getMessage() + ")", e);
        }
        return yaml;
    }

    /** Writes to a temp file and moves it, so a crash mid-write never corrupts the file. */
    private static void write(Path file, YamlConfiguration yaml) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, yaml.saveToString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
