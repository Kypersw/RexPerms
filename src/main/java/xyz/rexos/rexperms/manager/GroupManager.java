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
import java.util.concurrent.ConcurrentHashMap;

public final class GroupManager implements GroupLookup {

    private static final Comparator<Group> BY_WEIGHT_THEN_NAME = new Comparator<Group>() {
        @Override
        public int compare(Group a, Group b) {
            int c = Integer.compare(b.getWeight(), a.getWeight());
            return c != 0 ? c : a.getName().compareTo(b.getName());
        }
    };

    private final ConcurrentHashMap<String, Group> groups = new ConcurrentHashMap<String, Group>();
    private final DataEpoch epoch;

    public GroupManager(DataEpoch epoch) {
        this.epoch = epoch;
    }

    @Override
    public Group get(String name) {
        return name == null ? null : groups.get(name.toLowerCase(Locale.ROOT));
    }

    public boolean exists(String name) {
        return get(name) != null;
    }

    /** Returns the group, creating an empty one if it does not exist. */
    public Group getOrCreate(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Group existing = groups.get(key);
        if (existing != null) return existing;
        Group created = new Group(key, epoch);
        Group raced = groups.putIfAbsent(key, created);
        if (raced != null) return raced;
        epoch.bump();
        return created;
    }

    public Group unregister(String name) {
        Group removed = groups.remove(name.toLowerCase(Locale.ROOT));
        if (removed != null) epoch.bump();
        return removed;
    }

    public Collection<Group> all() {
        return groups.values();
    }

    /** Sorted by weight (descending), then by name. */
    public List<Group> sorted() {
        List<Group> list = new ArrayList<Group>(groups.values());
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
        groups.keySet().retainAll(names);
        epoch.bump();
    }
}
