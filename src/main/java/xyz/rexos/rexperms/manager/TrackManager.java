package xyz.rexos.rexperms.manager;

import xyz.rexos.rexperms.model.Track;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class TrackManager {

    private final xyz.rexos.rexperms.api.mutation.MutationCoordinator.Cell<
                    java.util.Map<String, Track>>
            state;

    private java.util.Map<String, Track> tracks() {
        return state.get();
    }

    private <T> T edit(java.util.function.Function<java.util.Map<String, Track>, T> action) {
        final Object[] result = {null};
        state.update(
                old -> {
                    java.util.Map<String, Track> next =
                            new java.util.LinkedHashMap<String, Track>(old);
                    result[0] = action.apply(next);
                    return java.util.Collections.unmodifiableMap(next);
                });
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }

    private final xyz.rexos.rexperms.api.mutation.MutationCoordinator coordinator;

    public TrackManager() {
        this(null);
    }

    public TrackManager(xyz.rexos.rexperms.api.mutation.MutationCoordinator coordinator) {
        this.coordinator = coordinator;
        state =
                new xyz.rexos.rexperms.api.mutation.MutationCoordinator.Cell<
                        java.util.Map<String, Track>>(
                        coordinator,
                        "TRACK_REGISTRY",
                        "*",
                        java.util.Collections.<String, Track>emptyMap(),
                        () -> {});
    }

    public Track get(String name) {
        return name == null ? null : tracks().get(name.toLowerCase(Locale.ROOT));
    }

    public Track getOrCreate(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Track existing = tracks().get(key);
        if (existing != null) return existing;
        Track created = new Track(key, coordinator);
        Track raced = edit(map -> map.putIfAbsent(key, created));
        return raced != null ? raced : created;
    }

    public Track unregister(String name) {
        return edit(map -> map.remove(name.toLowerCase(Locale.ROOT)));
    }

    public Collection<Track> all() {
        return java.util.Collections.unmodifiableCollection(tracks().values());
    }

    public List<String> names() {
        List<String> out = new ArrayList<String>(tracks().keySet());
        Collections.sort(out);
        return out;
    }

    /** Tracks that contain the given group. */
    public List<Track> containing(String group) {
        List<Track> out = new ArrayList<Track>();
        for (Track t : tracks().values()) {
            if (t.contains(group)) out.add(t);
        }
        return out;
    }

    public void retainOnly(Collection<String> names) {
        edit(map -> map.keySet().retainAll(names));
    }
}
