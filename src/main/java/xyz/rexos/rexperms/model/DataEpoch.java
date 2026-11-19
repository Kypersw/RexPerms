package xyz.rexos.rexperms.model;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Global counter that increases whenever any holder's data changes.
 *
 * <p>Groups inherit from each other, so a change in one group can affect every user. Each cache
 * entry remembers the epoch it was computed at and is recalculated lazily once the epoch moves on —
 * no need to track which user depends on which group.
 */
public final class DataEpoch {
    private final xyz.rexos.rexperms.api.mutation.MutationCoordinator coordinator;

    public DataEpoch() {
        this(null);
    }

    public DataEpoch(xyz.rexos.rexperms.api.mutation.MutationCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public xyz.rexos.rexperms.api.mutation.MutationCoordinator coordinator() {
        return coordinator;
    }

    private final AtomicLong value = new AtomicLong();

    public long get() {
        return value.get();
    }

    public void bump() {
        value.incrementAndGet();
    }
}
