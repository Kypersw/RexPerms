package xyz.rexos.rexperms.hook;

import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import xyz.rexos.rexperms.RexPermsPlugin;
import xyz.rexos.rexperms.calc.MetaData;
import xyz.rexos.rexperms.calc.Tristate;
import xyz.rexos.rexperms.inject.PermissibleInjector;
import xyz.rexos.rexperms.inject.RexPermissible;
import xyz.rexos.rexperms.model.ContextSet;
import xyz.rexos.rexperms.model.DataMutateResult;
import xyz.rexos.rexperms.model.Group;
import xyz.rexos.rexperms.model.Node;
import xyz.rexos.rexperms.model.NodeType;
import xyz.rexos.rexperms.model.PermissionHolder;
import xyz.rexos.rexperms.model.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Vault bridge. Most economy, shop and chat plugins read permissions and
 * prefixes through Vault, so this matters.
 *
 * <ul>
 *   <li>For queries the world argument is used as a context.</li>
 *   <li>For changes (playerAdd, playerAddGroup...) the world argument is ignored
 *       and nodes are added globally. Shop plugins usually pass the player's
 *       current world, which would otherwise make a bought rank work in that
 *       world only.</li>
 * </ul>
 */
public final class VaultHook {

    private final RexPermsPlugin plugin;
    private final VaultPermission permission;
    private final VaultChat chat;

    public VaultHook(RexPermsPlugin plugin) {
        this.plugin = plugin;
        this.permission = new VaultPermission();
        this.chat = new VaultChat(permission);
    }

    public void register() {
        ServicesManager sm = Bukkit.getServicesManager();
        sm.register(Permission.class, permission, plugin, ServicePriority.Highest);
        sm.register(Chat.class, chat, plugin, ServicePriority.Highest);
    }

    public void unregister() {
        ServicesManager sm = Bukkit.getServicesManager();
        sm.unregister(Permission.class, permission);
        sm.unregister(Chat.class, chat);
    }

    // ================================================================== shared helpers

    private User user(OfflinePlayer player) {
        if (player == null) return null;
        User loaded = plugin.getUserManager().get(player.getUniqueId());
        if (loaded != null) return loaded;
        if (plugin.getStorage() == null) return null;
        try {
            return plugin.getService().loadUser(player.getUniqueId(), player.getName()).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Vault: could not load " + player.getName(), e);
            return null;
        }
    }

    private User user(String name) {
        if (name == null) return null;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            User u = plugin.getUserManager().get(online.getUniqueId());
            if (u != null) return u;
        }
        if (plugin.getStorage() == null) return null;
        try {
            return plugin.getService().lookupUser(name).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Vault: could not find " + name, e);
            return null;
        }
    }

    /** Query context: the given world, otherwise the player's current contexts. */
    private ContextSet queryContext(String world, User user) {
        ContextSet base = plugin.getContextManager().staticContext();
        if (world != null && !world.trim().isEmpty()) return base.with("world", world);
        if (user != null) {
            Player p = Bukkit.getPlayer(user.getUniqueId());
            RexPermissible rex = p == null ? null : PermissibleInjector.getInjected(p);
            if (rex != null) return rex.getContext();
        }
        return base;
    }

    private boolean userHas(User user, String world, String perm) {
        if (user == null || perm == null) return false;
        Player p = Bukkit.getPlayer(user.getUniqueId());
        if (p != null && (world == null || world.equalsIgnoreCase(p.getWorld().getName()))) {
            return p.hasPermission(perm); // includes defaults and attachments
        }
        return user.getCachedData().permissions(queryContext(world, user), plugin.getCalculator())
                .check(perm) == Tristate.TRUE;
    }

    private boolean mutate(PermissionHolder holder, Node node, boolean add, String description) {
        if (holder == null) return false;
        DataMutateResult r = add ? holder.setNode(node) : holder.unsetNode(node);
        if (!r.wasSuccessful()) return false;
        plugin.getService().commit(holder, null, "vault: " + description);
        return true;
    }

    private MetaData meta(User user, String world) {
        return user.getCachedData().meta(queryContext(world, user), plugin.getCalculator());
    }

    private MetaData groupMeta(Group group, String world) {
        ContextSet ctx = plugin.getContextManager().staticContext();
        if (world != null) ctx = ctx.with("world", world);
        return plugin.getCalculator().calculateMeta(group, ctx);
    }

    private boolean inGroup(User u, String world, String group) {
        if (u == null || group == null) return false;
        String g = group.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis() / 1000L;
        for (Group parent : plugin.getCalculator().directParents(u, queryContext(world, u), now)) {
            if (parent.getName().equals(g)) return true;
        }
        return false;
    }

    private boolean addGroup(User u, String group) {
        Group g = plugin.getGroupManager().get(group);
        if (u == null || g == null) return false;
        return mutate(u, Node.inheritance(g.getName(), ContextSet.EMPTY), true, "parent add " + g.getName());
    }

    private boolean removeGroup(User u, String group) {
        if (u == null || group == null) return false;
        String g = group.toLowerCase(Locale.ROOT);
        if (!u.unsetNode(Node.inheritance(g, ContextSet.EMPTY)).wasSuccessful()) return false;
        plugin.getService().ensureDefaultGroup(u);
        if (g.equals(u.getPrimaryGroup())) {
            u.setPrimaryGroup(plugin.getCalculator().primaryGroup(u, ContextSet.EMPTY, System.currentTimeMillis() / 1000L));
        }
        plugin.getService().commit(u, null, "vault: parent remove " + g);
        return true;
    }

    private String[] groupsOf(User u, String world) {
        if (u == null) return new String[0];
        List<String> out = new ArrayList<String>();
        long now = System.currentTimeMillis() / 1000L;
        for (Group g : plugin.getCalculator().directParents(u, queryContext(world, u), now)) out.add(g.getName());
        return out.toArray(new String[0]);
    }

    /**
     * Vault has no priority concept: replace the holder's own permanent global
     * prefix, or add one with a sufficiently high priority.
     */
    private void setChat(PermissionHolder holder, final NodeType type, String value) {
        if (holder == null) return;
        int priority = holder instanceof Group ? Math.max(1, ((Group) holder).getWeight()) : 1000;
        for (Node n : holder.getNodes(type)) {
            if (!n.isTemporary() && n.contexts().isEmpty()) priority = Math.max(priority, n.chatPriority());
        }
        holder.removeIf(n -> n.type() == type && !n.isTemporary() && n.contexts().isEmpty());
        if (value != null && !value.isEmpty()) {
            String prefix = type == NodeType.PREFIX ? "prefix." : "suffix.";
            holder.setNode(Node.of(prefix + priority + "." + value, true, 0L, ContextSet.EMPTY));
        }
        plugin.getService().commit(holder, null, "vault: " + (type == NodeType.PREFIX ? "setprefix" : "setsuffix")
                + " \"" + value + "\"");
    }

    private void setMeta(PermissionHolder holder, String key, Object value) {
        if (holder == null || key == null) return;
        final String k = key.toLowerCase(Locale.ROOT);
        if (k.contains(".")) return;
        holder.removeIf(n -> n.type() == NodeType.META && n.metaKey().equals(k)
                && !n.isTemporary() && n.contexts().isEmpty());
        if (value != null) holder.setNode(Node.meta(k, String.valueOf(value), ContextSet.EMPTY));
        plugin.getService().commit(holder, null, "vault: meta set " + k + " " + value);
    }

    private String playerMeta(User u, String world, String key) {
        return u == null ? null : meta(u, world).metaValue(key);
    }

    private String groupMetaValue(String world, String group, String key) {
        Group g = plugin.getGroupManager().get(group);
        return g == null ? null : groupMeta(g, world).metaValue(key);
    }

    // ================================================================== Permission

    private final class VaultPermission extends Permission {

        VaultPermission() {
            this.plugin = VaultHook.this.plugin;
        }

        @Override
        public String getName() {
            return "RexPerms";
        }

        @Override
        public boolean isEnabled() {
            return VaultHook.this.plugin.isEnabled();
        }

        @Override
        public boolean hasSuperPermsCompat() {
            return true;
        }

        @Override
        public boolean hasGroupSupport() {
            return true;
        }

        @Override
        public boolean playerHas(String world, String player, String perm) {
            return userHas(user(player), world, perm);
        }

        @Override
        public boolean playerHas(String world, OfflinePlayer player, String perm) {
            return userHas(user(player), world, perm);
        }

        @Override
        public boolean playerAdd(String world, String player, String perm) {
            return mutate(user(player), Node.permission(perm, true, ContextSet.EMPTY), true, "permission set " + perm);
        }

        @Override
        public boolean playerAdd(String world, OfflinePlayer player, String perm) {
            return mutate(user(player), Node.permission(perm, true, ContextSet.EMPTY), true, "permission set " + perm);
        }

        @Override
        public boolean playerRemove(String world, String player, String perm) {
            return mutate(user(player), Node.permission(perm, true, ContextSet.EMPTY), false, "permission unset " + perm);
        }

        @Override
        public boolean playerRemove(String world, OfflinePlayer player, String perm) {
            return mutate(user(player), Node.permission(perm, true, ContextSet.EMPTY), false, "permission unset " + perm);
        }

        @Override
        public boolean groupHas(String world, String group, String perm) {
            Group g = VaultHook.this.plugin.getGroupManager().get(group);
            if (g == null) return false;
            ContextSet ctx = VaultHook.this.plugin.getContextManager().staticContext();
            if (world != null) ctx = ctx.with("world", world);
            return VaultHook.this.plugin.getCalculator().calculatePermissions(g, ctx).check(perm) == Tristate.TRUE;
        }

        @Override
        public boolean groupAdd(String world, String group, String perm) {
            return mutate(VaultHook.this.plugin.getGroupManager().get(group),
                    Node.permission(perm, true, ContextSet.EMPTY), true, "permission set " + perm);
        }

        @Override
        public boolean groupRemove(String world, String group, String perm) {
            return mutate(VaultHook.this.plugin.getGroupManager().get(group),
                    Node.permission(perm, true, ContextSet.EMPTY), false, "permission unset " + perm);
        }

        @Override
        public boolean playerInGroup(String world, String player, String group) {
            return inGroup(user(player), world, group);
        }

        @Override
        public boolean playerInGroup(String world, OfflinePlayer player, String group) {
            return inGroup(user(player), world, group);
        }

        @Override
        public boolean playerAddGroup(String world, String player, String group) {
            return addGroup(user(player), group);
        }

        @Override
        public boolean playerAddGroup(String world, OfflinePlayer player, String group) {
            return addGroup(user(player), group);
        }

        @Override
        public boolean playerRemoveGroup(String world, String player, String group) {
            return removeGroup(user(player), group);
        }

        @Override
        public boolean playerRemoveGroup(String world, OfflinePlayer player, String group) {
            return removeGroup(user(player), group);
        }

        @Override
        public String[] getPlayerGroups(String world, String player) {
            return groupsOf(user(player), world);
        }

        @Override
        public String[] getPlayerGroups(String world, OfflinePlayer player) {
            return groupsOf(user(player), world);
        }

        @Override
        public String getPrimaryGroup(String world, String player) {
            User u = user(player);
            return u == null ? null : meta(u, world).primaryGroup();
        }

        @Override
        public String getPrimaryGroup(String world, OfflinePlayer player) {
            User u = user(player);
            return u == null ? null : meta(u, world).primaryGroup();
        }

        @Override
        public String[] getGroups() {
            return VaultHook.this.plugin.getGroupManager().names().toArray(new String[0]);
        }
    }

    // ================================================================== Chat

    private final class VaultChat extends Chat {

        VaultChat(Permission perms) {
            super(perms);
        }

        @Override
        public String getName() {
            return "RexPerms";
        }

        @Override
        public boolean isEnabled() {
            return plugin.isEnabled();
        }

        @Override
        public String getPlayerPrefix(String world, String player) {
            User u = user(player);
            return u == null ? "" : nullToEmpty(meta(u, world).prefix());
        }

        @Override
        public String getPlayerPrefix(String world, OfflinePlayer player) {
            User u = user(player);
            return u == null ? "" : nullToEmpty(meta(u, world).prefix());
        }

        @Override
        public String getPlayerSuffix(String world, String player) {
            User u = user(player);
            return u == null ? "" : nullToEmpty(meta(u, world).suffix());
        }

        @Override
        public String getPlayerSuffix(String world, OfflinePlayer player) {
            User u = user(player);
            return u == null ? "" : nullToEmpty(meta(u, world).suffix());
        }

        @Override
        public void setPlayerPrefix(String world, String player, String prefix) {
            setChat(user(player), NodeType.PREFIX, prefix);
        }

        @Override
        public void setPlayerPrefix(String world, OfflinePlayer player, String prefix) {
            setChat(user(player), NodeType.PREFIX, prefix);
        }

        @Override
        public void setPlayerSuffix(String world, String player, String suffix) {
            setChat(user(player), NodeType.SUFFIX, suffix);
        }

        @Override
        public void setPlayerSuffix(String world, OfflinePlayer player, String suffix) {
            setChat(user(player), NodeType.SUFFIX, suffix);
        }

        @Override
        public String getGroupPrefix(String world, String group) {
            Group g = plugin.getGroupManager().get(group);
            return g == null ? "" : nullToEmpty(groupMeta(g, world).prefix());
        }

        @Override
        public String getGroupSuffix(String world, String group) {
            Group g = plugin.getGroupManager().get(group);
            return g == null ? "" : nullToEmpty(groupMeta(g, world).suffix());
        }

        @Override
        public void setGroupPrefix(String world, String group, String prefix) {
            setChat(plugin.getGroupManager().get(group), NodeType.PREFIX, prefix);
        }

        @Override
        public void setGroupSuffix(String world, String group, String suffix) {
            setChat(plugin.getGroupManager().get(group), NodeType.SUFFIX, suffix);
        }

        @Override
        public int getPlayerInfoInteger(String world, String player, String node, int def) {
            return toInt(playerMeta(user(player), world, node), def);
        }

        @Override
        public void setPlayerInfoInteger(String world, String player, String node, int value) {
            setMeta(user(player), node, value);
        }

        @Override
        public int getGroupInfoInteger(String world, String group, String node, int def) {
            return toInt(groupMetaValue(world, group, node), def);
        }

        @Override
        public void setGroupInfoInteger(String world, String group, String node, int value) {
            setMeta(plugin.getGroupManager().get(group), node, value);
        }

        @Override
        public double getPlayerInfoDouble(String world, String player, String node, double def) {
            return toDouble(playerMeta(user(player), world, node), def);
        }

        @Override
        public void setPlayerInfoDouble(String world, String player, String node, double value) {
            setMeta(user(player), node, value);
        }

        @Override
        public double getGroupInfoDouble(String world, String group, String node, double def) {
            return toDouble(groupMetaValue(world, group, node), def);
        }

        @Override
        public void setGroupInfoDouble(String world, String group, String node, double value) {
            setMeta(plugin.getGroupManager().get(group), node, value);
        }

        @Override
        public boolean getPlayerInfoBoolean(String world, String player, String node, boolean def) {
            return toBool(playerMeta(user(player), world, node), def);
        }

        @Override
        public void setPlayerInfoBoolean(String world, String player, String node, boolean value) {
            setMeta(user(player), node, value);
        }

        @Override
        public boolean getGroupInfoBoolean(String world, String group, String node, boolean def) {
            return toBool(groupMetaValue(world, group, node), def);
        }

        @Override
        public void setGroupInfoBoolean(String world, String group, String node, boolean value) {
            setMeta(plugin.getGroupManager().get(group), node, value);
        }

        @Override
        public String getPlayerInfoString(String world, String player, String node, String def) {
            String v = playerMeta(user(player), world, node);
            return v == null ? def : v;
        }

        @Override
        public void setPlayerInfoString(String world, String player, String node, String value) {
            setMeta(user(player), node, value);
        }

        @Override
        public String getGroupInfoString(String world, String group, String node, String def) {
            String v = groupMetaValue(world, group, node);
            return v == null ? def : v;
        }

        @Override
        public void setGroupInfoString(String world, String group, String node, String value) {
            setMeta(plugin.getGroupManager().get(group), node, value);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static int toInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double toDouble(String s, double def) {
        if (s == null) return def;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean toBool(String s, boolean def) {
        if (s == null) return def;
        if (s.equalsIgnoreCase("true")) return true;
        if (s.equalsIgnoreCase("false")) return false;
        return def;
    }
}
