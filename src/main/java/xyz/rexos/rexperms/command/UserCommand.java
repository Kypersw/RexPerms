package xyz.rexos.rexperms.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import xyz.rexos.rexperms.RexPermsPlugin;
import xyz.rexos.rexperms.api.event.UserTrackChangeEvent;
import xyz.rexos.rexperms.calc.MetaData;
import xyz.rexos.rexperms.config.Messages;
import xyz.rexos.rexperms.inject.PermissibleInjector;
import xyz.rexos.rexperms.inject.RexPermissible;
import xyz.rexos.rexperms.model.ContextSet;
import xyz.rexos.rexperms.model.Group;
import xyz.rexos.rexperms.model.Node;
import xyz.rexos.rexperms.model.Track;
import xyz.rexos.rexperms.model.User;
import xyz.rexos.rexperms.service.TrackResult;
import xyz.rexos.rexperms.util.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** /rp user &lt;player&gt; ... */
final class UserCommand extends CommandSupport {

    static final List<String> SUBS = Collections.unmodifiableList(Arrays.asList(
            "info", "permission", "parent", "meta", "promote", "demote", "showtracks", "clear"));

    private final HolderCommands holderCommands;

    UserCommand(RexPermsPlugin plugin, HolderCommands holderCommands) {
        super(plugin);
        this.holderCommands = holderCommands;
    }

    void execute(final CommandSender sender, final String label, Args a) {
        if (!a.has(0)) {
            usage(sender, "/" + label + " user <player> <" + Text.join("|", SUBS) + ">");
            return;
        }
        final String target = a.get(0);
        final Args rest = a.from(1);
        Player online = Bukkit.getPlayerExact(target);
        if (online == null || plugin.getUserManager().get(online.getUniqueId()) == null) {
            msg().send(sender, "general.loading-user", Messages.text("user", target));
        }

        plugin.getService().lookupUser(target).thenAcceptAsync(user -> {
            if (user == null) {
                msg().send(sender, "user.not-found", Messages.text("user", target));
                return;
            }
            handle(sender, user, "/" + label + " user " + user.getFriendlyName(), rest);
        }, plugin.mainThread()).exceptionally(t -> {
            msg().send(sender, "general.storage-error");
            return null;
        });
    }

    private void handle(CommandSender s, User user, String base, Args a) {
        String sub = a.has(0) ? a.lower(0) : "info";
        if (holderCommands.handle(s, user, base, a)) return;

        if (sub.equals("info")) {
            if (allowed(s, "user.info")) info(s, user);
        } else if (sub.equals("promote") || sub.equals("demote")) {
            if (!allowed(s, "user." + sub)) return;
            Args rest = a.from(1);
            ContextSet ctx = rest.extractContext();
            if (!rest.has(0)) {
                usage(s, base + " " + sub + " <track> [context...]");
                return;
            }
            Track track = plugin.getTrackManager().get(rest.get(0));
            if (track == null) {
                msg().send(s, "track.not-found", Messages.text("track", rest.get(0)));
                return;
            }
            trackMove(s, user, track, ctx, sub.equals("promote"));
        } else if (sub.equals("showtracks")) {
            if (allowed(s, "user.showtracks")) showTracks(s, user);
        } else if (sub.equals("clear")) {
            if (!allowed(s, "user.clear")) return;
            Args rest = a.from(1);
            final ContextSet ctx = rest.extractContext();
            int removed = user.removeIf(n -> ctx.isEmpty() || n.contexts().equals(ctx));
            if (ctx.isEmpty()) user.setPrimaryGroup("default");
            plugin.getService().ensureDefaultGroup(user);
            msg().send(s, "user.cleared", HolderCommands.holderName(user), Messages.text("count", removed), ctx(ctx));
            plugin.getService().commit(user, s, "clear" + Text.contextSuffix(ctx));
        } else {
            usage(s, base + " <" + Text.join("|", SUBS) + ">");
        }
    }

    private void info(CommandSender s, User user) {
        Player online = Bukkit.getPlayer(user.getUniqueId());
        RexPermissible rex = online == null ? null : PermissibleInjector.getInjected(online);
        ContextSet ctx = rex != null ? rex.getContext() : plugin.getContextManager().staticContext();
        MetaData meta = user.getCachedData().meta(ctx, plugin.getCalculator());

        List<String> parents = new ArrayList<String>();
        List<String> tempParents = new ArrayList<String>();
        for (Node n : user.getParentNodes()) {
            String entry = n.groupName() + Text.contextSuffix(n.contexts());
            if (n.isTemporary()) tempParents.add(entry + expiryText(n));
            else parents.add(entry);
        }

        msg().sendList(s, "user.info",
                Messages.text("user", user.getFriendlyName()),
                Messages.text("uuid", user.getUniqueId()),
                Messages.comp("status", online != null
                        ? msg().component("user.status-online")
                        : msg().component("user.status-offline")),
                Messages.text("primary", meta.primaryGroup()),
                Messages.text("parents", parents.isEmpty() ? "-" : Text.join(", ", parents)),
                Messages.text("temp_parents", tempParents.isEmpty() ? "-" : Text.join(", ", tempParents)),
                Messages.comp("prefix", meta.prefix() == null ? Component.text("-", NamedTextColor.DARK_GRAY)
                        : Text.colorize(meta.prefix())),
                Messages.comp("suffix", meta.suffix() == null ? Component.text("-", NamedTextColor.DARK_GRAY)
                        : Text.colorize(meta.suffix())),
                Messages.text("nodes", user.nodeCount()),
                Messages.text("context", ctx.toString()));
    }

    private void trackMove(CommandSender s, User user, Track track, ContextSet ctx, boolean promote) {
        TrackResult r = promote
                ? plugin.getService().promote(user, track, ctx)
                : plugin.getService().demote(user, track, ctx);
        TagResolver holder = HolderCommands.holderName(user);
        TagResolver trackName = Messages.text("track", track.getName());
        switch (r.outcome()) {
            case EMPTY_TRACK:
                msg().send(s, "track.empty", trackName);
                return;
            case AMBIGUOUS:
                msg().send(s, "track.ambiguous", holder, trackName);
                return;
            case NOT_ON_TRACK:
                msg().send(s, "track.not-on-track", holder, trackName);
                return;
            case GROUP_MISSING:
                msg().send(s, "track.group-missing", trackName);
                return;
            case END_OF_TRACK:
                msg().send(s, "track.end-of-track", holder, trackName, Messages.text("group", r.from()));
                return;
            default:
                break;
        }

        String key;
        if (r.outcome() == TrackResult.Outcome.ADDED_TO_FIRST) key = "track.added-to-first";
        else if (r.outcome() == TrackResult.Outcome.REMOVED_FROM_FIRST) key = "track.removed-from-track";
        else key = promote ? "track.promoted" : "track.demoted";

        String from = r.from() == null ? "-" : r.from();
        String to = r.to() == null ? "-" : r.to();
        msg().send(s, key, holder, trackName, Messages.text("from", from), Messages.text("to", to), ctx(ctx));
        plugin.getService().commit(user, s, (promote ? "promote " : "demote ") + track.getName()
                + " (" + from + " → " + to + ")" + Text.contextSuffix(ctx));
        Bukkit.getPluginManager().callEvent(new UserTrackChangeEvent(user.getUniqueId(), user.getFriendlyName(),
                track.getName(), promote ? UserTrackChangeEvent.Action.PROMOTE : UserTrackChangeEvent.Action.DEMOTE,
                r.from(), r.to()));

        Player online = Bukkit.getPlayer(user.getUniqueId());
        if (online != null && online != s) {
            msg().send(online, promote ? "track.notify-promoted" : "track.notify-demoted",
                    Messages.comp("group", displayOf(r.to() == null ? r.from() : r.to())));
        }
    }

    private Component displayOf(String group) {
        Group g = plugin.getGroupManager().get(group);
        return Text.colorize(g == null ? group : g.getPlainDisplayName());
    }

    private void showTracks(CommandSender s, User user) {
        boolean any = false;
        msg().send(s, "user.showtracks-header", HolderCommands.holderName(user));
        for (Track t : plugin.getTrackManager().all()) {
            List<String> groups = t.getGroups();
            for (Node n : user.getParentNodes()) {
                if (!groups.contains(n.groupName())) continue;
                any = true;
                StringBuilder path = new StringBuilder();
                for (String g : groups) {
                    if (path.length() > 0) path.append(" <dark_gray>→</dark_gray> ");
                    // group names are [a-z0-9_-], safe to embed in MiniMessage
                    path.append(g.equals(n.groupName()) ? "<green><b>" + g + "</b></green>" : "<gray>" + g + "</gray>");
                }
                msg().sendPlain(s, "user.showtracks-entry",
                        Messages.text("track", t.getName()),
                        Messages.comp("path", Messages.mini().deserialize(path.toString())),
                        ctx(n.contexts()));
            }
        }
        if (!any) msg().sendPlain(s, "user.showtracks-none");
    }
}
