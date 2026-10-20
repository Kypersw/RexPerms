package xyz.rexos.rexperms.model;

import java.util.Locale;

/**
 * What happens when the same temporary node is added again.
 * E.g. a player with 30 days of VIP buys 30 more: with {@link #ACCUMULATE}
 * they end up with 60 days.
 */
public enum TemporaryMergeBehaviour {
    /** Adds the new duration to the remaining time. */
    ACCUMULATE,
    /** Replaces the existing node if the new one lasts longer. */
    REPLACE,
    /** Fails if one already exists (default for commands). */
    FAIL;

    public static TemporaryMergeBehaviour parse(String s) {
        if (s == null) return null;
        String l = s.toLowerCase(Locale.ROOT);
        if (l.equals("accumulate")) return ACCUMULATE;
        if (l.equals("replace")) return REPLACE;
        if (l.equals("deny") || l.equals("fail")) return FAIL;
        return null;
    }
}
