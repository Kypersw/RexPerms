package xyz.rexos.rexperms.hook;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import xyz.rexos.rexperms.RexPermsPlugin;
import xyz.rexos.rexperms.calc.MetaData;
import xyz.rexos.rexperms.inject.PermissibleInjector;
import xyz.rexos.rexperms.inject.RexPermissible;
import xyz.rexos.rexperms.model.ContextSet;
import xyz.rexos.rexperms.model.Group;
import xyz.rexos.rexperms.model.Node;
import xyz.rexos.rexperms.model.Track;
import xyz.rexos.rexperms.model.User;
import xyz.rexos.rexperms.util.Durations;
import xyz.rexos.rexperms.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PlaceholderAPI placeholders. Full list in the README.
 *
 * <pre>
 * %rexperms_prefix%                    %rexperms_suffix%
 * %rexperms_primary_group%             %rexperms_primary_group_display%
 * %rexperms_groups%                    %rexperms_weight%
 * %rexperms_has_permission_&lt;perm&gt;%     %rexperms_in_group_&lt;group&gt;%
 * %rexperms_inherits_group_&lt;group&gt;%    %rexperms_meta_&lt;key&gt;%
 * %rexperms_expiry_time_&lt;perm&gt;%        %rexperms_group_expiry_time_&lt;group&gt;%
 * %rexperms_track_next_&lt;track&gt;%        %rexperms_track_previous_&lt;track&gt;%
 * %rexperms_prefix_raw%                %rexperms_suffix_raw%
 * </pre>
 */
public final class PlaceholderHook extends PlaceholderExpansion {

    private final RexPermsPlugin plugin;

    public PlaceholderHook(RexPermsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "rexperms";
    }

    @Override
    public String getAuthor() {
        return "RexOS";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    /** Older PlaceholderAPI versions call this. */
    @Override
    public boolean canRegister() {
        return true;
    }

    /** Entry point used by PlaceholderAPI releases older than 2.10. */
    @Override
    public String onPlaceholderRequest(Player player, String params) {
        return onRequest(player, params);
    }

    @Override
    public String onRequest(OfflinePlayer offline, String params) {
        if (offline == null) return "";
        User user = plugin.getUserManager().get(offline.getUniqueId());
        if (user == null) return "";

        Player online = offline.getPlayer();
        RexPermissible rex = online == null ? null : PermissibleInjector.getInjected(online);
        ContextSet ctx = rex != null ? rex.getContext() : plugin.getContextManager().staticContext();
        String p = params.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis() / 1000L;

        if (p.equals("prefix")) return Text.legacy(meta(user, ctx).prefix());
        if (p.equals("suffix")) return Text.legacy(meta(user, ctx).suffix());
        if (p.equals("prefix_raw")) return nullToEmpty(meta(user, ctx).prefix());
        if (p.equals("suffix_raw")) return nullToEmpty(meta(user, ctx).suffix());
        if (p.equals("primary_group") || p.equals("group")) return meta(user, ctx).primaryGroup();
        if (p.equals("primary_group_display") || p.equals("group_display")) {
            String primary = meta(user, ctx).primaryGroup();
            Group g = plugin.getGroupManager().get(primary);
            return g == null ? primary : Text.legacy(g.getPlainDisplayName());
        }
        if (p.equals("groups")) {
            List<String> names = new ArrayList<String>();
            for (Group g : plugin.getCalculator().directParents(user, ctx, now)) names.add(g.getName());
            return Text.join(", ", names);
        }
        if (p.equals("weight")) return String.valueOf(meta(user, ctx).weight());

        if (p.startsWith("has_permission_")) {
            String perm = params.substring("has_permission_".length());
            boolean v = online != null ? online.hasPermission(perm)
                    : user.getCachedData().permissions(ctx, plugin.getCalculator()).check(perm).asBoolean();
            return bool(v);
        }
        if (p.startsWith("in_group_")) {
            String g = p.substring("in_group_".length());
            for (Group parent : plugin.getCalculator().directParents(user, ctx, now)) {
                if (parent.getName().equals(g)) return bool(true);
            }
            return bool(false);
        }
        if (p.startsWith("inherits_group_")) {
            String g = p.substring("inherits_group_".length());
            for (Group parent : plugin.getCalculator().resolveInheritance(user, ctx)) {
                if (parent.getName().equals(g)) return bool(true);
            }
            return bool(false);
        }
        if (p.startsWith("meta_")) return nullToEmpty(meta(user, ctx).metaValue(p.substring("meta_".length())));
        if (p.startsWith("group_expiry_time_")) {
            return expiry(user, "group." + p.substring("group_expiry_time_".length()), now);
        }
        if (p.startsWith("expiry_time_")) return expiry(user, p.substring("expiry_time_".length()), now);
        if (p.startsWith("track_next_") || p.startsWith("track_previous_")) {
            boolean next = p.startsWith("track_next_");
            Track track = plugin.getTrackManager().get(p.substring(next ? "track_next_".length()
                    : "track_previous_".length()));
            if (track == null) return "";
            for (Node n : user.getParentNodes()) {
                if (!n.contexts().isEmpty() || !track.contains(n.groupName())) continue;
                String r = next ? track.next(n.groupName()) : track.previous(n.groupName());
                return r == null ? "" : r;
            }
            List<String> groups = track.getGroups();
            return next && !groups.isEmpty() ? groups.get(0) : "";
        }
        return null;
    }

    private MetaData meta(User user, ContextSet ctx) {
        return user.getCachedData().meta(ctx, plugin.getCalculator());
    }

    private static String expiry(User user, String key, long now) {
        for (Node n : user.getNodes()) {
            if (n.isTemporary() && n.key().equals(key)) return Durations.format(n.secondsLeft(now));
        }
        return "";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String bool(boolean b) {
        try {
            return b ? me.clip.placeholderapi.PlaceholderAPIPlugin.booleanTrue()
                    : me.clip.placeholderapi.PlaceholderAPIPlugin.booleanFalse();
        } catch (Throwable t) {
            return b ? "yes" : "no";
        }
    }
}
