package xyz.rexos.rexperms.command;

import org.bukkit.command.CommandSender;
import xyz.rexos.rexperms.RexPermsPlugin;
import xyz.rexos.rexperms.config.Messages;
import xyz.rexos.rexperms.model.Group;
import xyz.rexos.rexperms.model.Track;
import xyz.rexos.rexperms.util.Text;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** /rp track &lt;track&gt; ... */
final class TrackCommand extends CommandSupport {

    static final List<String> SUBS = Collections.unmodifiableList(Arrays.asList(
            "info", "append", "insert", "remove", "clear", "rename", "clone", "delete"));

    TrackCommand(RexPermsPlugin plugin) {
        super(plugin);
    }

    void execute(CommandSender s, String label, Args a) {
        if (!a.has(0)) {
            usage(s, "/" + label + " track <track> <" + Text.join("|", SUBS) + ">");
            return;
        }
        Track track = plugin.getTrackManager().get(a.get(0));
        if (track == null) {
            msg().send(s, "track.not-found", Messages.text("track", a.get(0)));
            return;
        }
        String base = "/" + label + " track " + track.getName();
        String sub = a.has(1) ? a.lower(1) : "info";
        Args args = a.from(2);
        if (!allowed(s, "track." + sub)) return;

        if (sub.equals("info")) {
            msg().send(s, "track.info-header", Messages.text("track", track.getName()),
                    Messages.text("count", track.size()));
            List<String> groups = track.getGroups();
            if (groups.isEmpty()) {
                msg().sendPlain(s, "track.info-empty");
                return;
            }
            for (int i = 0; i < groups.size(); i++) {
                String g = groups.get(i);
                msg().sendPlain(s, "track.info-entry", Messages.text("index", i + 1), Messages.text("group", g),
                        Messages.text("missing", plugin.getGroupManager().exists(g) ? "" : " (group missing!)"));
            }
        } else if (sub.equals("append") || sub.equals("insert")) {
            boolean insert = sub.equals("insert");
            if (!args.has(insert ? 1 : 0)) {
                usage(s, base + " " + sub + " <group>" + (insert ? " <position>" : ""));
                return;
            }
            Group group = plugin.getGroupManager().get(args.get(0));
            if (group == null) {
                msg().send(s, "group.not-found", Messages.text("group", args.get(0)));
                return;
            }
            boolean ok;
            if (insert) {
                Integer pos = Args.parseInt(args.get(1));
                if (pos == null || pos < 1) {
                    msg().send(s, "general.invalid-number", Messages.text("input", args.get(1)));
                    return;
                }
                ok = track.insert(group.getName(), pos);
            } else {
                ok = track.append(group.getName());
            }
            if (!ok) {
                msg().send(s, "track.already-contains", Messages.text("track", track.getName()),
                        Messages.text("group", group.getName()));
                return;
            }
            msg().send(s, "track.group-added", Messages.text("track", track.getName()),
                    Messages.text("group", group.getName()), Messages.text("path", track.toString()));
            plugin.getService().commitTrack(track, s, sub + " " + group.getName());
        } else if (sub.equals("remove")) {
            if (!args.has(0)) {
                usage(s, base + " remove <group>");
                return;
            }
            if (!track.remove(args.get(0))) {
                msg().send(s, "track.not-contains", Messages.text("track", track.getName()),
                        Messages.text("group", args.get(0)));
                return;
            }
            msg().send(s, "track.group-removed", Messages.text("track", track.getName()),
                    Messages.text("group", args.lower(0)), Messages.text("path", track.toString()));
            plugin.getService().commitTrack(track, s, "remove " + args.lower(0));
        } else if (sub.equals("clear")) {
            track.clear();
            msg().send(s, "track.cleared", Messages.text("track", track.getName()));
            plugin.getService().commitTrack(track, s, "clear");
        } else if (sub.equals("rename") || sub.equals("clone")) {
            if (!args.has(0)) {
                usage(s, base + " " + sub + " <new-name>");
                return;
            }
            String newName = args.lower(0);
            if (!Group.isValidName(newName)) {
                msg().send(s, "track.invalid-name", Messages.text("track", newName));
                return;
            }
            if (plugin.getTrackManager().get(newName) != null) {
                msg().send(s, "track.already-exists", Messages.text("track", newName));
                return;
            }
            Track copy = plugin.getTrackManager().getOrCreate(newName);
            copy.setGroups(track.getGroups());
            boolean rename = sub.equals("rename");
            plugin.getService().commitTrack(copy, s, (rename ? "renamed from " : "cloned from ") + track.getName());
            if (rename) {
                plugin.getService().deleteTrack(track.getName(), s);
                msg().send(s, "track.renamed", Messages.text("old", track.getName()), Messages.text("new", newName));
            } else {
                msg().send(s, "track.cloned", Messages.text("old", track.getName()), Messages.text("new", newName));
            }
        } else if (sub.equals("delete")) {
            MiscCommands.deleteTrack(plugin, s, track.getName());
        } else {
            usage(s, base + " <" + Text.join("|", SUBS) + ">");
        }
    }
}
