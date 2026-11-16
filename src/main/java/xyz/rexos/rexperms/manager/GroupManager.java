package xyz.rexos.rexperms.manager;

import xyz.rexos.rexperms.calc.GroupLookup;
import xyz.rexos.rexperms.model.DataEpoch;
import xyz.rexos.rexperms.model.Group;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class GroupManager implements GroupLookup {

    private static final Comparator<Group> BY_WEIGHT_THEN_NAME =
            new Comparator<Group>() {
                @Override
                public int compare(Group a, Group b) {
                    int c = Integer.compare(b.getWeight(), a.getWeight());
                    return c != 0 ? c : a.getName().compareTo(b.getName());
                }
            };

    private final xyz.rexos.rexperms.api.mutation.MutationCoordinator.Cell<
                    java.util.Map<String, Group>>
            state;

    private java.util.Map<String, Group> groups() {
        return state.get();
    }

    private <T> T edit(java.util.function.Function<java.util.Map<String, Group>, T> action) {
        final Object[] result = {null};
        state.update(
                old -> {
                    java.util.Map<String, Group> next =
                            new java.util.LinkedHashMap<String, Group>(old);
                    result[0] = action.apply(next);
                    return java.util.Collections.unmodifiableMap(next);
                });
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }

    private final DataEpoch epoch;

    public GroupManager(DataEpoch epoch) {
        this.epoch = epoch;
        state =
                new xyz.rexos.rexperms.api.mutation.MutationCoordinator.Cell<
                        java.util.Map<String, Group>>(
                        epoch.coordinator(),
                        "GROUP_REGISTRY",
                        "*",
                        java.util.Collections.<String, Group>emptyMap(),
                        epoch::bump);
    }

    @Override
    public Group get(String name) {
        return name == null ? null : groups().get(name.toLowerCase(Locale.ROOT));
    }

    public boolean exists(String name) {
        return get(name) != null;
    }

    /** Returns the group, creating an empty one if it does not exist. */
    public Group getOrCreate(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Group existing = groups().get(key);
        if (existing != null) return existing;
        Group created = new Group(key, epoch);
        Group raced = edit(map -> map.putIfAbsent(key, created));
        if (raced != null) return raced;
        return created;
    }

    public Group unregister(String name) {
        Group removed = edit(map -> map.remove(name.toLowerCase(Locale.ROOT)));
        return removed;
    }

    public Collection<Group> all() {
        return java.util.Collections.unmodifiableCollection(groups().values());
    }

    /** Sorted by weight (descending), then by name. */
    public List<Group> sorted() {
        List<Group> list = new ArrayList<Group>(groups().values());
        Collections.sort(list, BY_WEIGHT_THEN_NAME);
        return list;
    }

    public List<String> names() {
        List<String> out = new ArrayList<String>();
        for (Group g : sorted()) out.add(g.getName());
        return out;
    }

    /** Used during a full reload from storage. */
    public void retainOnly(Collection<String> names) {
        edit(map -> map.keySet().retainAll(names));
    }
}
