package xyz.rexos.rexperms.storage;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import xyz.rexos.rexperms.model.ContextSet;
import xyz.rexos.rexperms.model.DataEpoch;
import xyz.rexos.rexperms.model.Group;
import xyz.rexos.rexperms.model.Node;
import xyz.rexos.rexperms.model.Track;
import xyz.rexos.rexperms.model.User;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class StorageTest {

    @TempDir
    File dir;

    private Storage create(String type) throws Exception {
        Logger log = Logger.getLogger("test");
        Storage s = type.equals("yaml")
                ? new YamlStorage(dir, log)
                : new SqlStorage(SqlStorage.Dialect.SQLITE, null, dir, "rp_", log);
        s.init();
        return s;
    }

    @ParameterizedTest
    @ValueSource(strings = {"yaml", "sqlite"})
    void roundTrip(String type) throws Exception {
        Storage s = create(type);
        DataEpoch epoch = new DataEpoch();
        long future = System.currentTimeMillis() / 1000L + 3600;

        // Group
        Group admin = new Group("admin", epoch);
        admin.setNode(Node.weight(100));
        admin.setNode(Node.prefix(100, "&c[Admin. Team] ", ContextSet.EMPTY));
        admin.setNode(Node.permission("worldedit.*", true, ContextSet.of("world", "creative")));
        admin.setNode(Node.permission("essentials.nick", false, ContextSet.EMPTY));
        s.saveGroup(admin);
        Map<String, List<Node>> groups = s.loadAllGroups();
        assertEquals(1, groups.size());
        assertEquals(4, groups.get("admin").size());
        assertTrue(groups.get("admin").containsAll(admin.getNodes()));

        // User
        UUID uuid = UUID.randomUUID();
        User u = new User(uuid, "Alice", epoch);
        u.setNode(Node.inheritance("admin", ContextSet.EMPTY));
        u.setNode(Node.of("group.vip", true, future, ContextSet.of("server", "survival")));
        u.setNode(Node.meta("homes", "5", ContextSet.EMPTY));
        u.setPrimaryGroup("admin");
        s.saveUser(u);
        s.saveUsername(uuid, "Alice");

        User loaded = new User(uuid, null, epoch);
        assertTrue(s.loadUser(loaded));
        assertEquals("admin", loaded.getPrimaryGroup());
        assertEquals("Alice", loaded.getUsername());
        assertEquals(3, loaded.nodeCount());
        assertTrue(loaded.getNodes().containsAll(u.getNodes()));

        assertEquals(uuid, s.lookupUuid("alice"));
        assertEquals("Alice", s.lookupUsername(uuid));
        assertFalse(s.loadUser(new User(UUID.randomUUID(), null, epoch)));

        // Search
        List<HeldNode> found = s.searchUserNodes(n -> n.key().equals("group.admin"));
        assertEquals(1, found.size());
        assertEquals(uuid.toString(), found.get(0).holderId());

        // Track
        Track staff = new Track("staff");
        staff.setGroups(Arrays.asList("helper", "mod", "admin"));
        s.saveTrack(staff);
        assertEquals(Arrays.asList("helper", "mod", "admin"), s.loadAllTracks().get("staff"));

        // Audit log
        s.logAction(new LoggedAction(1L, "Console", 'U', "Alice", "parent add admin"));
        s.logAction(new LoggedAction(2L, "Console", 'G', "admin", "setweight 100"));
        List<LoggedAction> log = s.recentActions(10, null);
        assertEquals(2, log.size());
        assertEquals("setweight 100", log.get(0).description(), "newest first");
        assertEquals(1, s.recentActions(10, "alice").size());

        // Delete
        s.deleteGroup("admin");
        assertNull(s.loadGroup("admin"));
        s.deleteTrack("staff");
        assertTrue(s.loadAllTracks().isEmpty());

        // A user with only the default group should not take up storage
        User plain = new User(UUID.randomUUID(), "Bob", epoch);
        plain.setNode(Node.inheritance("default", ContextSet.EMPTY));
        s.saveUser(plain);
        assertTrue(s.searchUserNodes(n -> true).stream().noneMatch(h -> h.holderId().equals(plain.getIdentifier())));

        s.shutdown();
    }
}
