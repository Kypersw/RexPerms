package xyz.rexos.rexperms.calc;

import xyz.rexos.rexperms.model.ContextSet;
import xyz.rexos.rexperms.model.Group;
import xyz.rexos.rexperms.model.HolderType;
import xyz.rexos.rexperms.model.Node;
import xyz.rexos.rexperms.model.NodeType;
import xyz.rexos.rexperms.model.PermissionHolder;
import xyz.rexos.rexperms.model.User;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The permission calculation engine.
 *
 * <h2>Inheritance order</h2>
 * The holder's own nodes come first, then parent groups are walked
 * depth-first (pre-order). Siblings on the same level are sorted by weight,
 * heaviest first. A permission is fixed where it is first defined, so a node
 * set directly on a player always beats one from a group, and a heavy group
 * beats a light one.
 *
 * <h2>Context priority</h2>
 * Within one holder, a node with more contexts is more specific and wins:
 * {@code fly=false [world=pvp]} overrides a global {@code fly=true} in pvp.
 */
public final class PermissionCalculator {

    private static final class ChildFrame {
        final String permission;
        final boolean value;
        final int depth;

        ChildFrame(String permission, boolean value, int depth) {
            this.permission = permission;
            this.value = value;
            this.depth = depth;
        }
    }

    private static final int MAX_DEPTH = 64;
    private static final int MAX_CHILD_DEPTH = 16;

    private static final Comparator<Node> MOST_SPECIFIC_FIRST = new Comparator<Node>() {
        @Override
        public int compare(Node a, Node b) {
            return Integer.compare(b.contexts().size(), a.contexts().size());
        }
    };

    private static final Comparator<Group> HEAVIEST_FIRST = new Comparator<Group>() {
        @Override
        public int compare(Group a, Group b) {
            return Integer.compare(b.getWeight(), a.getWeight());
        }
    };

    private final GroupLookup groups;
    private volatile ChildPermissionProvider childProvider = ChildPermissionProvider.NONE;
    private volatile boolean applyWildcards = true;
    private volatile boolean applyChildPermissions = true;
    private volatile boolean defaultGroupFallback = true;
    private volatile boolean storedPrimaryGroup = false;

    public PermissionCalculator(GroupLookup groups) {
        this.groups = groups;
    }

    // ------------------------------------------------------------------ settings

    public void setChildProvider(ChildPermissionProvider provider) {
        this.childProvider = provider == null ? ChildPermissionProvider.NONE : provider;
    }

    public void setApplyWildcards(boolean v) {
        this.applyWildcards = v;
    }

    public void setApplyChildPermissions(boolean v) {
        this.applyChildPermissions = v;
    }

    public void setDefaultGroupFallback(boolean v) {
        this.defaultGroupFallback = v;
    }

    public void setStoredPrimaryGroup(boolean v) {
        this.storedPrimaryGroup = v;
    }

    // ------------------------------------------------------------------ inheritance

    /**
     * All groups the holder inherits in this context, in priority order.
     * The holder itself is not included.
     */
    public List<Group> resolveInheritance(PermissionHolder origin, ContextSet ctx) {
        long now = nowSeconds();
        List<Group> out = new ArrayList<Group>();
        Set<String> seen = new HashSet<String>();
        if (origin instanceof Group) seen.add(((Group) origin).getName());
        visit(origin, ctx, now, out, seen, 0);

        if (out.isEmpty() && defaultGroupFallback && origin.getType() == HolderType.USER) {
            Group def = groups.get("default");
            if (def != null && seen.add("default")) {
                out.add(def);
                visit(def, ctx, now, out, seen, 1);
            }
        }
        return out;
    }

    private void visit(PermissionHolder holder, ContextSet ctx, long now,
                       List<Group> out, Set<String> seen, int depth) {
        if (depth > MAX_DEPTH) return;
        for (Group parent : directParents(holder, ctx, now)) {
            if (seen.add(parent.getName())) {
                out.add(parent);
                visit(parent, ctx, now, out, seen, depth + 1);
            }
        }
    }

    /** Direct parents valid in this context, heaviest first. */
    public List<Group> directParents(PermissionHolder holder, ContextSet ctx, long now) {
        List<Group> parents = new ArrayList<Group>();
        for (Node n : holder.getParentNodes()) {
            if (!n.value() || n.hasExpired(now) || !n.contexts().isSatisfiedBy(ctx)) continue;
            Group g = groups.get(n.groupName());
            if (g != null && !parents.contains(g)) parents.add(g);
        }
        // Stable sort: equal weights keep insertion order
        Collections.sort(parents, HEAVIEST_FIRST);
        return parents;
    }

    // ------------------------------------------------------------------ permissions

    public CalculatedPermissions calculatePermissions(PermissionHolder origin, ContextSet ctx) {
        long now = nowSeconds();
        List<Group> inherited = resolveInheritance(origin, ctx);

        LinkedHashMap<String, CalculatedPermissions.Source> map =
                new LinkedHashMap<String, CalculatedPermissions.Source>();
        accumulatePermissions(origin, ctx, now, false, map);
        for (Group g : inherited) {
            accumulatePermissions(g, ctx, now, true, map);
        }

        // "group.<name>" for every inherited group (some plugins check this)
        for (Group g : inherited) {
            String key = "group." + g.getName();
            if (!map.containsKey(key)) {
                map.put(key, new CalculatedPermissions.Source(true, g.getName(), key, true));
            }
        }

        if (applyChildPermissions && childProvider != ChildPermissionProvider.NONE) {
            expandChildren(map);
        }
        return new CalculatedPermissions(map, applyWildcards);
    }

    private static void accumulatePermissions(PermissionHolder holder, ContextSet ctx, long now, boolean inherited,
                                              Map<String, CalculatedPermissions.Source> into) {
        List<Node> applicable = new ArrayList<Node>();
        for (Node n : holder.getNodes(NodeType.PERMISSION)) {
            if (!n.hasExpired(now) && n.contexts().isSatisfiedBy(ctx)) applicable.add(n);
        }
        Collections.sort(applicable, MOST_SPECIFIC_FIRST);
        String holderName = holder.getType() == HolderType.GROUP ? holder.getIdentifier() : holder.getFriendlyName();
        for (Node n : applicable) {
            if (!into.containsKey(n.key())) {
                into.put(n.key(), new CalculatedPermissions.Source(n.value(), holderName, n.key(), inherited));
            }
        }
    }

    private void expandChildren(LinkedHashMap<String, CalculatedPermissions.Source> map) {
        List<Map.Entry<String, CalculatedPermissions.Source>> roots =
                new ArrayList<Map.Entry<String, CalculatedPermissions.Source>>(map.entrySet());
        Map<String, CalculatedPermissions.Source> additions = new LinkedHashMap<String, CalculatedPermissions.Source>();
        for (Map.Entry<String, CalculatedPermissions.Source> root : roots) {
            CalculatedPermissions.Source src = root.getValue();
            Deque<ChildFrame> stack = new ArrayDeque<ChildFrame>();
            stack.push(new ChildFrame(root.getKey(), src.value(), 0));
            Set<String> visited = new HashSet<String>();
            while (!stack.isEmpty()) {
                ChildFrame frame = stack.pop();
                if (frame.depth >= MAX_CHILD_DEPTH || !visited.add(frame.permission)) continue;
                Map<String, Boolean> children = childProvider.children(frame.permission);
                if (children == null || children.isEmpty()) continue;
                for (Map.Entry<String, Boolean> child : children.entrySet()) {
                    String childKey = child.getKey().toLowerCase(Locale.ROOT);
                    // Bukkit rule: a true child follows the parent, a false child inverts it
                    boolean childValue = child.getValue() == frame.value;
                    if (!map.containsKey(childKey) && !additions.containsKey(childKey)) {
                        additions.put(childKey, new CalculatedPermissions.Source(
                                childValue, src.holder(), root.getKey(), src.inherited()));
                    }
                    stack.push(new ChildFrame(childKey, childValue, frame.depth + 1));
                }
            }
        }
        for (Map.Entry<String, CalculatedPermissions.Source> e : additions.entrySet()) {
            if (!map.containsKey(e.getKey())) map.put(e.getKey(), e.getValue());
        }
    }

    // ------------------------------------------------------------------ meta

    public MetaData calculateMeta(PermissionHolder origin, ContextSet ctx) {
        long now = nowSeconds();
        List<Group> inherited = resolveInheritance(origin, ctx);
        List<PermissionHolder> chain = new ArrayList<PermissionHolder>(inherited.size() + 1);
        chain.add(origin);
        chain.addAll(inherited);

        TreeMap<Integer, String> prefixes = new TreeMap<Integer, String>(Collections.<Integer>reverseOrder());
        TreeMap<Integer, String> suffixes = new TreeMap<Integer, String>(Collections.<Integer>reverseOrder());
        Map<String, String> meta = new HashMap<String, String>();

        for (PermissionHolder holder : chain) {
            List<Node> nodes = new ArrayList<Node>(holder.getNodes());
            Collections.sort(nodes, MOST_SPECIFIC_FIRST);
            for (Node n : nodes) {
                if (n.hasExpired(now) || !n.value() || !n.contexts().isSatisfiedBy(ctx)) continue;
                switch (n.type()) {
                    case PREFIX:
                        if (!prefixes.containsKey(n.chatPriority())) prefixes.put(n.chatPriority(), n.chatValue());
                        break;
                    case SUFFIX:
                        if (!suffixes.containsKey(n.chatPriority())) suffixes.put(n.chatPriority(), n.chatValue());
                        break;
                    case META:
                        if (!meta.containsKey(n.metaKey())) meta.put(n.metaKey(), n.metaValue());
                        break;
                    default:
                        break;
                }
            }
        }

        int weight = 0;
        for (Group g : inherited) weight = Math.max(weight, g.getWeight());
        if (origin instanceof Group) weight = Math.max(weight, ((Group) origin).getWeight());

        String primary = origin instanceof User ? primaryGroup((User) origin, ctx, now) : origin.getIdentifier();
        return new MetaData(
                prefixes.isEmpty() ? null : prefixes.firstEntry().getValue(),
                suffixes.isEmpty() ? null : suffixes.firstEntry().getValue(),
                prefixes, suffixes, meta, primary, weight);
    }

    /**
     * Primary group.
     * <ul>
     *   <li>parents-by-weight (default): the heaviest direct parent</li>
     *   <li>stored: the group set by command (while the user is still in it), else the heaviest parent</li>
     * </ul>
     */
    public String primaryGroup(User user, ContextSet ctx, long now) {
        List<Group> direct = directParents(user, ctx, now);
        if (storedPrimaryGroup) {
            String stored = user.getPrimaryGroup();
            for (Group g : direct) {
                if (g.getName().equals(stored)) return stored;
            }
        }
        if (!direct.isEmpty()) return direct.get(0).getName();
        return "default";
    }

    public static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
}
