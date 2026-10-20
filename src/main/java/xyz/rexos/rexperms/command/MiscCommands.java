package xyz.rexos.rexperms.command;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import xyz.rexos.rexperms.RexPermsPlugin;
import xyz.rexos.rexperms.config.Messages;
import xyz.rexos.rexperms.migration.MigrationService;
import xyz.rexos.rexperms.model.Group;
import xyz.rexos.rexperms.model.HolderType;
import xyz.rexos.rexperms.model.Node;
import xyz.rexos.rexperms.model.PermissionHolder;
import xyz.rexos.rexperms.model.Track;
import xyz.rexos.rexperms.storage.HeldNode;
import xyz.rexos.rexperms.storage.LoggedAction;
import xyz.rexos.rexperms.util.Durations;
import xyz.rexos.rexperms.util.ServerVersion;
import xyz.rexos.rexperms.util.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Commands that are not tied to a holder. */
final class MiscCommands extends CommandSupport {

    private final MigrationService migration;

    MiscCommands(RexPermsPlugin plugin) {
        super(plugin);
        this.migration = new MigrationService(plugin);
    }

    // ------------------------------------------------------------------ info

    void help(CommandSender s, String label) {
        msg().sendList(s, "help", Messages.text("label", label));
    }

    void info(CommandSender s) {
        if (!allowed(s, "info")) return;
        msg().sendList(s, "info",
                Messages.text("version", plugin.getDescription().getVersion()),
                Messages.text("platform", Bukkit.getName() + " " + ServerVersion.describe()),
                Messages.text("storage", plugin.getStorage().storage().getName()),
                Messages.text("server", plugin.getSettings().server()),
                Messages.text("sync", plugin.getSyncPoller() != null ? "on" : "off"),
                Messages.text("groups", plugin.getGroupManager().all().size()),
                Messages.text("tracks", plugin.getTrackManager().all().size()),
                Messages.text("users", plugin.getUserManager().all().size()),
                Messages.text("online", Bukkit.getOnlinePlayers().size()),
                Messages.text("vault", Bukkit.getPluginManager().getPlugin("Vault") != null ? "hooked" : "not found"),
                Messages.text("papi", Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null ? "hooked" : "not found"));
    }

    // ------------------------------------------------------------------ groups

    void createGroup(final CommandSender s, String label, Args a) {
        if (!allowed(s, "creategroup")) return;
        if (!a.has(0)) {
            usage(s, "/" + label + " creategroup <name> [weight] [display-name]");
            return;
        }
        final String name = a.lower(0);
        if (!Group.isValidName(name)) {
            msg().send(s, "group.invalid-name", Messages.text("group", name));
            return;
        }
        if (plugin.getGroupManager().exists(name)) {
            msg().send(s, "group.already-exists", Messages.text("group", name));
            return;
        }
        Integer weight = a.has(1) ? Args.parseInt(a.get(1)) : null;
        if (a.has(1) && weight == null) {
            msg().send(s, "general.invalid-number", Messages.text("input", a.get(1)));
            return;
        }
        Group group = plugin.getGroupManager().getOrCreate(name);
        if (weight != null) group.setNode(Node.weight(weight));
        if (a.has(2)) group.setNode(Node.displayName(a.get(2)));
        plugin.getService().createGroup(name, s).thenAcceptAsync(g ->
                msg().send(s, "group.created", Messages.text("group", name)), plugin.mainThread());
    }

    void deleteGroupCommand(CommandSender s, String label, Args a) {
        if (!allowed(s, "deletegroup")) return;
        if (!a.has(0)) {
            usage(s, "/" + label + " deletegroup <name>");
            return;
        }
        deleteGroup(plugin, s, a.get(0));
    }

    static void deleteGroup(RexPermsPlugin plugin, final CommandSender s, String name) {
        final Messages msg = plugin.getMessages();
        final String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("default")) {
            msg.send(s, "group.cannot-delete-default");
            return;
        }
        if (!plugin.getGroupManager().exists(lower)) {
            msg.send(s, "group.not-found", Messages.text("group", lower));
            return;
        }
        msg.send(s, "group.deleting", Messages.text("group", lower));
        plugin.getService().deleteGroup(lower, s).thenAcceptAsync(updated ->
                        msg.send(s, "group.deleted", Messages.text("group", lower), Messages.text("count", updated)),
                plugin.mainThread()).exceptionally(t -> {
            msg.send(s, "general.storage-error");
            return null;
        });
    }

    void listGroups(CommandSender s, final String label, Args a) {
        if (!allowed(s, "listgroups")) return;
        List<Group> groups = plugin.getGroupManager().sorted();
        paginate(s, groups, parsePage(a, 0), "group.list-header", Messages.text("label", label), g -> {
            String prefix = plugin.getCalculator().calculateMeta(g, plugin.getContextManager().staticContext()).prefix();
            Component line = msg().component("group.list-entry",
                    Messages.text("group", g.getName()),
                    Messages.comp("display", Text.colorize(g.getPlainDisplayName())),
                    Messages.text("weight", g.getWeight()),
                    Messages.comp("prefix", prefix == null ? Component.empty() : Text.colorize(prefix)));
            return clickable(line, "/" + label + " group " + g.getName() + " info", "Click for info");
        });
    }

    // ------------------------------------------------------------------ tracks

    void createTrack(CommandSender s, String label, Args a) {
        if (!allowed(s, "createtrack")) return;
        if (!a.has(0)) {
            usage(s, "/" + label + " createtrack <name> [group1 group2 ...]");
            return;
        }
        String name = a.lower(0);
        if (!Group.isValidName(name)) {
            msg().send(s, "track.invalid-name", Messages.text("track", name));
            return;
        }
        if (plugin.getTrackManager().get(name) != null) {
            msg().send(s, "track.already-exists", Messages.text("track", name));
            return;
        }
        Track track = plugin.getTrackManager().getOrCreate(name);
        List<String> missing = new ArrayList<String>();
        for (int i = 1; i < a.size(); i++) {
            String g = a.lower(i);
            if (plugin.getGroupManager().exists(g)) track.append(g);
            else missing.add(g);
        }
        plugin.getService().commitTrack(track, s, "created" + (track.size() > 0 ? " (" + track + ")" : ""));
        msg().send(s, "track.created", Messages.text("track", name),
                Messages.text("path", track.size() == 0 ? "-" : track.toString()));
        if (!missing.isEmpty()) {
            msg().send(s, "track.skipped-missing", Messages.text("groups", Text.join(", ", missing)));
        }
    }

    void deleteTrackCommand(CommandSender s, String label, Args a) {
        if (!allowed(s, "deletetrack")) return;
        if (!a.has(0)) {
            usage(s, "/" + label + " deletetrack <name>");
            return;
        }
        deleteTrack(plugin, s, a.get(0));
    }

    static void deleteTrack(RexPermsPlugin plugin, CommandSender s, String name) {
        if (plugin.getTrackManager().get(name) == null) {
            plugin.getMessages().send(s, "track.not-found", Messages.text("track", name));
            return;
        }
        plugin.getService().deleteTrack(name, s);
        plugin.getMessages().send(s, "track.deleted", Messages.text("track", name.toLowerCase(Locale.ROOT)));
    }

    void listTracks(CommandSender s, String label) {
        if (!allowed(s, "listtracks")) return;
        List<Track> tracks = new ArrayList<Track>(plugin.getTrackManager().all());
        Collections.sort(tracks, new Comparator<Track>() {
            @Override
            public int compare(Track a, Track b) {
                return a.getName().compareTo(b.getName());
            }
        });
        if (tracks.isEmpty()) {
            msg().send(s, "track.list-empty", Messages.text("label", label));
            return;
        }
        msg().send(s, "track.list-header", Messages.text("count", tracks.size()));
        for (Track t : tracks) {
            Component line = msg().component("track.list-entry", Messages.text("track", t.getName()),
                    Messages.text("path", t.size() == 0 ? "-" : t.toString()));
            msg().send(s, clickable(line, "/" + label + " track " + t.getName() + " info", "Click for info"));
        }
    }

    // ------------------------------------------------------------------ search

    void search(final CommandSender s, final String label, Args a) {
        if (!allowed(s, "search")) return;
        if (!a.has(0)) {
            usage(s, "/" + label + " search <node> [page]   (end with * for a prefix search)");
            return;
        }
        final String query = a.lower(0);
        final int page = parsePage(a, 1);
        final PermissionHolder.NodeFilter filter;
        if (query.endsWith("*") && query.length() > 1) {
            final String prefix = query.substring(0, query.length() - 1);
            filter = n -> n.key().toLowerCase(Locale.ROOT).startsWith(prefix);
        } else {
            filter = n -> n.key().equalsIgnoreCase(query);
        }
        msg().send(s, "general.searching");
        plugin.getStorage().submit(st -> {
            List<HeldNode> out = new ArrayList<HeldNode>(st.searchGroupNodes(filter));
            out.addAll(st.searchUserNodes(filter));
            return out;
        }).thenAcceptAsync(results -> {
            if (results.isEmpty()) {
                msg().send(s, "search.none", Messages.text("query", query));
                return;
            }
            Collections.sort(results, new Comparator<HeldNode>() {
                @Override
                public int compare(HeldNode x, HeldNode y) {
                    int c = x.holderType().ordinal() - y.holderType().ordinal();
                    return c != 0 ? c : x.holderName().toLowerCase(Locale.ROOT)
                            .compareTo(y.holderName().toLowerCase(Locale.ROOT));
                }
            });
            paginate(s, results, page, "search.header", Messages.text("query", query), h -> {
                boolean user = h.holderType() == HolderType.USER;
                Component line = msg().component("search.entry",
                        Messages.text("type", user ? "user" : "group"),
                        Messages.text("name", h.holderName()),
                        Messages.text("node", h.node().key()),
                        Messages.comp("value", value(h.node().value())),
                        ctx(h.node().contexts()),
                        Messages.text("expiry", expiryText(h.node())));
                return clickable(line, "/" + label + " " + (user ? "user " : "group ") + h.holderName() + " info",
                        "Click for info");
            });
        }, plugin.mainThread()).exceptionally(t -> {
            msg().send(s, "general.storage-error");
            return null;
        });
    }

    // ------------------------------------------------------------------ verbose

    void verbose(CommandSender s, String label, Args a) {
        if (!allowed(s, "verbose")) return;
        String mode = a.has(0) ? a.lower(0) : (plugin.getVerboseHandler().isListening(s) ? "off" : "on");
        if (mode.equals("on")) {
            String filter = a.has(1) ? a.get(1) : "";
            plugin.getVerboseHandler().enable(s, filter);
            msg().send(s, "verbose.enabled", Messages.text("filter", filter.isEmpty() ? "everything" : filter),
                    Messages.text("label", label));
        } else if (mode.equals("off")) {
            plugin.getVerboseHandler().disable(s);
            msg().send(s, "verbose.disabled");
        } else {
            usage(s, "/" + label + " verbose <on|off> [player|permission-part]");
        }
    }

    // ------------------------------------------------------------------ log

    void log(final CommandSender s, String label, Args a) {
        if (!allowed(s, "log")) return;
        String target = null;
        int page = 1;
        if (a.has(0)) {
            Integer p = Args.parseInt(a.get(0));
            if (p != null) {
                page = p;
            } else {
                target = a.get(0);
                page = parsePage(a, 1);
            }
        }
        final String filter = target;
        final int finalPage = page;
        plugin.getStorage().submit(st -> st.recentActions(200, filter)).thenAcceptAsync(actions -> {
            if (actions.isEmpty()) {
                msg().send(s, "log.empty");
                return;
            }
            final long now = System.currentTimeMillis() / 1000L;
            CommandSupport.LineRenderer<LoggedAction> renderer = e -> msg().component("log.entry",
                    Messages.text("ago", Durations.formatShort(now - e.time())),
                    Messages.text("actor", e.actorName()),
                    Messages.text("type", e.targetTypeName()),
                    Messages.text("target", e.targetName()),
                    Messages.text("action", e.description()));
            paginate(s, actions, finalPage, "log.header",
                    Messages.text("target", filter == null ? "all" : filter), renderer);
        }, plugin.mainThread()).exceptionally(t -> {
            msg().send(s, "general.storage-error");
            return null;
        });
    }

    // ------------------------------------------------------------------ management

    void sync(final CommandSender s) {
        if (!allowed(s, "sync")) return;
        msg().send(s, "general.sync-started");
        plugin.getService().fullReload().thenRunAsync(() -> {
            plugin.getService().broadcastFullSync();
            msg().send(s, "general.sync-complete");
        }, plugin.mainThread()).exceptionally(t -> {
            msg().send(s, "general.storage-error");
            return null;
        });
    }

    void reload(CommandSender s) {
        if (!allowed(s, "reload")) return;
        plugin.reloadSettings();
        msg().send(s, "general.reloaded");
    }

    void exportData(final CommandSender s, String label, Args a) {
        if (!allowed(s, "export")) return;
        if (!(s instanceof ConsoleCommandSender) && !s.isOp()) {
            msg().send(s, "migration.console-or-op");
            return;
        }
        String name = a.has(0) ? a.get(0) : "rexperms-backup-" + (System.currentTimeMillis() / 1000L);
        msg().send(s, "migration.export-started");
        migration.export(name).thenAcceptAsync(file ->
                        msg().send(s, "migration.export-done", Messages.text("file", file.getName())),
                plugin.mainThread()).exceptionally(t -> {
            msg().send(s, "migration.failed", Messages.text("error", rootMessage(t)));
            return null;
        });
    }

    void importData(final CommandSender s, String label, Args a) {
        if (!allowed(s, "import")) return;
        if (!(s instanceof ConsoleCommandSender)) {
            msg().send(s, "migration.console-only");
            return;
        }
        if (!a.has(0)) {
            usage(s, "/" + label + " import <file.json.gz>   (a RexPerms or LuckPerms /lp export file)");
            return;
        }
        msg().send(s, "migration.import-started", Messages.text("file", a.get(0)));
        migration.importFile(a.get(0)).thenAcceptAsync(result ->
                        msg().send(s, "migration.import-done",
                                Messages.text("groups", result.groups()),
                                Messages.text("tracks", result.tracks()),
                                Messages.text("users", result.users()),
                                Messages.text("skipped", result.skipped())),
                plugin.mainThread()).exceptionally(t -> {
            msg().send(s, "migration.failed", Messages.text("error", rootMessage(t)));
            return null;
        });
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }
}
