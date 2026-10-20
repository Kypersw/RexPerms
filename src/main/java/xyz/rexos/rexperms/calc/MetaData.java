package xyz.rexos.rexperms.calc;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;

/**
 * Calculated chat/meta data.
 */
public final class MetaData {

    private final String prefix;
    private final String suffix;
    private final SortedMap<Integer, String> prefixes;
    private final SortedMap<Integer, String> suffixes;
    private final Map<String, String> meta;
    private final String primaryGroup;
    private final int weight;

    /**
     * @param prefix       highest priority prefix (or null)
     * @param suffix       highest priority suffix (or null)
     * @param prefixes     priority -> prefix (descending)
     * @param suffixes     priority -> suffix (descending)
     * @param meta         meta key -> value (first found wins)
     * @param primaryGroup primary group name
     * @param weight       weight of the heaviest group (useful for tab sorting)
     */
    public MetaData(String prefix, String suffix, SortedMap<Integer, String> prefixes,
                    SortedMap<Integer, String> suffixes, Map<String, String> meta,
                    String primaryGroup, int weight) {
        this.prefix = prefix;
        this.suffix = suffix;
        this.prefixes = Collections.unmodifiableSortedMap(prefixes);
        this.suffixes = Collections.unmodifiableSortedMap(suffixes);
        this.meta = Collections.unmodifiableMap(meta);
        this.primaryGroup = primaryGroup;
        this.weight = weight;
    }

    public String prefix() {
        return prefix;
    }

    public String suffix() {
        return suffix;
    }

    public SortedMap<Integer, String> prefixes() {
        return prefixes;
    }

    public SortedMap<Integer, String> suffixes() {
        return suffixes;
    }

    public Map<String, String> meta() {
        return meta;
    }

    public String primaryGroup() {
        return primaryGroup;
    }

    public int weight() {
        return weight;
    }

    public String metaValue(String key) {
        return meta.get(key.toLowerCase(Locale.ROOT));
    }

    public int metaInt(String key, int def) {
        String v = metaValue(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
