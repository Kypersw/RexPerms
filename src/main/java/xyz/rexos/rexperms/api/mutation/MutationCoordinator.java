package xyz.rexos.rexperms.api.mutation;

import java.util.*;
import java.util.function.*;

/**
 * Main-thread unit of work. Drafts are visible only to the transaction thread. All resources are
 * authorized before any state is published or effect runs.
 */
public final class MutationCoordinator {
    public interface Authorizer {
        void authorize(String kind, String id, Object before, Object after);
    }

    private final Authorizer authorizer;
    private final Runnable threadCheck;
    private final ThreadLocal<Transaction> current = new ThreadLocal<Transaction>();
    private boolean authorizing;

    private static final class Transaction {
        final LinkedHashMap<Cell<?>, Object> changes = new LinkedHashMap<Cell<?>, Object>();
        final List<Runnable> effects = new ArrayList<Runnable>();
        final List<java.util.function.Consumer<Throwable>> failures =
                new ArrayList<java.util.function.Consumer<Throwable>>();
        Throwable rollbackOnly;
    }

    public MutationCoordinator(Authorizer authorizer, Runnable threadCheck) {
        this.authorizer = Objects.requireNonNull(authorizer);
        this.threadCheck = threadCheck;
    }

    public boolean isActive() {
        return current.get() != null;
    }

    public void run(Runnable action) {
        call(
                () -> {
                    action.run();
                    return null;
                });
    }

    public <T> T call(Supplier<T> action) {
        threadCheck.run();
        if (authorizing) throw new SecurityException("Mutation during authorization is forbidden");
        if (isActive()) {
            try {
                return action.get();
            } catch (RuntimeException | Error ex) {
                current.get().rollbackOnly = ex;
                throw ex;
            }
        }
        Transaction tx = new Transaction();
        current.set(tx);
        T result;
        try {
            result = action.get();
            if (tx.rollbackOnly != null)
                throw new SecurityException("Nested mutation failed", tx.rollbackOnly);
            authorizing = true;
            try {
                for (Map.Entry<Cell<?>, Object> e : tx.changes.entrySet()) {
                    Cell<?> c = e.getKey();
                    if (!Objects.equals(c.value, e.getValue()))
                        authorizer.authorize(c.kind, c.id, c.value, e.getValue());
                }
            } finally {
                authorizing = false;
            }
            for (Map.Entry<Cell<?>, Object> e : tx.changes.entrySet())
                e.getKey().publish(e.getValue());
        } catch (RuntimeException | Error ex) {
            for (java.util.function.Consumer<Throwable> failure : tx.failures) failure.accept(ex);
            throw ex;
        } finally {
            current.remove();
        }
        for (Runnable effect : tx.effects) effect.run();
        return result;
    }

    /** Effects must never be submitted until the surrounding transaction is accepted. */
    public void onRollback(java.util.function.Consumer<Throwable> failure) {
        Transaction tx = current.get();
        if (tx != null) tx.failures.add(failure);
    }

    public void afterCommit(Runnable action) {
        Transaction tx = current.get();
        if (tx == null) action.run();
        else tx.effects.add(action);
    }

    public static final class Cell<T> {
        private volatile T value;
        private final MutationCoordinator owner;
        private final String kind, id;
        private final Runnable changed;

        public Cell(
                MutationCoordinator owner, String kind, String id, T initial, Runnable changed) {
            this.owner = owner;
            this.kind = kind;
            this.id = id;
            this.value = initial;
            this.changed = changed;
        }

        @SuppressWarnings("unchecked")
        public T get() {
            Transaction tx = owner == null ? null : owner.current.get();
            return tx != null && !owner.authorizing && tx.changes.containsKey(this)
                    ? (T) tx.changes.get(this)
                    : value;
        }

        public void update(UnaryOperator<T> update) {
            if (owner == null) {
                synchronized (this) {
                    value = update.apply(value);
                    changed.run();
                }
                return;
            }
            owner.run(() -> owner.current.get().changes.put(this, update.apply(get())));
        }

        @SuppressWarnings("unchecked")
        private void publish(Object next) {
            if (!Objects.equals(value, next)) {
                value = (T) next;
                changed.run();
            }
        }
    }
}
