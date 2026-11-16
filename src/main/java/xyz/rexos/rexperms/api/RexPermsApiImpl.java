package xyz.rexos.rexperms.api;

import org.bukkit.entity.Player;

import xyz.rexos.rexperms.RexPermsPlugin;
import xyz.rexos.rexperms.api.event.PreHolderMutationEvent;
import xyz.rexos.rexperms.calc.MetaData;
import xyz.rexos.rexperms.inject.PermissibleInjector;
import xyz.rexos.rexperms.inject.RexPermissible;
import xyz.rexos.rexperms.model.ContextSet;
import xyz.rexos.rexperms.model.DataEpoch;
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
    public void registerAuthorizationPolicy(
            org.bukkit.plugin.Plugin owner, AuthorizationPolicy policy) {
        plugin.registerPolicy(owner, policy);
    }

    @Override
    public User getLoadedUser(UUID uuid) {
        return plugin.getUserManager().get(uuid);
    }

    @Override
    public CompletableFuture<User> loadUser(UUID uuid) {
        return plugin.getService().loadUser(uuid, null);
    }

    /** API writes use a detached working copy; cancellation cannot leak live mutations. */
    @Override
    public CompletableFuture<Void> modifyUser(UUID uuid, final Consumer<User> action) {
        return modifyUserInternal(uuid, null, action);
    }

    @Override
    public CompletableFuture<Void> modifyUserAs(UUID uuid, Player actor, Consumer<User> action) {
        if (actor == null) throw new IllegalArgumentException("actor cannot be null");
        return modifyUserInternal(uuid, actor, action);
    }

    private CompletableFuture<Void> modifyUserInternal(
            UUID uuid, final Player actor, final Consumer<User> action) {
        return loadUser(uuid)
                .thenComposeAsync(
                        user -> {
                            User draft =
                                    new User(
                                            user.getUniqueId(),
                                            user.getUsername(),
                                            new DataEpoch());
                            draft.replaceNodes(user.getNodes());
                            draft.setPrimaryGroup(user.getPrimaryGroup());
                            action.accept(draft);
                            plugin.getService().ensureDefaultGroup(draft);
                            return plugin.mutate(
                                    new xyz.rexos.rexperms.api.mutation.MutationContext(
                                            actor == null
                                                    ? PreHolderMutationEvent.Origin.API
                                                    : PreHolderMutationEvent.Origin.PLAYER,
                                            actor == null ? null : actor.getUniqueId().toString(),
                                            "API_USER"),
                                    () -> {
                                        user.replaceNodes(draft.getNodes());
                                        user.setPrimaryGroup(draft.getPrimaryGroup());
                                        plugin.getService().ensureDefaultGroup(user);
                                        return plugin.getService().commit(user, null, "API change");
                                    });
                        },
                        plugin.mainThread());
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
        if (group == null || plugin.getGroupManager().get(group.getName()) != group)
            throw new IllegalArgumentException("Expected a managed group");
        CompletableFuture<Void> result = new CompletableFuture<Void>();
        plugin.mainThread()
                .execute(
                        () -> {
                            try {
                                Group draft = new Group(group.getName(), new DataEpoch());
                                draft.replaceNodes(group.getNodes());
                                action.accept(draft);
                                plugin.mutate(
                                        new xyz.rexos.rexperms.api.mutation.MutationContext(
                                                PreHolderMutationEvent.Origin.API,
                                                null,
                                                "API_GROUP"),
                                        () -> {
                                            group.replaceNodes(draft.getNodes());
                                            plugin.getService()
                                                    .commit(group, null, "API change")
                                                    .whenComplete(
                                                            (v, err) -> {
                                                                if (err != null)
                                                                    result.completeExceptionally(
                                                                            err);
                                                                else result.complete(null);
                                                            });
                                            return null;
                                        });
                            } catch (Throwable t) {
                                result.completeExceptionally(t);
                            }
                        });
        return result;
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
