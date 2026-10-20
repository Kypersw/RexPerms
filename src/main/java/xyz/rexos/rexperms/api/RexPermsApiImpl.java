package xyz.rexos.rexperms.api;

import org.bukkit.entity.Player;
import xyz.rexos.rexperms.RexPermsPlugin;
import xyz.rexos.rexperms.calc.MetaData;
import xyz.rexos.rexperms.inject.PermissibleInjector;
import xyz.rexos.rexperms.inject.RexPermissible;
import xyz.rexos.rexperms.model.ContextSet;
import xyz.rexos.rexperms.model.Group;
import xyz.rexos.rexperms.model.Node;
import xyz.rexos.rexperms.model.User;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class RexPermsApiImpl implements RexPermsApi {

    private final RexPermsPlugin plugin;

    public RexPermsApiImpl(RexPermsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public User getLoadedUser(UUID uuid) {
        return plugin.getUserManager().get(uuid);
    }

    @Override
    public CompletableFuture<User> loadUser(UUID uuid) {
        return plugin.getService().loadUser(uuid, null);
    }

    @Override
    public CompletableFuture<Void> modifyUser(UUID uuid, final Consumer<User> action) {
        return loadUser(uuid).thenComposeAsync(user -> {
            action.accept(user);
            plugin.getService().ensureDefaultGroup(user);
            return plugin.getService().commit(user, null, "API change");
        }, plugin.mainThread());
    }

    @Override
    public Group getGroup(String name) {
        return plugin.getGroupManager().get(name);
    }

    @Override
    public Collection<Group> getGroups() {
        return Collections.unmodifiableCollection(plugin.getGroupManager().all());
    }

    @Override
    public CompletableFuture<Void> modifyGroup(Group group, Consumer<Group> action) {
        action.accept(group);
        return plugin.getService().commit(group, null, "API change");
    }

    @Override
    public String getPrefix(Player player) {
        MetaData m = meta(player);
        return m == null ? null : m.prefix();
    }

    @Override
    public String getSuffix(Player player) {
        MetaData m = meta(player);
        return m == null ? null : m.suffix();
    }

    @Override
    public String getPrimaryGroup(Player player) {
        MetaData m = meta(player);
        return m == null ? "default" : m.primaryGroup();
    }

    @Override
    public String getMeta(Player player, String key) {
        MetaData m = meta(player);
        return m == null ? null : m.metaValue(key);
    }

    @Override
    public boolean inheritsGroup(Player player, String group) {
        User u = plugin.getUserManager().get(player.getUniqueId());
        if (u == null) return false;
        String g = group.toLowerCase(Locale.ROOT);
        for (Group x : plugin.getCalculator().resolveInheritance(u, context(player))) {
            if (x.getName().equals(g)) return true;
        }
        return false;
    }

    @Override
    public Node permissionNode(String key, boolean value) {
        return Node.permission(key, value, ContextSet.EMPTY);
    }

    @Override
    public ContextSet globalContext() {
        return ContextSet.EMPTY;
    }

    private MetaData meta(Player player) {
        User u = plugin.getUserManager().get(player.getUniqueId());
        return u == null ? null : u.getCachedData().meta(context(player), plugin.getCalculator());
    }

    private ContextSet context(Player player) {
        RexPermissible rex = PermissibleInjector.getInjected(player);
        return rex != null ? rex.getContext() : plugin.getContextManager().calculate(player);
    }
}
