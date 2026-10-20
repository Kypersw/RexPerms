package xyz.rexos.rexperms.calc;

import xyz.rexos.rexperms.model.ContextSet;
import xyz.rexos.rexperms.model.User;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A user's calculated permissions and meta, per context.
 *
 * Each entry remembers the {@link xyz.rexos.rexperms.model.DataEpoch} it was
 * built at and is recalculated lazily on the first query after any data
 * change, so bulk edits never trigger redundant work.
 */
public final class CachedUserData {

    private static final class Entry {
        final long epoch;
        final CalculatedPermissions permissions;
        final MetaData meta;

        Entry(long epoch, CalculatedPermissions permissions, MetaData meta) {
            this.epoch = epoch;
            this.permissions = permissions;
            this.meta = meta;
        }
    }

    /** A player rarely has many contexts at once; cap it anyway. */
    private static final int MAX_CONTEXTS = 32;

    private final User user;
    private final ConcurrentHashMap<ContextSet, Entry> entries = new ConcurrentHashMap<ContextSet, Entry>();

    public CachedUserData(User user) {
        this.user = user;
    }

    public CalculatedPermissions permissions(ContextSet ctx, PermissionCalculator calc) {
        return entry(ctx, calc).permissions;
    }

    public MetaData meta(ContextSet ctx, PermissionCalculator calc) {
        return entry(ctx, calc).meta;
    }

    private Entry entry(ContextSet ctx, PermissionCalculator calc) {
        // Read the epoch first: if data changes mid-calculation the entry is already stale
        long current = user.getEpoch().get();
        Entry e = entries.get(ctx);
        if (e != null && e.epoch == current) return e;

        Entry fresh = new Entry(current, calc.calculatePermissions(user, ctx), calc.calculateMeta(user, ctx));
        if (entries.size() >= MAX_CONTEXTS) entries.clear();
        entries.put(ctx, fresh);
        return fresh;
    }

    public void invalidate() {
        entries.clear();
    }
}
