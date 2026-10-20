package xyz.rexos.rexperms.manager;

import xyz.rexos.rexperms.model.Track;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public final class TrackManager {

    private final ConcurrentHashMap<String, Track> tracks = new ConcurrentHashMap<String, Track>();

    public Track get(String name) {
        return name == null ? null : tracks.get(name.toLowerCase(Locale.ROOT));
    }

    public Track getOrCreate(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Track existing = tracks.get(key);
        if (existing != null) return existing;
        Track created = new Track(key);
        Track raced = tracks.putIfAbsent(key, created);
        return raced != null ? raced : created;
    }

    public Track unregister(String name) {
        return tracks.remove(name.toLowerCase(Locale.ROOT));
    }

    public Collection<Track> all() {
        return tracks.values();
    }

    public List<String> names() {
        List<String> out = new ArrayList<String>(tracks.keySet());
        Collections.sort(out);
        return out;
    }

    /** Tracks that contain the given group. */
    public List<Track> containing(String group) {
        List<Track> out = new ArrayList<Track>();
        for (Track t : tracks.values()) {
            if (t.contains(group)) out.add(t);
        }
        return out;
    }

    public void retainOnly(Collection<String> names) {
        tracks.keySet().retainAll(names);
    }
}
