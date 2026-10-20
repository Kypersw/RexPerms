package xyz.rexos.rexperms.migration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import xyz.rexos.rexperms.RexPermsPlugin;
import xyz.rexos.rexperms.model.ContextSet;
import xyz.rexos.rexperms.model.Group;
import xyz.rexos.rexperms.model.Node;
import xyz.rexos.rexperms.model.Track;
import xyz.rexos.rexperms.model.User;
import xyz.rexos.rexperms.storage.HeldNode;
import xyz.rexos.rexperms.storage.Storage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Export / import. The file format is the same as LuckPerms' {@code /lp export}
 * output, so moving over from LuckPerms takes one command:
 *
 * <pre>
 *   1) With LuckPerms installed:   /lp export migration
 *   2) Copy plugins/LuckPerms/migration.json.gz to plugins/RexPerms/
 *   3) Remove LuckPerms, install RexPerms
 *   4) From the console:           rp import migration.json.gz
 * </pre>
 */
public final class MigrationService {

    public static final class ImportResult {
        private final int groups;
        private final int tracks;
        private final int users;
        private final int skipped;

        ImportResult(int groups, int tracks, int users, int skipped) {
            this.groups = groups;
            this.tracks = tracks;
            this.users = users;
            this.skipped = skipped;
        }

        public int groups() {
            return groups;
        }

        public int tracks() {
            return tracks;
        }

        public int users() {
            return users;
        }

        public int skipped() {
            return skipped;
        }
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final RexPermsPlugin plugin;

    public MigrationService(RexPermsPlugin plugin) {
        this.plugin = plugin;
    }

    private static <T> CompletableFuture<T> failed(Throwable t) {
        CompletableFuture<T> f = new CompletableFuture<T>();
        f.completeExceptionally(t);
        return f;
    }

    // ================================================================== export

    public CompletableFuture<File> export(String name) {
        String fileName = name.endsWith(".json.gz") ? name : name + ".json.gz";
        final File file = new File(plugin.getDataFolder(), fileName.replace("/", "_").replace("\\", "_"));
        // Snapshot groups and tracks on the main thread
        final Map<String, List<Node>> groups = new LinkedHashMap<String, List<Node>>();
        for (Group g : plugin.getGroupManager().sorted()) groups.put(g.getName(), g.getNodes());
        final Map<String, List<String>> tracks = new LinkedHashMap<String, List<String>>();
        for (Track t : plugin.getTrackManager().all()) tracks.put(t.getName(), t.getGroups());

        return plugin.getStorage().submit(storage -> {
            JsonObject root = new JsonObject();
            JsonObject meta = new JsonObject();
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            meta.addProperty("generatedBy", "RexPerms " + plugin.getDescription().getVersion());
            meta.addProperty("generatedAt", iso.format(new Date()));
            root.add("metadata", meta);

            JsonObject groupsJson = new JsonObject();
            for (Map.Entry<String, List<Node>> e : groups.entrySet()) {
                JsonObject obj = new JsonObject();
                obj.add("nodes", nodesToJson(e.getValue()));
                groupsJson.add(e.getKey(), obj);
            }
            root.add("groups", groupsJson);

            JsonObject tracksJson = new JsonObject();
            for (Map.Entry<String, List<String>> e : tracks.entrySet()) {
                JsonObject obj = new JsonObject();
                JsonArray arr = new JsonArray();
                for (String g : e.getValue()) arr.add(g);
                obj.add("groups", arr);
                tracksJson.add(e.getKey(), obj);
            }
            root.add("tracks", tracksJson);

            root.add("users", exportUsers(storage));

            Writer w = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(file)), StandardCharsets.UTF_8);
            try {
                GSON.toJson(root, w);
            } finally {
                w.close();
            }
            return file;
        });
    }

    private JsonObject exportUsers(Storage storage) throws Exception {
        Set<String> uuids = new LinkedHashSet<String>();
        for (HeldNode h : storage.searchUserNodes(n -> true)) uuids.add(h.holderId());
        JsonObject users = new JsonObject();
        for (String id : uuids) {
            UUID uuid;
            try {
                uuid = UUID.fromString(id);
            } catch (IllegalArgumentException e) {
                continue;
            }
            User u = plugin.getUserManager().create(uuid, null);
            storage.loadUser(u);
            JsonObject obj = new JsonObject();
            if (u.getUsername() != null) obj.addProperty("username", u.getUsername());
            obj.addProperty("primaryGroup", u.getPrimaryGroup());
            obj.add("nodes", nodesToJson(u.getNodes()));
            users.add(id, obj);
        }
        return users;
    }

    private static JsonArray nodesToJson(List<Node> nodes) {
        JsonArray arr = new JsonArray();
        for (Node n : nodes) {
            JsonObject o = new JsonObject();
            o.addProperty("key", n.key());
            o.addProperty("value", n.value());
            if (n.isTemporary()) o.addProperty("expiry", n.expiry());
            if (!n.contexts().isEmpty()) {
                JsonObject ctx = new JsonObject();
                for (Map.Entry<String, String> e : n.contexts().asMap().entrySet()) {
                    ctx.addProperty(e.getKey(), e.getValue());
                }
                o.add("context", ctx);
            }
            arr.add(o);
        }
        return arr;
    }

    // ================================================================== import

    public CompletableFuture<ImportResult> importFile(String name) {
        File folder = plugin.getDataFolder();
        final File file = new File(folder, name);
        try {
            // Block "../" escapes out of the plugin folder
            boolean inside = file.getCanonicalPath().startsWith(folder.getCanonicalPath() + File.separator);
            if (!inside || !file.isFile()) {
                return failed(new IOException("File not found: plugins/RexPerms/" + name));
            }
        } catch (IOException e) {
            return failed(e);
        }
        return plugin.getStorage().submit(storage -> {
            JsonObject root;
            Reader r = new InputStreamReader(open(file), StandardCharsets.UTF_8);
            try {
                root = JsonParser.parseReader(r).getAsJsonObject();
            } finally {
                r.close();
            }
            int[] skipped = {0};
            int groups = 0;
            int tracks = 0;
            int users = 0;

            final Map<String, List<Node>> importedGroups = new LinkedHashMap<String, List<Node>>();
            JsonObject groupsJson = obj(root, "groups");
            if (groupsJson != null) {
                for (Map.Entry<String, JsonElement> e : groupsJson.entrySet()) {
                    String gName = e.getKey().toLowerCase(Locale.ROOT);
                    if (!Group.isValidName(gName)) {
                        skipped[0]++;
                        continue;
                    }
                    List<Node> nodes = parseNodes(e.getValue().getAsJsonObject(), skipped);
                    Group g = new Group(gName, plugin.getEpoch());
                    g.replaceNodes(nodes);
                    storage.saveGroup(g);
                    importedGroups.put(gName, nodes);
                    groups++;
                }
            }

            final Map<String, List<String>> importedTracks = new LinkedHashMap<String, List<String>>();
            JsonObject tracksJson = obj(root, "tracks");
            if (tracksJson != null) {
                for (Map.Entry<String, JsonElement> e : tracksJson.entrySet()) {
                    String tName = e.getKey().toLowerCase(Locale.ROOT);
                    if (!Group.isValidName(tName)) {
                        skipped[0]++;
                        continue;
                    }
                    List<String> list = new ArrayList<String>();
                    JsonElement arr = e.getValue().getAsJsonObject().get("groups");
                    if (arr != null && arr.isJsonArray()) {
                        for (JsonElement g : arr.getAsJsonArray()) list.add(g.getAsString().toLowerCase(Locale.ROOT));
                    }
                    Track t = new Track(tName);
                    t.setGroups(list);
                    storage.saveTrack(t);
                    importedTracks.put(tName, list);
                    tracks++;
                }
            }

            final List<User> importedUsers = new ArrayList<User>();
            JsonObject usersJson = obj(root, "users");
            if (usersJson != null) {
                for (Map.Entry<String, JsonElement> e : usersJson.entrySet()) {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(e.getKey());
                    } catch (IllegalArgumentException ex) {
                        skipped[0]++;
                        continue;
                    }
                    JsonObject uo = e.getValue().getAsJsonObject();
                    String username = uo.has("username") && !uo.get("username").isJsonNull()
                            ? uo.get("username").getAsString() : null;
                    User u = plugin.getUserManager().create(uuid, username);
                    u.replaceNodes(parseNodes(uo, skipped));
                    if (uo.has("primaryGroup") && !uo.get("primaryGroup").isJsonNull()) {
                        u.setPrimaryGroup(uo.get("primaryGroup").getAsString());
                    }
                    plugin.getService().ensureDefaultGroup(u);
                    storage.saveUser(u);
                    if (username != null && username.matches("^[A-Za-z0-9_]{1,16}$")) {
                        storage.saveUsername(uuid, username);
                    }
                    importedUsers.add(u);
                    users++;
                }
            }

            // Update memory on the main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Map.Entry<String, List<Node>> e : importedGroups.entrySet()) {
                    plugin.getGroupManager().getOrCreate(e.getKey()).replaceNodes(e.getValue());
                }
                for (Map.Entry<String, List<String>> e : importedTracks.entrySet()) {
                    plugin.getTrackManager().getOrCreate(e.getKey()).setGroups(e.getValue());
                }
                for (User imported : importedUsers) {
                    User live = plugin.getUserManager().get(imported.getUniqueId());
                    if (live != null) {
                        live.replaceNodes(imported.getNodes());
                        live.setPrimaryGroup(imported.getPrimaryGroup());
                    }
                }
                plugin.getService().refreshAllOnline();
                plugin.getService().broadcastFullSync();
            });
            plugin.getService().logAction("Console", 'G', "*", "import: " + file.getName() + " (" + groups
                    + " groups, " + tracks + " tracks, " + users + " users)", null);
            return new ImportResult(groups, tracks, users, skipped[0]);
        });
    }

    private static InputStream open(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        return file.getName().endsWith(".gz") ? new GZIPInputStream(in) : in;
    }

    private static JsonObject obj(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /**
     * LuckPerms node format:
     * {"key":"x","value":true,"expiry":123,"context":{"world":"a"}} — a context value
     * may be an array when it has several values; the first one is used.
     */
    private static List<Node> parseNodes(JsonObject holder, int[] skipped) {
        List<Node> out = new ArrayList<Node>();
        if (holder == null) return out;
        JsonElement arr = holder.get("nodes");
        if (arr == null || !arr.isJsonArray()) return out;
        long now = System.currentTimeMillis() / 1000L;
        for (JsonElement el : arr.getAsJsonArray()) {
            try {
                JsonObject o = el.getAsJsonObject();
                String key = o.get("key").getAsString();
                boolean value = !o.has("value") || o.get("value").getAsBoolean();
                long expiry = o.has("expiry") && !o.get("expiry").isJsonNull() ? o.get("expiry").getAsLong() : 0L;
                if (expiry > 0 && expiry <= now) {
                    skipped[0]++; // already expired
                    continue;
                }
                Map<String, String> ctx = new LinkedHashMap<String, String>();
                JsonElement c = o.get("context");
                if (c != null && c.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> ce : c.getAsJsonObject().entrySet()) {
                        JsonElement v = ce.getValue();
                        if (v.isJsonArray()) {
                            JsonArray va = v.getAsJsonArray();
                            if (va.size() == 0) continue;
                            if (va.size() > 1) skipped[0]++; // multi-value: only the first is kept
                            ctx.put(ce.getKey(), va.get(0).getAsString());
                        } else {
                            ctx.put(ce.getKey(), v.getAsString());
                        }
                    }
                }
                out.add(Node.of(key, value, Math.max(0L, expiry), ContextSet.of(ctx)));
            } catch (RuntimeException e) {
                skipped[0]++;
            }
        }
        return out;
    }
}
