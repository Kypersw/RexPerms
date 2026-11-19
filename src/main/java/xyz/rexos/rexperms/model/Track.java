package xyz.rexos.rexperms.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * An ordered list of groups used for promotion and demotion. Example "staff" track: helper -> mod
 * -> admin -> owner
 */
public final class Track {

    private final String name;
    private final xyz.rexos.rexperms.api.mutation.MutationCoordinator.Cell<List<String>> state;

    private List<String> groups() {
        return state.get();
    }

    private <T> T edit(java.util.function.Function<List<String>, T> action) {
        final Object[] result = {null};
        state.update(
                old -> {
                    List<String> next = new ArrayList<String>(old);
                    result[0] = action.apply(next);
                    return Collections.unmodifiableList(next);
                });
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }

    public Track(String name) {
        this(name, null);
    }

    public Track(String name, xyz.rexos.rexperms.api.mutation.MutationCoordinator coordinator) {
        this.name = name.toLowerCase(Locale.ROOT);
        this.state =
                new xyz.rexos.rexperms.api.mutation.MutationCoordinator.Cell<List<String>>(
                        coordinator, "TRACK", this.name, Collections.<String>emptyList(), () -> {});
    }

    public String getName() {
        return name;
    }

    public synchronized List<String> getGroups() {
        return Collections.unmodifiableList(new ArrayList<String>(groups()));
    }

    public synchronized int size() {
        return groups().size();
    }

    public synchronized boolean contains(String group) {
        return groups().contains(group.toLowerCase(Locale.ROOT));
    }

    public synchronized int indexOf(String group) {
        return groups().indexOf(group.toLowerCase(Locale.ROOT));
    }

    /** The group after the given one, or null if it is the last. */
    public synchronized String next(String group) {
        int i = groups().indexOf(group.toLowerCase(Locale.ROOT));
        if (i < 0) throw new IllegalArgumentException(group + " is not on this track");
        return i + 1 < groups().size() ? groups().get(i + 1) : null;
    }

    /** The group before the given one, or null if it is the first. */
    public synchronized String previous(String group) {
        int i = groups().indexOf(group.toLowerCase(Locale.ROOT));
        if (i < 0) throw new IllegalArgumentException(group + " is not on this track");
        return i > 0 ? groups().get(i - 1) : null;
    }

    public synchronized boolean append(String group) {
        return edit(
                groups -> {
                    String g = group.toLowerCase(Locale.ROOT);
                    if (groups.contains(g)) return false;
                    groups.add(g);
                    return true;
                });
    }

    public synchronized boolean insert(String group, int position) {
        return edit(
                groups -> {
                    String g = group.toLowerCase(Locale.ROOT);
                    if (groups.contains(g)) return false;
                    groups.add(Math.max(0, Math.min(groups.size(), position - 1)), g);
                    return true;
                });
    }

    public synchronized boolean remove(String group) {
        return edit(groups -> groups.remove(group.toLowerCase(Locale.ROOT)));
    }

    public synchronized void clear() {
        edit(
                groups -> {
                    groups.clear();
                    return null;
                });
    }

    public synchronized void setGroups(List<String> list) {
        edit(
                groups -> {
                    groups.clear();
                    for (String g : list) {
                        String lower = g.toLowerCase(Locale.ROOT);
                        if (!groups.contains(lower)) groups.add(lower);
                    }
                    return null;
                });
    }

    @Override
    public synchronized String toString() {
        StringBuilder sb = new StringBuilder();
        for (String g : groups()) {
            if (sb.length() > 0) sb.append(" → ");
            sb.append(g);
        }
        return sb.toString();
    }
}
