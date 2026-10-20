package xyz.rexos.rexperms.inject;

import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachmentInfo;
import xyz.rexos.rexperms.RexPermsPlugin;
import xyz.rexos.rexperms.calc.CalculatedPermissions;
import xyz.rexos.rexperms.calc.Tristate;
import xyz.rexos.rexperms.model.ContextSet;
import xyz.rexos.rexperms.model.User;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Replaces the player's built-in {@link PermissibleBase}.
 *
 * Bukkit's own implementation rebuilds every permission on each change and
 * knows nothing about wildcards. This class answers each query straight from
 * RexPerms' cache:
 * <ol>
 *   <li>Temporary permissions other plugins attached (PermissionAttachment)</li>
 *   <li>RexPerms data (user + groups, wildcards, child permissions)</li>
 *   <li>The plugin.yml default of the permission (op / true / false)</li>
 * </ol>
 */
public final class RexPermissible extends PermissibleBase {

    private final Player player;
    private final User user;
    private final RexPermsPlugin plugin;

    /** The object we replaced; restored when the plugin disables. */
    private PermissibleBase previous;

    /** Cached contexts, refreshed on world/gamemode change. */
    private volatile ContextSet context;

    /** Permissions from attachments added by other plugins (lower-case). */
    private volatile Map<String, Boolean> attachmentPermissions = Collections.emptyMap();

    public RexPermissible(Player player, User user, RexPermsPlugin plugin) {
        super(player);
        this.player = player;
        this.user = user;
        this.plugin = plugin;
        this.context = plugin.getContextManager().calculate(player);
        rebuildAttachments();
    }

    public User getUser() {
        return user;
    }

    public ContextSet getContext() {
        return context;
    }

    public void refreshContext() {
        this.context = plugin.getContextManager().calculate(player);
    }

    void setPrevious(PermissibleBase previous) {
        this.previous = previous;
    }

    PermissibleBase getPrevious() {
        return previous;
    }

    // ------------------------------------------------------------------ queries

    @Override
    public boolean isPermissionSet(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (attachments().containsKey(key)) return true;
        return permissions().check(key) != Tristate.UNDEFINED;
    }

    @Override
    public boolean isPermissionSet(Permission perm) {
        return isPermissionSet(perm.getName());
    }

    @Override
    public boolean hasPermission(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Tristate value = Tristate.UNDEFINED;
        String cause = null;

        Boolean attached = attachments().get(key);
        if (attached != null) {
            value = Tristate.of(attached);
            cause = "attachment";
        } else {
            CalculatedPermissions.Result r = permissions().lookup(key);
            if (r.value() != Tristate.UNDEFINED) {
                value = r.value();
                cause = r.source().holder() + " → " + r.matchedNode();
            }
        }
        if (value != Tristate.UNDEFINED) return report(key, value.asBoolean(), cause);

        if (plugin.getSettings().opHasAll() && isOp()) return report(key, true, "op");

        if (plugin.getSettings().applyDefaultPermissions()) {
            // Bukkit's own answer: plugin.yml defaults and their children.
            // Attachments were checked above, so only defaults remain here.
            return report(key, super.hasPermission(name), "default");
        }
        // With defaults disabled, undefined permissions are available to operators only
        return report(key, Permission.DEFAULT_PERMISSION.getValue(isOp()), "undefined");
    }

    @Override
    public boolean hasPermission(Permission perm) {
        String key = perm.getName().toLowerCase(Locale.ROOT);
        Boolean attached = attachments().get(key);
        if (attached != null) return report(key, attached, "attachment");
        CalculatedPermissions.Result r = permissions().lookup(key);
        if (r.value() != Tristate.UNDEFINED) {
            return report(key, r.value().asBoolean(), r.source().holder() + " → " + r.matchedNode());
        }
        if (plugin.getSettings().opHasAll() && isOp()) return report(key, true, "op");
        if (plugin.getSettings().applyDefaultPermissions()) {
            return report(key, super.hasPermission(perm), "default:" + perm.getDefault());
        }
        return report(key, Permission.DEFAULT_PERMISSION.getValue(isOp()), "undefined");
    }

    private boolean report(String key, boolean value, String cause) {
        plugin.getVerboseHandler().record(player.getName(), key, value, cause);
        return value;
    }

    private CalculatedPermissions permissions() {
        return user.getCachedData().permissions(context, plugin.getCalculator());
    }

    private Map<String, Boolean> attachments() {
        Map<String, Boolean> m = attachmentPermissions;
        return m == null ? Collections.<String, Boolean>emptyMap() : m;
    }

    // ------------------------------------------------------------------ attachments

    /**
     * Bukkit calls this whenever an attachment is added or removed. We let the
     * superclass do its calculation and keep only the attachment-sourced entries.
     */
    @Override
    public synchronized void recalculatePermissions() {
        super.recalculatePermissions();
        // The superclass constructor calls this before our fields are assigned
        if (plugin != null) rebuildAttachments();
    }

    private void rebuildAttachments() {
        Map<String, Boolean> map = new HashMap<String, Boolean>();
        for (PermissionAttachmentInfo info : super.getEffectivePermissions()) {
            if (info.getAttachment() != null) {
                map.put(info.getPermission().toLowerCase(Locale.ROOT), info.getValue());
            }
        }
        attachmentPermissions = map.isEmpty()
                ? Collections.<String, Boolean>emptyMap()
                : Collections.unmodifiableMap(map);
    }

    /**
     * Some plugins (menus, tab completers) list effective permissions.
     * We add RexPerms' permissions to Bukkit's own list.
     */
    @Override
    public Set<PermissionAttachmentInfo> getEffectivePermissions() {
        Set<PermissionAttachmentInfo> out = new HashSet<PermissionAttachmentInfo>(super.getEffectivePermissions());
        Set<String> present = new HashSet<String>();
        for (PermissionAttachmentInfo i : out) present.add(i.getPermission().toLowerCase(Locale.ROOT));
        for (Map.Entry<String, CalculatedPermissions.Source> e : permissions().asMap().entrySet()) {
            if (present.add(e.getKey())) {
                out.add(new PermissionAttachmentInfo(player, e.getKey(), null, e.getValue().value()));
            }
        }
        return out;
    }
}
