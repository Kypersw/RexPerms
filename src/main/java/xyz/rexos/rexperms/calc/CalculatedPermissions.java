package xyz.rexos.rexperms.calc;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flattened permission map calculated for one context.
 *
 * Lookup order (most specific first):
 * <ol>
 *   <li>Exact match: {@code essentials.fly}</li>
 *   <li>Wildcards: {@code essentials.*}, then {@code *}</li>
 * </ol>
 * Results are additionally cached, since Bukkit may ask for the same
 * permission dozens of times per second.
 */
public final class CalculatedPermissions {

    /** Where a permission came from. */
    public static final class Source {
        private final boolean value;
        private final String holder;
        private final String node;
        private final boolean inherited;

        public Source(boolean value, String holder, String node, boolean inherited) {
            this.value = value;
            this.holder = holder;
            this.node = node;
            this.inherited = inherited;
        }

        public boolean value() {
            return value;
        }

        public String holder() {
            return holder;
        }

        public String node() {
            return node;
        }

        public boolean inherited() {
            return inherited;
        }
    }

    /** Lookup result: value + source (source is null when undefined). */
    public static final class Result {
        static final Result UNDEFINED = new Result(Tristate.UNDEFINED, null, null);

        private final Tristate value;
        private final Source source;
        private final String matchedNode;

        public Result(Tristate value, Source source, String matchedNode) {
            this.value = value;
            this.source = source;
            this.matchedNode = matchedNode;
        }

        public Tristate value() {
            return value;
        }

        public Source source() {
            return source;
        }

        public String matchedNode() {
            return matchedNode;
        }
    }

    private final Map<String, Source> sources;
    private final boolean applyWildcards;
    private final Map<String, Result> lookupCache = new ConcurrentHashMap<String, Result>();

    public CalculatedPermissions(Map<String, Source> sources, boolean applyWildcards) {
        this.sources = Collections.unmodifiableMap(sources);
        this.applyWildcards = applyWildcards;
    }

    public Tristate check(String permission) {
        return lookup(permission).value();
    }

    public Result lookup(String permission) {
        String key = permission.toLowerCase(Locale.ROOT);
        Result cached = lookupCache.get(key);
        if (cached != null) return cached;
        Result computed = compute(key);
        if (lookupCache.size() < 4096) {
            lookupCache.put(key, computed);
        }
        return computed;
    }

    private Result compute(String key) {
        Source s = sources.get(key);
        if (s != null) return new Result(Tristate.of(s.value()), s, key);
        if (!applyWildcards) return Result.UNDEFINED;

        String current = key;
        // a.b.c -> a.b.* -> a.*
        int dot;
        while ((dot = current.lastIndexOf('.')) > 0) {
            current = current.substring(0, dot);
            String wildcard = current + ".*";
            s = sources.get(wildcard);
            if (s != null) return new Result(Tristate.of(s.value()), s, wildcard);
        }
        s = sources.get("*");
        if (s != null) return new Result(Tristate.of(s.value()), s, "*");
        // Legacy form used by some old plugins
        s = sources.get("'*'");
        if (s != null) return new Result(Tristate.of(s.value()), s, "'*'");
        return Result.UNDEFINED;
    }

    /** All flattened permissions (read-only). */
    public Map<String, Source> asMap() {
        return sources;
    }

    public int size() {
        return sources.size();
    }
}
