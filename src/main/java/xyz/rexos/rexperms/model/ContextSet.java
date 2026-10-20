package xyz.rexos.rexperms.model;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable set of context pairs. Describes either where a node applies
 * (world=survival, server=lobby) or the current state of a player.
 *
 * Keys and values are stored lower-case so "World=Survival" and
 * "world=survival" are treated as equal.
 */
public final class ContextSet {

    public static final ContextSet EMPTY = new ContextSet(Collections.<String, String>emptyMap());

    private final Map<String, String> entries;
    private final int hash;

    private ContextSet(Map<String, String> entries) {
        this.entries = entries;
        this.hash = entries.hashCode();
    }

    public static ContextSet of(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return EMPTY;
        }
        TreeMap<String, String> sorted = new TreeMap<String, String>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (k != null && v != null && !k.trim().isEmpty() && !v.trim().isEmpty()) {
                sorted.put(k.trim().toLowerCase(Locale.ROOT), v.trim().toLowerCase(Locale.ROOT));
            }
        }
        return sorted.isEmpty() ? EMPTY : new ContextSet(Collections.unmodifiableMap(sorted));
    }

    public static ContextSet of(String key, String value) {
        return of(Collections.singletonMap(key, value));
    }

    /**
     * Parses "world=survival,server=lobby". Blank input or "global" yields {@link #EMPTY}.
     */
    public static ContextSet parse(String serialized) {
        if (serialized == null) return EMPTY;
        String s = serialized.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("global")) return EMPTY;
        TreeMap<String, String> map = new TreeMap<String, String>();
        for (String part : s.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0 || eq == part.length() - 1) {
                throw new IllegalArgumentException("Invalid context: '" + part + "' (expected key=value)");
            }
            map.put(part.substring(0, eq), part.substring(eq + 1));
        }
        return of(map);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public String get(String key) {
        return entries.get(key.toLowerCase(Locale.ROOT));
    }

    public Map<String, String> asMap() {
        return entries;
    }

    public ContextSet with(String key, String value) {
        TreeMap<String, String> copy = new TreeMap<String, String>(entries);
        copy.put(key, value);
        return of(copy);
    }

    /**
     * Is this set (a node's contexts) satisfied by the given current contexts?
     * Every key in the node must be present in the current set with the same value.
     */
    public boolean isSatisfiedBy(ContextSet current) {
        if (entries.isEmpty()) return true;
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (!e.getValue().equals(current.entries.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    public String serialize() {
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ContextSet)) return false;
        ContextSet other = (ContextSet) o;
        return hash == other.hash && entries.equals(other.entries);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return entries.isEmpty() ? "global" : serialize();
    }
}
