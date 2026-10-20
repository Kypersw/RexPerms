package xyz.rexos.rexperms.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import xyz.rexos.rexperms.RexPermsPlugin;
import xyz.rexos.rexperms.calc.MetaData;
import xyz.rexos.rexperms.config.Messages;
import xyz.rexos.rexperms.model.Group;
import xyz.rexos.rexperms.model.HolderType;
import xyz.rexos.rexperms.model.Node;
import xyz.rexos.rexperms.model.NodeType;
import xyz.rexos.rexperms.model.Track;
import xyz.rexos.rexperms.model.User;
import xyz.rexos.rexperms.storage.HeldNode;
import xyz.rexos.rexperms.util.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** /rp group &lt;group&gt; ... */
final class GroupCommand extends CommandSupport {

    static final List<String> SUBS = Collections.unmodifiableList(Arrays.asList(
            "info", "permission", "parent", "meta", "setweight", "setdisplayname",
            "listmembers", "showtracks", "rename", "clone", "delete"));

    private final HolderCommands holderCommands;

    GroupCommand(RexPermsPlugin plugin, HolderCommands holderCommands) {
        super(plugin);
        this.holderCommands = holderCommands;
    }

    void execute(final CommandSender s, String label, Args a) {
        if (!a.has(0)) {
            usage(s, "/" + label + " group <group> <" + Text.join("|", SUBS) + ">");
            return;
        }
        final Group group = plugin.getGroupManager().get(a.get(0));
        if (group == null) {
            msg().send(s, "group.not-found", Messages.text("group", a.get(0)));
            return;
        }
        String base = "/" + label + " group " + group.getName();
        Args rest = a.from(1);
        if (holderCommands.handle(s, group, base, rest)) return;

        String sub = rest.has(0) ? rest.lower(0) : "info";
        Args args = rest.from(1);
        if (sub.equals("info")) {
            if (allowed(s, "group.info")) info(s, group);
        } else if (sub.equals("setweight")) {
            if (!allowed(s, "group.setweight")) return;
            Integer w = args.has(0) ? Args.parseInt(args.get(0)) : null;
            if (w == null) {
                usage(s, base + " setweight <number>");
                return;
            }
            group.removeIf(n -> n.type() == NodeType.WEIGHT);
            group.setNode(Node.weight(w));
            msg().send(s, "group.weight-set", Messages.text("group", group.getName()), Messages.text("weight", w));
            plugin.getService().commit(group, s, "setweight " + w);
        } else if (sub.equals("setdisplayname")) {
            if (!allowed(s, "group.setdisplayname")) return;
            if (!args.has(0)) {
                usage(s, base + " setdisplayname <name>");
                return;
            }
            String name = args.get(0);
            group.removeIf(n -> n.type() == NodeType.DISPLAY_NAME);
            if (!name.equalsIgnoreCase(group.getName()) && !name.equals("-")) {
                group.setNode(Node.displayName(name));
            }
            msg().send(s, "group.displayname-set", Messages.text("group", group.getName()),
                    Messages.comp("display", Text.colorize(group.getPlainDisplayName())));
            plugin.getService().commit(group, s, "setdisplayname " + name);
        } else if (sub.equals("listmembers")) {
            if (allowed(s, "group.listmembers")) listMembers(s, group, parsePage(args, 0));
        } else if (sub.equals("showtracks")) {
            if (!allowed(s, "group.showtracks")) return;
            List<Track> tracks = plugin.getTrackManager().containing(group.getName());
            if (tracks.isEmpty()) {
                msg().send(s, "group.showtracks-none", Messages.text("group", group.getName()));
                return;
            }
            msg().send(s, "group.showtracks-header", Messages.text("group", group.getName()));
            for (Track t : tracks) {
                msg().sendPlain(s, "group.showtracks-entry", Messages.text("track", t.getName()),
                        Messages.text("path", t.toString()));
            }
        } else if (sub.equals("rename") || sub.equals("clone")) {
            if (!allowed(s, "group." + sub)) return;
            if (!args.has(0)) {
                usage(s, base + " " + sub + " <new-name>");
                return;
            }
            final String newName = args.lower(0);
            if (!checkNewName(s, newName)) return;
            final String oldName = group.getName();
            if (sub.equals("rename")) {
                if (oldName.equals("default")) {
                    msg().send(s, "group.cannot-rename-default");
                    return;
                }
                plugin.getService().renameGroup(group, newName, s).thenAcceptAsync(updated ->
                        msg().send(s, "group.renamed", Messages.text("old", oldName),
                                Messages.text("new", newName), Messages.text("count", updated)), plugin.mainThread());
            } else {
                plugin.getService().cloneGroup(group, newName, s).thenAcceptAsync(g ->
                        msg().send(s, "group.cloned", Messages.text("old", oldName),
                                Messages.text("new", newName)), plugin.mainThread());
            }
        } else if (sub.equals("delete")) {
            if (allowed(s, "deletegroup")) MiscCommands.deleteGroup(plugin, s, group.getName());
        } else {
            usage(s, base + " <" + Text.join("|", SUBS) + ">");
        }
    }

    private boolean checkNewName(CommandSender s, String name) {
        if (!Group.isValidName(name)) {
            msg().send(s, "group.invalid-name", Messages.text("group", name));
            return false;
        }
        if (plugin.getGroupManager().exists(name)) {
            msg().send(s, "group.already-exists", Messages.text("group", name));
            return false;
        }
        return true;
    }

    private void info(CommandSender s, Group group) {
        MetaData meta = plugin.getCalculator().calculateMeta(group, plugin.getContextManager().staticContext());
        List<String> parents = new ArrayList<String>();
        for (Node n : group.getParentNodes()) {
            parents.add(n.groupName() + Text.contextSuffix(n.contexts()) + expiryText(n));
        }
        List<String> tracks = new ArrayList<String>();
        for (Track t : plugin.getTrackManager().containing(group.getName())) tracks.add(t.getName());

        msg().sendList(s, "group.info",
                Messages.text("group", group.getName()),
                Messages.comp("display", Text.colorize(group.getPlainDisplayName())),
                Messages.text("weight", group.getWeight()),
                Messages.text("parents", parents.isEmpty() ? "-" : Text.join(", ", parents)),
                Messages.comp("prefix", meta.prefix() == null ? Component.text("-", NamedTextColor.DARK_GRAY)
                        : Text.colorize(meta.prefix())),
                Messages.comp("suffix", meta.suffix() == null ? Component.text("-", NamedTextColor.DARK_GRAY)
                        : Text.colorize(meta.suffix())),
                Messages.text("permissions", group.getNodes(NodeType.PERMISSION).size()),
                Messages.text("tracks", tracks.isEmpty() ? "-" : Text.join(", ", tracks)));
    }

    private void listMembers(final CommandSender s, final Group group, final int page) {
        final String key = "group." + group.getName();
        msg().send(s, "general.searching");
        plugin.getStorage().submit(st -> {
            List<HeldNode> found = new ArrayList<HeldNode>(st.searchUserNodes(n -> n.key().equals(key)));
            found.addAll(st.searchGroupNodes(n -> n.key().equals(key)));
            return found;
        }).thenAcceptAsync(found -> {
            // Include online users whose latest change might not be saved yet
            for (User u : plugin.getUserManager().all()) {
                boolean listed = false;
                for (HeldNode h : found) if (h.holderId().equals(u.getIdentifier())) listed = true;
                if (listed) continue;
                for (Node n : u.getParentNodes()) {
                    if (n.key().equals(key)) found.add(new HeldNode(u.getType(), u.getIdentifier(), u.getFriendlyName(), n));
                }
            }
            if (found.isEmpty()) {
                msg().send(s, "group.listmembers-none", Messages.text("group", group.getName()));
                return;
            }
            Collections.sort(found, new Comparator<HeldNode>() {
                @Override
                public int compare(HeldNode a, HeldNode b) {
                    int c = a.holderType().ordinal() - b.holderType().ordinal();
                    return c != 0 ? c : a.holderName().toLowerCase(Locale.ROOT)
                            .compareTo(b.holderName().toLowerCase(Locale.ROOT));
                }
            });
            paginate(s, found, page, "group.listmembers-header", Messages.text("group", group.getName()), h ->
                    msg().component("group.listmembers-entry",
                            Messages.text("type", h.holderType() == HolderType.USER ? "user" : "group"),
                            Messages.text("name", h.holderName()),
                            ctx(h.node().contexts()),
                            Messages.text("expiry", expiryText(h.node()))));
        }, plugin.mainThread()).exceptionally(t -> {
            msg().send(s, "general.storage-error");
            return null;
        });
    }
}
