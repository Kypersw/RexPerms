package xyz.rexos.rexperms.command;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import xyz.rexos.rexperms.RexPermsPlugin;
import xyz.rexos.rexperms.config.Messages;
import xyz.rexos.rexperms.model.ContextSet;
import xyz.rexos.rexperms.model.Track;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** /rexperms (/rp) entry point and tab completion. */
public final class RootCommand implements TabExecutor {

    private static final List<String> ROOT_SUBS = Collections.unmodifiableList(Arrays.asList(
            "help", "info", "user", "group", "track", "creategroup", "deletegroup", "listgroups",
            "createtrack", "deletetrack", "listtracks", "search", "check", "verbose", "log", "sync",
            "reload", "export", "import"));

    private static final List<String> DURATIONS = Collections.unmodifiableList(Arrays.asList(
            "30m", "1h", "12h", "1d", "3d", "7d", "14d", "30d", "90d", "1y"));

    private static final List<String> MERGE = Collections.unmodifiableList(Arrays.asList(
            "accumulate", "replace", "deny"));

    private final RexPermsPlugin plugin;
    private final UserCommand userCommand;
    private final GroupCommand groupCommand;
    private final TrackCommand trackCommand;
    private final MiscCommands misc;

    public RootCommand(RexPermsPlugin plugin) {
        this.plugin = plugin;
        HolderCommands holderCommands = new HolderCommands(plugin);
        this.userCommand = new UserCommand(plugin, holderCommands);
        this.groupCommand = new GroupCommand(plugin, holderCommands);
        this.trackCommand = new TrackCommand(plugin);
        this.misc = new MiscCommands(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] rawArgs) {
        // Players with no RexPerms permission at all do not even learn the sub-commands
        if (!(sender instanceof ConsoleCommandSender) && !hasAnyAccess(sender)) {
            plugin.getMessages().send(sender, "general.no-access");
            return true;
        }
        Args args = Args.parse(rawArgs);
        if (!args.has(0)) {
            misc.help(sender, label);
            return true;
        }
        Args rest = args.from(1);
        String sub = args.lower(0);
        if (sub.equals("help") || sub.equals("?")) {
            misc.help(sender, label);
        } else if (sub.equals("info")) {
            misc.info(sender);
        } else if (sub.equals("user") || sub.equals("u")) {
            userCommand.execute(sender, label, rest);
        } else if (sub.equals("group") || sub.equals("g")) {
            groupCommand.execute(sender, label, rest);
        } else if (sub.equals("track") || sub.equals("t")) {
            trackCommand.execute(sender, label, rest);
        } else if (sub.equals("creategroup")) {
            misc.createGroup(sender, label, rest);
        } else if (sub.equals("deletegroup")) {
            misc.deleteGroupCommand(sender, label, rest);
        } else if (sub.equals("listgroups") || sub.equals("groups")) {
            misc.listGroups(sender, label, rest);
        } else if (sub.equals("createtrack")) {
            misc.createTrack(sender, label, rest);
        } else if (sub.equals("deletetrack")) {
            misc.deleteTrackCommand(sender, label, rest);
        } else if (sub.equals("listtracks") || sub.equals("tracks")) {
            misc.listTracks(sender, label);
        } else if (sub.equals("search")) {
            misc.search(sender, label, rest);
        } else if (sub.equals("check")) {
            // /rp check <player> <permission>  ==  /rp user <player> permission check <permission>
            if (rest.size() < 2) {
                plugin.getMessages().send(sender, "general.usage",
                        Messages.text("usage", "/" + label + " check <player> <permission>"));
                return true;
            }
            userCommand.execute(sender, label,
                    Args.parse(new String[]{rest.get(0), "permission", "check", rest.get(1)}));
        } else if (sub.equals("verbose")) {
            misc.verbose(sender, label, rest);
        } else if (sub.equals("log")) {
            misc.log(sender, label, rest);
        } else if (sub.equals("sync")) {
            misc.sync(sender);
        } else if (sub.equals("reload")) {
            misc.reload(sender);
        } else if (sub.equals("export")) {
            misc.exportData(sender, label, rest);
        } else if (sub.equals("import")) {
            misc.importData(sender, label, rest);
        } else {
            plugin.getMessages().send(sender, "general.unknown-command", Messages.text("label", label));
        }
        return true;
    }

    private boolean hasAnyAccess(CommandSender sender) {
        if (sender.isOp() || sender.hasPermission("rexperms.*")) return true;
        for (String sub : ROOT_SUBS) {
            if (sender.hasPermission("rexperms." + sub)) return true;
        }
        for (String area : Arrays.asList("user", "group", "track")) {
            if (sender.hasPermission("rexperms." + area + ".*") || sender.hasPermission("rexperms." + area + ".info")) {
                return true;
            }
        }
        return false;
    }

    // ================================================================== tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !hasAnyAccess(sender)) return Collections.emptyList();
        String last = args.length == 0 ? "" : args[args.length - 1];
        return filter(suggestions(args), last);
    }

    private List<String> suggestions(String[] a) {
        int n = a.length;
        if (n <= 1) return ROOT_SUBS;
        String root = a[0].toLowerCase(Locale.ROOT);
        if (root.equals("user") || root.equals("u")) {
            if (n == 2) return onlineNames();
            if (n == 3) return UserCommand.SUBS;
            String sub = a[2].toLowerCase(Locale.ROOT);
            if (sub.equals("promote") || sub.equals("demote")) {
                return n == 4 ? plugin.getTrackManager().names() : contextSuggestions(a[n - 1]);
            }
            return holderSuggestions(a, 3);
        }
        if (root.equals("group") || root.equals("g")) {
            if (n == 2) return plugin.getGroupManager().names();
            if (n == 3) return GroupCommand.SUBS;
            return holderSuggestions(a, 3);
        }
        if (root.equals("track") || root.equals("t")) {
            if (n == 2) return plugin.getTrackManager().names();
            if (n == 3) return TrackCommand.SUBS;
            String sub = a[2].toLowerCase(Locale.ROOT);
            Track t = plugin.getTrackManager().get(a[1]);
            if (n == 4 && (sub.equals("append") || sub.equals("insert"))) {
                List<String> out = new ArrayList<String>(plugin.getGroupManager().names());
                if (t != null) out.removeAll(t.getGroups());
                return out;
            }
            if (n == 4 && sub.equals("remove")) {
                return t == null ? Collections.<String>emptyList() : t.getGroups();
            }
            return Collections.emptyList();
        }
        if (root.equals("deletegroup")) return n == 2 ? plugin.getGroupManager().names() : Collections.<String>emptyList();
        if (root.equals("createtrack")) return n >= 3 ? plugin.getGroupManager().names() : Collections.<String>emptyList();
        if (root.equals("deletetrack")) return n == 2 ? plugin.getTrackManager().names() : Collections.<String>emptyList();
        if (root.equals("check")) {
            if (n == 2) return onlineNames();
            if (n == 3) return HolderCommands.knownPermissions(a[2]);
            return Collections.emptyList();
        }
        if (root.equals("search")) return n == 2 ? HolderCommands.knownPermissions(a[1]) : Collections.<String>emptyList();
        if (root.equals("verbose")) {
            if (n == 2) return Arrays.asList("on", "off");
            if (n == 3) return onlineNames();
            return Collections.emptyList();
        }
        if (root.equals("log") && n == 2) {
            List<String> out = new ArrayList<String>(onlineNames());
            out.addAll(plugin.getGroupManager().names());
            return out;
        }
        if (root.equals("import") && n == 2) return importFiles();
        return Collections.emptyList();
    }

    /** a[start-1] = permission|parent|meta|setweight, a[start] = sub-command, then arguments. */
    private List<String> holderSuggestions(String[] a, int start) {
        int n = a.length;
        String area = a[start - 1].toLowerCase(Locale.ROOT);
        int pos = n - start; // 1 = completing the sub-command
        String current = a[n - 1];

        if (current.contains("=")) return contextSuggestions(current);
        String sub = pos >= 2 ? a[start].toLowerCase(Locale.ROOT) : "";

        if (area.equals("permission") || area.equals("perm") || area.equals("permissions")) {
            if (pos == 1) return HolderCommands.PERMISSION_SUBS;
            if (pos == 2 && !sub.equals("info") && !sub.equals("clear")) return HolderCommands.knownPermissions(current);
            if (pos == 3 && (sub.equals("set") || sub.equals("settemp"))) return Arrays.asList("true", "false");
            if (pos == 4 && sub.equals("settemp")) return DURATIONS;
            if (pos == 5 && sub.equals("settemp")) return MERGE;
            return contextSuggestions(current);
        }
        if (area.equals("parent") || area.equals("parents")) {
            if (pos == 1) return HolderCommands.PARENT_SUBS;
            if (pos == 2 && !sub.equals("info") && !sub.equals("clear")) return plugin.getGroupManager().names();
            if (pos == 3 && sub.equals("addtemp")) return DURATIONS;
            if (pos == 4 && sub.equals("addtemp")) return MERGE;
            return contextSuggestions(current);
        }
        if (area.equals("meta") || area.equals("chat")) {
            if (pos == 1) return HolderCommands.META_SUBS;
            if (pos == 2 && (sub.contains("prefix") || sub.contains("suffix"))) return Arrays.asList("100", "50", "10");
            if (pos == 4 && sub.startsWith("addtemp")) return DURATIONS;
            return contextSuggestions(current);
        }
        if (area.equals("setweight")) {
            return pos == 1 ? Arrays.asList("10", "50", "100", "500", "1000") : Collections.<String>emptyList();
        }
        return Collections.emptyList();
    }

    private List<String> contextSuggestions(String current) {
        List<String> out = new ArrayList<String>();
        int eq = current.indexOf('=');
        if (eq < 0) {
            for (String k : plugin.getContextManager().knownKeys()) out.add(k + "=");
            return out;
        }
        String key = current.substring(0, eq).toLowerCase(Locale.ROOT);
        if (key.equals("world")) {
            for (World w : Bukkit.getWorlds()) out.add("world=" + w.getName().toLowerCase(Locale.ROOT));
        } else if (key.equals("server")) {
            ContextSet st = plugin.getContextManager().staticContext();
            out.add("server=" + (st.get("server") != null ? st.get("server") : "lobby"));
        } else if (key.equals("gamemode")) {
            for (String g : Arrays.asList("survival", "creative", "adventure", "spectator")) out.add("gamemode=" + g);
        } else if (key.equals("dimension")) {
            for (String d : Arrays.asList("overworld", "the_nether", "the_end")) out.add("dimension=" + d);
        }
        return out;
    }

    private List<String> onlineNames() {
        List<String> out = new ArrayList<String>();
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        return out;
    }

    private List<String> importFiles() {
        List<String> out = new ArrayList<String>();
        File[] files = plugin.getDataFolder().listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".json.gz") || f.getName().endsWith(".json")) out.add(f.getName());
            }
        }
        return out;
    }

    private static List<String> filter(Collection<String> options, String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<String>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(t)) out.add(o);
        }
        return out;
    }
}
