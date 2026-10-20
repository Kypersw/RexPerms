package xyz.rexos.rexperms.calc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.rexos.rexperms.manager.GroupManager;
import xyz.rexos.rexperms.model.ContextSet;
import xyz.rexos.rexperms.model.DataEpoch;
import xyz.rexos.rexperms.model.DataMutateResult;
import xyz.rexos.rexperms.model.Group;
import xyz.rexos.rexperms.model.Node;
import xyz.rexos.rexperms.model.TemporaryMergeBehaviour;
import xyz.rexos.rexperms.model.User;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PermissionCalculatorTest {

    private DataEpoch epoch;
    private GroupManager groups;
    private PermissionCalculator calc;
    private User user;

    private static final ContextSet SURVIVAL = ContextSet.of("world", "survival");
    private static final ContextSet PVP = ContextSet.of("world", "pvp");

    @BeforeEach
    void setUp() {
        epoch = new DataEpoch();
        groups = new GroupManager(epoch);
        calc = new PermissionCalculator(groups);
        user = new User(UUID.randomUUID(), "Steve", epoch);

        Group def = groups.getOrCreate("default");
        def.setNode(Node.permission("essentials.spawn", true, ContextSet.EMPTY));
        def.setNode(Node.permission("essentials.fly", false, ContextSet.EMPTY));
        def.setNode(Node.prefix(1, "&7[Player] ", ContextSet.EMPTY));

        Group vip = groups.getOrCreate("vip");
        vip.setNode(Node.weight(10));
        vip.setNode(Node.inheritance("default", ContextSet.EMPTY));
        vip.setNode(Node.permission("essentials.fly", true, ContextSet.EMPTY));
        vip.setNode(Node.prefix(10, "&6[VIP] ", ContextSet.EMPTY));
        vip.setNode(Node.meta("homes", "3", ContextSet.EMPTY));

        Group admin = groups.getOrCreate("admin");
        admin.setNode(Node.weight(100));
        admin.setNode(Node.inheritance("vip", ContextSet.EMPTY));
        admin.setNode(Node.permission("worldedit.*", true, ContextSet.EMPTY));
        admin.setNode(Node.prefix(100, "&c[Admin] ", ContextSet.EMPTY));
        admin.setNode(Node.meta("homes", "50", ContextSet.EMPTY));
    }

    @Test
    void defaultFallbackWhenUserHasNoGroups() {
        CalculatedPermissions p = calc.calculatePermissions(user, ContextSet.EMPTY);
        assertEquals(Tristate.TRUE, p.check("essentials.spawn"));
        assertEquals(Tristate.FALSE, p.check("essentials.fly"));
        assertEquals(Tristate.UNDEFINED, p.check("worldedit.wand"));
    }

    @Test
    void childGroupOverridesParent() {
        user.setNode(Node.inheritance("vip", ContextSet.EMPTY));
        CalculatedPermissions p = calc.calculatePermissions(user, ContextSet.EMPTY);
        assertEquals(Tristate.TRUE, p.check("essentials.fly"), "vip's true must override default's false");
        assertEquals(Tristate.TRUE, p.check("essentials.spawn"), "default'tan miras");
        assertEquals(Tristate.TRUE, p.check("group.vip"));
        assertEquals(Tristate.TRUE, p.check("group.default"));
    }

    @Test
    void userNodeBeatsGroupNode() {
        user.setNode(Node.inheritance("vip", ContextSet.EMPTY));
        user.setNode(Node.permission("essentials.fly", false, ContextSet.EMPTY));
        assertEquals(Tristate.FALSE, calc.calculatePermissions(user, ContextSet.EMPTY).check("essentials.fly"));
    }

    @Test
    void wildcardsResolveMostSpecificFirst() {
        user.setNode(Node.inheritance("admin", ContextSet.EMPTY));
        user.setNode(Node.permission("worldedit.region.*", false, ContextSet.EMPTY));
        CalculatedPermissions p = calc.calculatePermissions(user, ContextSet.EMPTY);
        assertEquals(Tristate.TRUE, p.check("worldedit.wand"));
        assertEquals(Tristate.TRUE, p.check("WorldEdit.Brush.Sphere"), "case-insensitive");
        assertEquals(Tristate.FALSE, p.check("worldedit.region.set"), "the more specific wildcard wins");
        assertEquals("worldedit.region.*", p.lookup("worldedit.region.set").matchedNode());

        user.setNode(Node.permission("*", true, ContextSet.EMPTY));
        p = calc.calculatePermissions(user, ContextSet.EMPTY);
        assertEquals(Tristate.TRUE, p.check("anything.at.all"));
        assertEquals(Tristate.FALSE, p.check("worldedit.region.set"));
    }

    @Test
    void wildcardsCanBeDisabled() {
        user.setNode(Node.inheritance("admin", ContextSet.EMPTY));
        calc.setApplyWildcards(false);
        assertEquals(Tristate.UNDEFINED, calc.calculatePermissions(user, ContextSet.EMPTY).check("worldedit.wand"));
    }

    @Test
    void contextualNodesOnlyApplyInMatchingContext() {
        user.setNode(Node.inheritance("vip", ContextSet.EMPTY));
        user.setNode(Node.permission("essentials.fly", false, PVP));
        assertEquals(Tristate.TRUE, calc.calculatePermissions(user, SURVIVAL).check("essentials.fly"));
        assertEquals(Tristate.FALSE, calc.calculatePermissions(user, PVP).check("essentials.fly"));
    }

    @Test
    void moreSpecificContextWinsWithinSameHolder() {
        user.setNode(Node.permission("kit.daily", true, ContextSet.EMPTY));
        user.setNode(Node.permission("kit.daily", false, PVP));
        assertEquals(Tristate.FALSE, calc.calculatePermissions(user, PVP).check("kit.daily"));
        assertEquals(Tristate.TRUE, calc.calculatePermissions(user, SURVIVAL).check("kit.daily"));
    }

    @Test
    void contextualParent() {
        user.setNode(Node.inheritance("admin", ContextSet.of("server", "creative")));
        assertEquals(Tristate.UNDEFINED,
                calc.calculatePermissions(user, ContextSet.of("server", "lobby")).check("worldedit.wand"));
        assertEquals(Tristate.TRUE,
                calc.calculatePermissions(user, ContextSet.of("server", "creative")).check("worldedit.wand"));
    }

    @Test
    void siblingGroupsOrderedByWeight() {
        Group builder = groups.getOrCreate("builder");
        builder.setNode(Node.weight(5));
        builder.setNode(Node.permission("plots.claim", false, ContextSet.EMPTY));
        Group donor = groups.getOrCreate("donor");
        donor.setNode(Node.weight(50));
        donor.setNode(Node.permission("plots.claim", true, ContextSet.EMPTY));

        // The heavier group wins regardless of insertion order
        user.setNode(Node.inheritance("builder", ContextSet.EMPTY));
        user.setNode(Node.inheritance("donor", ContextSet.EMPTY));
        assertEquals(Tristate.TRUE, calc.calculatePermissions(user, ContextSet.EMPTY).check("plots.claim"));
        List<Group> order = calc.resolveInheritance(user, ContextSet.EMPTY);
        assertEquals("donor", order.get(0).getName());
        assertEquals("builder", order.get(1).getName());
    }

    @Test
    void cyclicInheritanceDoesNotLoop() {
        groups.get("default").setNode(Node.inheritance("admin", ContextSet.EMPTY));
        user.setNode(Node.inheritance("admin", ContextSet.EMPTY));
        List<Group> order = calc.resolveInheritance(user, ContextSet.EMPTY);
        assertEquals(3, order.size());
    }

    @Test
    void expiredNodesAreIgnored() {
        long past = System.currentTimeMillis() / 1000L - 10;
        long future = System.currentTimeMillis() / 1000L + 3600;
        user.setNode(Node.of("group.admin", true, past, ContextSet.EMPTY));
        user.setNode(Node.of("shop.discount", true, future, ContextSet.EMPTY));
        CalculatedPermissions p = calc.calculatePermissions(user, ContextSet.EMPTY);
        assertEquals(Tristate.UNDEFINED, p.check("worldedit.wand"));
        assertEquals(Tristate.TRUE, p.check("shop.discount"));
        assertEquals(1, user.removeExpired(System.currentTimeMillis() / 1000L).size());
    }

    @Test
    void childPermissionsExpand() {
        final Map<String, Boolean> kids = new HashMap<String, Boolean>();
        kids.put("essentials.kits.starter", true);
        kids.put("essentials.kits.cheat", false);
        calc.setChildProvider(perm -> perm.equals("essentials.kits.*") ? kids : Collections.<String, Boolean>emptyMap());
        user.setNode(Node.permission("essentials.kits.*", true, ContextSet.EMPTY));
        CalculatedPermissions p = calc.calculatePermissions(user, ContextSet.EMPTY);
        assertEquals(Tristate.TRUE, p.check("essentials.kits.starter"));
        // Bukkit rule: a false child inverts the parent's value (explicit children beat the wildcard)
        assertEquals(Tristate.FALSE, p.check("essentials.kits.cheat"));
    }

    @Test
    void metaResolution() {
        user.setNode(Node.inheritance("admin", ContextSet.EMPTY));
        MetaData m = calc.calculateMeta(user, ContextSet.EMPTY);
        assertEquals("&c[Admin] ", m.prefix(), "highest priority");
        assertEquals("50", m.metaValue("homes"), "meta from the closest group");
        assertEquals(3, m.prefixes().size());
        assertEquals(100, m.weight());
        assertEquals("admin", m.primaryGroup());

        user.setNode(Node.prefix(500, "&d[Custom] ", ContextSet.EMPTY));
        assertEquals("&d[Custom] ", calc.calculateMeta(user, ContextSet.EMPTY).prefix());
    }

    @Test
    void primaryGroupFallsBackToHeaviestParent() {
        user.setNode(Node.inheritance("vip", ContextSet.EMPTY));
        user.setNode(Node.inheritance("admin", ContextSet.EMPTY));
        user.setPrimaryGroup("vip");
        // Default: the heaviest group
        assertEquals("admin", calc.calculateMeta(user, ContextSet.EMPTY).primaryGroup());
        // "stored" mode: the manually chosen group
        calc.setStoredPrimaryGroup(true);
        assertEquals("vip", calc.calculateMeta(user, ContextSet.EMPTY).primaryGroup());
        user.unsetNode(Node.inheritance("vip", ContextSet.EMPTY));
        assertEquals("admin", calc.calculateMeta(user, ContextSet.EMPTY).primaryGroup());
    }

    @Test
    void cacheInvalidatesOnAnyChange() {
        user.setNode(Node.inheritance("vip", ContextSet.EMPTY));
        CachedUserData cache = user.getCachedData();
        assertEquals(Tristate.UNDEFINED, cache.permissions(ContextSet.EMPTY, calc).check("vip.kit"));
        // A change to a group must invalidate the user's cache too
        groups.get("vip").setNode(Node.permission("vip.kit", true, ContextSet.EMPTY));
        assertEquals(Tristate.TRUE, cache.permissions(ContextSet.EMPTY, calc).check("vip.kit"));
    }

    @Test
    void temporaryMergeBehaviours() {
        long now = System.currentTimeMillis() / 1000L;
        Node day = Node.of("group.vip", true, now + 86_400, ContextSet.EMPTY);
        assertEquals(DataMutateResult.SUCCESS, user.setNode(day));
        assertEquals(DataMutateResult.TEMP_EXISTS, user.setNode(day, TemporaryMergeBehaviour.FAIL));

        assertEquals(DataMutateResult.SUCCESS, user.setNode(day, TemporaryMergeBehaviour.ACCUMULATE));
        long expiry = user.findNode(n -> n.key().equals("group.vip")).expiry();
        assertTrue(expiry >= now + 2 * 86_400 - 2, "durations must stack");

        // "replace" with a shorter duration changes nothing
        assertEquals(DataMutateResult.ALREADY_HAS, user.setNode(Node.of("group.vip", true, now + 60, ContextSet.EMPTY),
                TemporaryMergeBehaviour.REPLACE));
        // Permanent and temporary versions of a node use separate slots
        assertEquals(DataMutateResult.SUCCESS, user.setNode(Node.inheritance("vip", ContextSet.EMPTY)));
        assertEquals(2, user.getParentNodes().size());
    }
}
