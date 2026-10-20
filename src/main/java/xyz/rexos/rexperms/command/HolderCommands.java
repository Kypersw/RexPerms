package xyz.rexos.rexperms.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import xyz.rexos.rexperms.RexPermsPlugin;
import xyz.rexos.rexperms.calc.CalculatedPermissions;
import xyz.rexos.rexperms.calc.MetaData;
import xyz.rexos.rexperms.calc.Tristate;
import xyz.rexos.rexperms.config.Messages;
import xyz.rexos.rexperms.inject.PermissibleInjector;
import xyz.rexos.rexperms.inject.RexPermissible;
import xyz.rexos.rexperms.model.ContextSet;
import xyz.rexos.rexperms.model.DataMutateResult;
import xyz.rexos.rexperms.model.Group;
import xyz.rexos.rexperms.model.HolderType;
import xyz.rexos.rexperms.model.Node;
import xyz.rexos.rexperms.model.NodeType;
import xyz.rexos.rexperms.model.PermissionHolder;
import xyz.rexos.rexperms.model.TemporaryMergeBehaviour;
import xyz.rexos.rexperms.model.User;
import xyz.rexos.rexperms.util.Durations;
import xyz.rexos.rexperms.util.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedMap;

/**
 * Sub-commands shared by users and groups: permission, parent, meta.
 *
 * <pre>
 * &lt;holder&gt; permission info|set|unset|settemp|unsettemp|check|clear
 * &lt;holder&gt; parent    info|set|add|remove|addtemp|removetemp|clear|switchprimarygroup
 * &lt;holder&gt; meta      info|set|unset|settemp|unsettemp|setprefix|setsuffix|removeprefix|removesuffix|
 *                   addtempprefix|addtempsuffix|clear
 * </pre>
 */
final class HolderCommands extends CommandSupport {

    static final List<String> PERMISSION_SUBS = Collections.unmodifiableList(Arrays.asList(
            "info", "set", "unset", "settemp", "unsettemp", "check", "clear"));
    static final List<String> PARENT_SUBS = Collections.unmodifiableList(Arrays.asList(
            "info", "set", "add", "remove", "addtemp", "removetemp", "clear", "switchprimarygroup"));
    static final List<String> META_SUBS = Collections.unmodifiableList(Arrays.asList(
            "info", "set", "unset", "settemp", "unsettemp", "setprefix", "setsuffix", "removeprefix",
            "removesuffix", "addtempprefix", "addtempsuffix", "clear"));

    private static final Comparator<Node> BY_KEY_THEN_CONTEXT = new Comparator<Node>() {
        @Override
        public int compare(Node a, Node b) {
            int c = a.key().compareTo(b.key());
            return c != 0 ? c : a.contexts().toString().compareTo(b.contexts().toString());
        }
    };

    private static final Comparator<Node> BY_PRIORITY_DESC = new Comparator<Node>() {
        @Override
        public int compare(Node a, Node b) {
            return Integer.compare(b.chatPriority(), a.chatPriority());
        }
    };

    HolderCommands(RexPermsPlugin plugin) {
        super(plugin);
    }

    /**
     * @param base prefix for suggested commands, e.g. "/rp user Steve"
     * @param a    arguments starting with "permission ..." / "parent ..." / "meta ..."
     * @return true if handled
     */
    boolean handle(CommandSender sender, PermissionHolder holder, String base, Args a) {
        if (!a.has(0)) return false;
        String type = holder.getType() == HolderType.USER ? "user" : "group";
        String area = a.lower(0);
        if (area.equals("permission") || area.equals("perm") || area.equals("permissions")) {
            permission(sender, holder, type, base, a.from(1));
        } else if (area.equals("parent") || area.equals("parents")) {
            parent(sender, holder, type, base, a.from(1));
        } else if (area.equals("meta") || area.equals("chat")) {
            meta(sender, holder, type, base, a.from(1));
        } else {
            return false;
        }
        return true;
    }

    // ================================================================== permission

    private void permission(CommandSender s, PermissionHolder h, String type, String base, Args a) {
        String sub = a.has(0) ? a.lower(0) : "info";
        if (!allowed(s, type + ".permission." + sub)) return;
        Args rest = a.from(1);
        ContextSet ctx = rest.extractContext();

        if (sub.equals("info")) {
            permissionInfo(s, h, base, parsePage(rest, 0));
        } else if (sub.equals("set")) {
            if (!rest.has(0)) {
                usage(s, base + " permission set <node> [true|false] [context...]");
                return;
            }
            boolean value = true;
            if (rest.has(1)) {
                Boolean b = Args.parseBoolean(rest.get(1));
                if (b == null) {
                    msg().send(s, "general.invalid-boolean", Messages.text("input", rest.get(1)));
                    return;
                }
                value = b;
            }
            if (!validPermissionKey(s, rest.get(0))) return;
            Node node = Node.permission(rest.get(0), value, ctx);
            if (h.setNode(node) == DataMutateResult.ALREADY_HAS) {
                msg().send(s, "permission.already-has", holderName(h), Messages.text("node", node.key()),
                        Messages.comp("value", value(value)), ctx(ctx));
                return;
            }
            msg().send(s, "permission.set", holderName(h), Messages.text("node", node.key()),
                    Messages.comp("value", value(value)), ctx(ctx));
            plugin.getService().commit(h, s, "permission set " + node.key() + " " + value + Text.contextSuffix(ctx));
        } else if (sub.equals("unset")) {
            if (!rest.has(0)) {
                usage(s, base + " permission unset <node> [context...]");
                return;
            }
            Node probe = Node.permission(rest.get(0), true, ctx);
            if (h.unsetNode(probe) == DataMutateResult.LACKS) {
                msg().send(s, "permission.lacks", holderName(h), Messages.text("node", probe.key()), ctx(ctx));
                return;
            }
            msg().send(s, "permission.unset", holderName(h), Messages.text("node", probe.key()), ctx(ctx));
            plugin.getService().commit(h, s, "permission unset " + probe.key() + Text.contextSuffix(ctx));
        } else if (sub.equals("settemp")) {
            if (!rest.has(2)) {
                usage(s, base + " permission settemp <node> <true|false> <duration> [accumulate|replace|deny] [context...]");
                return;
            }
            Boolean value = Args.parseBoolean(rest.get(1));
            if (value == null) {
                msg().send(s, "general.invalid-boolean", Messages.text("input", rest.get(1)));
                return;
            }
            long expiry = Durations.parseExpiry(rest.get(2), nowSeconds());
            if (expiry < 0) {
                msg().send(s, "general.invalid-duration", Messages.text("input", rest.get(2)));
                return;
            }
            if (!validPermissionKey(s, rest.get(0))) return;
            final Node node = Node.of(rest.get(0), value, expiry, ctx);
            DataMutateResult r = h.setNode(node, mergeArg(rest, 3));
            if (r == DataMutateResult.TEMP_EXISTS) {
                msg().send(s, "permission.temp-exists", holderName(h), Messages.text("node", node.key()), ctx(ctx));
                return;
            }
            if (r == DataMutateResult.ALREADY_HAS) {
                msg().send(s, "permission.already-has", holderName(h), Messages.text("node", node.key()),
                        Messages.comp("value", value(value)), ctx(ctx));
                return;
            }
            String duration = remaining(h, node);
            msg().send(s, "permission.settemp", holderName(h), Messages.text("node", node.key()),
                    Messages.comp("value", value(value)), Messages.text("duration", duration), ctx(ctx));
            plugin.getService().commit(h, s, "permission settemp " + node.key() + " " + value + " " + duration
                    + Text.contextSuffix(ctx));
        } else if (sub.equals("unsettemp")) {
            if (!rest.has(0)) {
                usage(s, base + " permission unsettemp <node> [context...]");
                return;
            }
            Node probe = Node.of(rest.get(0), true, Long.MAX_VALUE / 2, ctx);
            if (h.unsetNode(probe) == DataMutateResult.LACKS) {
                msg().send(s, "permission.lacks-temp", holderName(h), Messages.text("node", probe.key()), ctx(ctx));
                return;
            }
            msg().send(s, "permission.unsettemp", holderName(h), Messages.text("node", probe.key()), ctx(ctx));
            plugin.getService().commit(h, s, "permission unsettemp " + probe.key() + Text.contextSuffix(ctx));
        } else if (sub.equals("check")) {
            if (!rest.has(0)) {
                usage(s, base + " permission check <node> [context...]");
                return;
            }
            permissionCheck(s, h, rest.get(0), ctx, !ctx.isEmpty());
        } else if (sub.equals("clear")) {
            final ContextSet c = ctx;
            int removed = h.removeIf(n -> n.type() == NodeType.PERMISSION && (c.isEmpty() || n.contexts().equals(c)));
            msg().send(s, "permission.cleared", holderName(h), Messages.text("count", removed), ctx(ctx));
            if (removed > 0) plugin.getService().commit(h, s, "permission clear" + Text.contextSuffix(ctx));
        } else {
            usage(s, base + " permission <" + Text.join("|", PERMISSION_SUBS) + ">");
        }
    }

    private String remaining(PermissionHolder h, final Node node) {
        Node stored = h.findNode(n -> n.sameSlot(node));
        return Durations.format((stored == null ? node : stored).secondsLeft(nowSeconds()));
    }

    private boolean validPermissionKey(CommandSender s, String key) {
        if (key.trim().isEmpty() || key.contains(" ") || key.length() > 400) {
            msg().send(s, "permission.invalid-node", Messages.text("node", key));
            return false;
        }
        if (NodeType.of(key) != NodeType.PERMISSION) {
            // group.x / prefix.x keys are accepted directly (like LuckPerms) but we point to the better command
            msg().send(s, "permission.special-node-hint", Messages.text("node", key));
        }
        return true;
    }

    private void permissionInfo(CommandSender s, PermissionHolder h, final String base, int page) {
        List<Node> nodes = new ArrayList<Node>(h.getNodes(NodeType.PERMISSION));
        if (nodes.isEmpty()) {
            msg().send(s, "permission.info-empty", holderName(h));
            return;
        }
        Collections.sort(nodes, BY_KEY_THEN_CONTEXT);
        paginate(s, nodes, page, "permission.info-header", holderName(h), n -> {
            String unset = base + " permission " + (n.isTemporary() ? "unsettemp " : "unset ") + n.key()
                    + Text.contextArgs(n.contexts());
            Component line = msg().component("permission.info-entry",
                    Messages.text("node", n.key()),
                    Messages.comp("value", value(n.value())),
                    ctx(n.contexts()),
                    Messages.text("expiry", expiryText(n)));
            return clickable(line, unset, "Click to remove");
        });
    }

    /**
     * Shows the holder's own node(s) plus the calculated (inherited) result and
     * where it came from. For an online user without explicit contexts the
     * player's current contexts are used.
     */
    void permissionCheck(CommandSender s, PermissionHolder h, String perm, ContextSet ctx, boolean explicitContext) {
        String key = perm.toLowerCase(Locale.ROOT);
        ContextSet effective = ctx;
        if (!explicitContext) {
            effective = plugin.getContextManager().staticContext();
            if (h instanceof User) {
                Player p = Bukkit.getPlayer(((User) h).getUniqueId());
                RexPermissible rex = p == null ? null : PermissibleInjector.getInjected(p);
                if (rex != null) effective = rex.getContext();
            }
        }

        msg().send(s, "permission.check-header", holderName(h), Messages.text("node", key),
                Messages.text("context", effective.toString()));

        List<Node> own = new ArrayList<Node>();
        for (Node n : h.getNodes()) if (n.key().equals(key)) own.add(n);
        if (own.isEmpty()) {
            msg().sendPlain(s, "permission.check-own-none");
        } else {
            for (Node n : own) {
                msg().sendPlain(s, "permission.check-own-entry", Messages.comp("value", value(n.value())),
                        ctx(n.contexts()), Messages.text("expiry", expiryText(n)));
            }
        }

        CalculatedPermissions calc = h instanceof User
                ? ((User) h).getCachedData().permissions(effective, plugin.getCalculator())
                : plugin.getCalculator().calculatePermissions(h, effective);
        CalculatedPermissions.Result r = calc.lookup(key);
        if (r.value() == Tristate.UNDEFINED) {
            msg().sendPlain(s, "permission.check-result-undefined");
        } else {
            msg().sendPlain(s, "permission.check-result",
                    Messages.comp("value", value(r.value().asBoolean())),
                    Messages.text("source", r.source().holder()),
                    Messages.text("matched", r.matchedNode()));
        }
    }

    // ================================================================== parent

    private void parent(CommandSender s, PermissionHolder h, String type, String base, Args a) {
        String sub = a.has(0) ? a.lower(0) : "info";
        if (!allowed(s, type + ".parent." + sub)) return;
        Args rest = a.from(1);
        final ContextSet ctx = rest.extractContext();

        if (sub.equals("info")) {
            parentInfo(s, h, base);
        } else if (sub.equals("set") || sub.equals("add") || sub.equals("addtemp")) {
            boolean temp = sub.equals("addtemp");
            if (!rest.has(temp ? 1 : 0)) {
                usage(s, base + " parent " + sub + " <group>" + (temp ? " <duration> [accumulate|replace|deny]" : "")
                        + " [context...]");
                return;
            }
            Group group = plugin.getGroupManager().get(rest.get(0));
            if (group == null) {
                msg().send(s, "group.not-found", Messages.text("group", rest.get(0)));
                return;
            }
            if (h instanceof Group) {
                Group self = (Group) h;
                if (self.getName().equals(group.getName())) {
                    msg().send(s, "parent.self");
                    return;
                }
                if (createsCycle(self, group)) {
                    msg().send(s, "parent.cycle", Messages.text("group", self.getName()),
                            Messages.text("parent", group.getName()));
                    return;
                }
            }
            long expiry = 0L;
            if (temp) {
                expiry = Durations.parseExpiry(rest.get(1), nowSeconds());
                if (expiry < 0) {
                    msg().send(s, "general.invalid-duration", Messages.text("input", rest.get(1)));
                    return;
                }
            }
            final Node node = Node.of("group." + group.getName(), true, expiry, ctx);

            if (sub.equals("set")) {
                // Remove every permanent parent in this context, keep only this group
                h.removeIf(n -> n.type() == NodeType.INHERITANCE && !n.isTemporary() && n.contexts().equals(ctx));
                h.setNode(node);
                if (h instanceof User && ctx.isEmpty()) ((User) h).setPrimaryGroup(group.getName());
                msg().send(s, "parent.set", holderName(h), Messages.text("group", group.getName()), ctx(ctx));
                plugin.getService().commit(h, s, "parent set " + group.getName() + Text.contextSuffix(ctx));
                return;
            }

            DataMutateResult r = h.setNode(node, temp ? mergeArg(rest, 2) : TemporaryMergeBehaviour.REPLACE);
            if (r == DataMutateResult.ALREADY_HAS) {
                msg().send(s, "parent.already-has", holderName(h), Messages.text("group", group.getName()), ctx(ctx));
                return;
            }
            if (r == DataMutateResult.TEMP_EXISTS) {
                msg().send(s, "parent.temp-exists", holderName(h), Messages.text("group", group.getName()), ctx(ctx));
                return;
            }
            if (temp) {
                String duration = remaining(h, node);
                msg().send(s, "parent.addtemp", holderName(h), Messages.text("group", group.getName()),
                        Messages.text("duration", duration), ctx(ctx));
                plugin.getService().commit(h, s, "parent addtemp " + group.getName() + " " + duration
                        + Text.contextSuffix(ctx));
            } else {
                msg().send(s, "parent.add", holderName(h), Messages.text("group", group.getName()), ctx(ctx));
                plugin.getService().commit(h, s, "parent add " + group.getName() + Text.contextSuffix(ctx));
            }
        } else if (sub.equals("remove") || sub.equals("removetemp")) {
            boolean temp = sub.equals("removetemp");
            if (!rest.has(0)) {
                usage(s, base + " parent " + sub + " <group> [context...]");
                return;
            }
            String groupName = rest.lower(0);
            Node probe = Node.of("group." + groupName, true, temp ? Long.MAX_VALUE / 2 : 0L, ctx);
            if (h.unsetNode(probe) == DataMutateResult.LACKS) {
                msg().send(s, "parent.lacks", holderName(h), Messages.text("group", groupName), ctx(ctx));
                return;
            }
            if (h instanceof User) afterParentRemoval((User) h, groupName);
            msg().send(s, "parent.remove", holderName(h), Messages.text("group", groupName), ctx(ctx));
            plugin.getService().commit(h, s, "parent " + sub + " " + groupName + Text.contextSuffix(ctx));
        } else if (sub.equals("clear")) {
            int removed = h.removeIf(n -> n.type() == NodeType.INHERITANCE && (ctx.isEmpty() || n.contexts().equals(ctx)));
            if (h instanceof User) afterParentRemoval((User) h, ((User) h).getPrimaryGroup());
            msg().send(s, "parent.cleared", holderName(h), Messages.text("count", removed), ctx(ctx));
            if (removed > 0) plugin.getService().commit(h, s, "parent clear" + Text.contextSuffix(ctx));
        } else if (sub.equals("switchprimarygroup") || sub.equals("setprimarygroup")) {
            if (!(h instanceof User)) {
                msg().send(s, "parent.primary-users-only");
                return;
            }
            User u = (User) h;
            if (!rest.has(0)) {
                usage(s, base + " parent switchprimarygroup <group>");
                return;
            }
            String groupName = rest.lower(0);
            boolean member = false;
            for (Node n : u.getParentNodes()) {
                if (n.value() && n.groupName().equals(groupName)) member = true;
            }
            if (!member) {
                msg().send(s, "parent.primary-not-member", holderName(h), Messages.text("group", groupName));
                return;
            }
            u.setPrimaryGroup(groupName);
            msg().send(s, "parent.primary-switched", holderName(h), Messages.text("group", groupName));
            plugin.getService().commit(h, s, "parent switchprimarygroup " + groupName);
        } else {
            usage(s, base + " parent <" + Text.join("|", PARENT_SUBS) + ">");
        }
    }

    /** If the removed group was primary pick a new one; give default if nothing is left. */
    private void afterParentRemoval(User u, String removed) {
        plugin.getService().ensureDefaultGroup(u);
        if (removed != null && removed.equals(u.getPrimaryGroup())) {
            u.setPrimaryGroup(plugin.getCalculator().primaryGroup(u, ContextSet.EMPTY, nowSeconds()));
        }
    }

    /** A cycle appears if "parent" (indirectly) already inherits "self". */
    private boolean createsCycle(Group self, Group parent) {
        List<Group> stack = new ArrayList<Group>();
        stack.add(parent);
        Set<String> seen = new HashSet<String>();
        while (!stack.isEmpty()) {
            Group g = stack.remove(stack.size() - 1);
            if (!seen.add(g.getName())) continue;
            for (Node n : g.getParentNodes()) {
                if (n.groupName().equals(self.getName())) return true;
                Group next = plugin.getGroupManager().get(n.groupName());
                if (next != null) stack.add(next);
            }
        }
        return false;
    }

    private void parentInfo(CommandSender s, PermissionHolder h, String base) {
        List<Node> nodes = new ArrayList<Node>(h.getParentNodes());
        if (nodes.isEmpty()) {
            msg().send(s, "parent.info-empty", holderName(h));
            return;
        }
        Collections.sort(nodes, new Comparator<Node>() {
            @Override
            public int compare(Node a, Node b) {
                int c = Integer.compare(weightOf(b), weightOf(a));
                return c != 0 ? c : a.key().compareTo(b.key());
            }
        });
        msg().send(s, "parent.info-header", holderName(h), Messages.text("count", nodes.size()));
        String primary = h instanceof User
                ? plugin.getCalculator().primaryGroup((User) h, plugin.getContextManager().staticContext(), nowSeconds())
                : null;
        for (Node n : nodes) {
            Group g = plugin.getGroupManager().get(n.groupName());
            String cmd = base + " parent " + (n.isTemporary() ? "removetemp " : "remove ") + n.groupName()
                    + Text.contextArgs(n.contexts());
            boolean isPrimary = n.groupName().equals(primary) && n.contexts().isEmpty() && !n.isTemporary();
            Component line = msg().component("parent.info-entry",
                    Messages.text("group", n.groupName()),
                    Messages.text("weight", g == null ? "?" : String.valueOf(g.getWeight())),
                    Messages.text("primary", isPrimary ? " ★" : ""),
                    Messages.text("missing", g == null ? " (group missing!)" : ""),
                    ctx(n.contexts()),
                    Messages.text("expiry", expiryText(n)));
            msg().send(s, clickable(line, cmd, "Click to remove"));
        }
    }

    private int weightOf(Node n) {
        Group g = plugin.getGroupManager().get(n.groupName());
        return g == null ? 0 : g.getWeight();
    }

    // ================================================================== meta

    private void meta(CommandSender s, PermissionHolder h, String type, String base, Args a) {
        String sub = a.has(0) ? a.lower(0) : "info";
        if (!allowed(s, type + ".meta." + sub)) return;
        Args rest = a.from(1);
        final ContextSet ctx = rest.extractContext();

        if (sub.equals("info")) {
            metaInfo(s, h, base);
        } else if (sub.equals("set") || sub.equals("settemp")) {
            final boolean temp = sub.equals("settemp");
            if (!rest.has(temp ? 2 : 1)) {
                usage(s, base + " meta " + sub + " <key> <value>" + (temp ? " <duration>" : "") + " [context...]");
                return;
            }
            final String key = rest.lower(0);
            if (key.contains(".") || key.trim().isEmpty()) {
                msg().send(s, "meta.invalid-key", Messages.text("key", key));
                return;
            }
            String val = rest.get(1);
            long expiry = 0L;
            if (temp) {
                expiry = Durations.parseExpiry(rest.get(2), nowSeconds());
                if (expiry < 0) {
                    msg().send(s, "general.invalid-duration", Messages.text("input", rest.get(2)));
                    return;
                }
            }
            // Drop the previous value of this key in the same context
            h.removeIf(n -> n.type() == NodeType.META && n.metaKey().equals(key)
                    && n.contexts().equals(ctx) && n.isTemporary() == temp);
            h.setNode(Node.of("meta." + key + "." + val, true, expiry, ctx));
            msg().send(s, "meta.set", holderName(h), Messages.text("key", key), Messages.text("value", val), ctx(ctx));
            plugin.getService().commit(h, s, "meta " + sub + " " + key + " " + val + Text.contextSuffix(ctx));
        } else if (sub.equals("unset") || sub.equals("unsettemp")) {
            final boolean temp = sub.equals("unsettemp");
            if (!rest.has(0)) {
                usage(s, base + " meta " + sub + " <key> [context...]");
                return;
            }
            final String key = rest.lower(0);
            int removed = h.removeIf(n -> n.type() == NodeType.META && n.metaKey().equals(key)
                    && n.contexts().equals(ctx) && n.isTemporary() == temp);
            if (removed == 0) {
                msg().send(s, "meta.lacks", holderName(h), Messages.text("key", key), ctx(ctx));
                return;
            }
            msg().send(s, "meta.unset", holderName(h), Messages.text("key", key), ctx(ctx));
            plugin.getService().commit(h, s, "meta " + sub + " " + key + Text.contextSuffix(ctx));
        } else if (sub.equals("setprefix") || sub.equals("setsuffix")
                || sub.equals("addtempprefix") || sub.equals("addtempsuffix")) {
            chatSet(s, h, base, sub, rest, ctx);
        } else if (sub.equals("removeprefix") || sub.equals("removesuffix")) {
            boolean prefix = sub.equals("removeprefix");
            if (!rest.has(0)) {
                usage(s, base + " meta " + sub + " <priority> [text] [context...]");
                return;
            }
            final Integer priority = Args.parseInt(rest.get(0));
            if (priority == null) {
                msg().send(s, "general.invalid-number", Messages.text("input", rest.get(0)));
                return;
            }
            final String text = rest.has(1) ? rest.get(1) : null;
            final NodeType t = prefix ? NodeType.PREFIX : NodeType.SUFFIX;
            int removed = h.removeIf(n -> n.type() == t && n.chatPriority() == priority
                    && (text == null || n.chatValue().equals(text))
                    && n.contexts().equals(ctx));
            if (removed == 0) {
                msg().send(s, prefix ? "meta.prefix-lacks" : "meta.suffix-lacks", holderName(h),
                        Messages.text("priority", priority), ctx(ctx));
                return;
            }
            msg().send(s, prefix ? "meta.prefix-removed" : "meta.suffix-removed", holderName(h),
                    Messages.text("priority", priority), Messages.text("count", removed), ctx(ctx));
            plugin.getService().commit(h, s, "meta " + sub + " " + priority
                    + (text != null ? " \"" + text + "\"" : "") + Text.contextSuffix(ctx));
        } else if (sub.equals("clear")) {
            int removed = h.removeIf(n -> (n.type() == NodeType.PREFIX || n.type() == NodeType.SUFFIX
                    || n.type() == NodeType.META) && (ctx.isEmpty() || n.contexts().equals(ctx)));
            msg().send(s, "meta.cleared", holderName(h), Messages.text("count", removed), ctx(ctx));
            if (removed > 0) plugin.getService().commit(h, s, "meta clear" + Text.contextSuffix(ctx));
        } else {
            usage(s, base + " meta <" + Text.join("|", META_SUBS) + ">");
        }
    }

    private void chatSet(CommandSender s, PermissionHolder h, String base, String sub, Args rest, final ContextSet ctx) {
        boolean prefix = sub.endsWith("prefix");
        boolean temp = sub.startsWith("addtemp");
        final NodeType t = prefix ? NodeType.PREFIX : NodeType.SUFFIX;

        // Forms: setprefix <priority> <text>  |  setprefix <text>
        Integer priority = null;
        int textIndex = 0;
        if (rest.has(1) && Args.parseInt(rest.get(0)) != null) {
            priority = Args.parseInt(rest.get(0));
            textIndex = 1;
        }
        int needed = textIndex + (temp ? 1 : 0);
        if (!rest.has(needed)) {
            usage(s, base + " meta " + sub + " [priority] <text>" + (temp ? " <duration>" : "") + " [context...]");
            return;
        }
        String text = rest.get(textIndex);
        if (priority == null) priority = defaultChatPriority(h, t);

        long expiry = 0L;
        if (temp) {
            expiry = Durations.parseExpiry(rest.get(textIndex + 1), nowSeconds());
            if (expiry < 0) {
                msg().send(s, "general.invalid-duration", Messages.text("input", rest.get(textIndex + 1)));
                return;
            }
        }
        final int prio = priority;
        if (!temp) {
            // One permanent prefix per priority, so "setprefix" really "sets"
            h.removeIf(n -> n.type() == t && !n.isTemporary() && n.chatPriority() == prio && n.contexts().equals(ctx));
        }
        Node node = Node.of((prefix ? "prefix." : "suffix.") + prio + "." + text, true, expiry, ctx);
        if (h.setNode(node, TemporaryMergeBehaviour.REPLACE) == DataMutateResult.ALREADY_HAS) {
            msg().send(s, "meta.chat-already", holderName(h), Messages.text("priority", prio), ctx(ctx));
            return;
        }
        String duration = temp ? " (" + Durations.format(expiry - nowSeconds()) + ")" : "";
        msg().send(s, prefix ? "meta.prefix-set" : "meta.suffix-set", holderName(h),
                Messages.comp("text", Text.colorize(text)),
                Messages.text("raw", text),
                Messages.text("priority", prio),
                Messages.text("duration", duration),
                ctx(ctx));
        plugin.getService().commit(h, s, "meta " + sub + " " + prio + " \"" + text + "\"" + duration
                + Text.contextSuffix(ctx));
    }

    /**
     * Default priority: a group's weight, or for a user one above the highest
     * inherited priority (so the personal prefix wins).
     */
    private int defaultChatPriority(PermissionHolder h, NodeType t) {
        if (h instanceof Group) return Math.max(1, ((Group) h).getWeight());
        MetaData meta = plugin.getCalculator().calculateMeta(h, plugin.getContextManager().staticContext());
        SortedMap<Integer, String> map = t == NodeType.PREFIX ? meta.prefixes() : meta.suffixes();
        return map.isEmpty() ? 100 : map.firstKey() + 1;
    }

    private void metaInfo(CommandSender s, PermissionHolder h, String base) {
        msg().send(s, "meta.info-header", holderName(h));
        List<Node> prefixes = new ArrayList<Node>(h.getNodes(NodeType.PREFIX));
        List<Node> suffixes = new ArrayList<Node>(h.getNodes(NodeType.SUFFIX));
        List<Node> metas = new ArrayList<Node>(h.getNodes(NodeType.META));
        Collections.sort(prefixes, BY_PRIORITY_DESC);
        Collections.sort(suffixes, BY_PRIORITY_DESC);
        Collections.sort(metas, new Comparator<Node>() {
            @Override
            public int compare(Node a, Node b) {
                return a.metaKey().compareTo(b.metaKey());
            }
        });

        msg().sendPlain(s, "meta.info-prefixes", Messages.text("count", prefixes.size()));
        for (Node n : prefixes) msg().send(s, chatLine(base, n, "removeprefix"));
        msg().sendPlain(s, "meta.info-suffixes", Messages.text("count", suffixes.size()));
        for (Node n : suffixes) msg().send(s, chatLine(base, n, "removesuffix"));
        msg().sendPlain(s, "meta.info-meta", Messages.text("count", metas.size()));
        for (Node n : metas) {
            Component line = msg().component("meta.info-meta-entry",
                    Messages.text("key", n.metaKey()), Messages.text("value", n.metaValue()),
                    ctx(n.contexts()), Messages.text("expiry", expiryText(n)));
            msg().send(s, clickable(line, base + " meta " + (n.isTemporary() ? "unsettemp " : "unset ") + n.metaKey()
                    + Text.contextArgs(n.contexts()), "Click to remove"));
        }

        // Effective (inherited) result
        MetaData effective = h instanceof User
                ? ((User) h).getCachedData().meta(plugin.getContextManager().staticContext(), plugin.getCalculator())
                : plugin.getCalculator().calculateMeta(h, plugin.getContextManager().staticContext());
        msg().sendPlain(s, "meta.info-effective",
                Messages.comp("prefix", effective.prefix() == null ? Component.text("-", NamedTextColor.DARK_GRAY)
                        : Text.colorize(effective.prefix())),
                Messages.comp("suffix", effective.suffix() == null ? Component.text("-", NamedTextColor.DARK_GRAY)
                        : Text.colorize(effective.suffix())));
    }

    private Component chatLine(String base, Node n, String removeSub) {
        Component line = msg().component("meta.info-chat-entry",
                Messages.text("priority", n.chatPriority()),
                Messages.comp("text", Text.colorize(n.chatValue())),
                Messages.text("raw", n.chatValue()),
                ctx(n.contexts()), Messages.text("expiry", expiryText(n)));
        return clickable(line, base + " meta " + removeSub + " " + n.chatPriority() + " "
                + Text.quoteIfNeeded(n.chatValue()) + Text.contextArgs(n.contexts()), "Click to remove");
    }

    // ================================================================== helpers

    static TagResolver holderName(PermissionHolder h) {
        return Messages.text("holder", h.getFriendlyName());
    }

    private static TemporaryMergeBehaviour mergeArg(Args a, int index) {
        if (!a.has(index)) return TemporaryMergeBehaviour.FAIL;
        TemporaryMergeBehaviour m = TemporaryMergeBehaviour.parse(a.get(index));
        return m == null ? TemporaryMergeBehaviour.FAIL : m;
    }

    /** Registered permissions starting with the given text, for tab completion. */
    static List<String> knownPermissions(String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<String>();
        for (Permission perm : Bukkit.getPluginManager().getPermissions()) {
            String name = perm.getName().toLowerCase(Locale.ROOT);
            if (name.startsWith(p)) {
                out.add(name);
                if (out.size() >= 50) break;
            }
        }
        Collections.sort(out);
        return out;
    }
}
