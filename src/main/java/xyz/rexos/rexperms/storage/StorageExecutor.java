package xyz.rexos.rexperms.storage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs storage operations off the main thread.
 *
 * YAML uses a single thread to avoid file races; SQL uses several thanks
 * to the connection pool.
 */
public final class StorageExecutor {

    public interface Task<T> {
        T run(Storage storage) throws Exception;
    }

    public interface VoidTask {
        void run(Storage storage) throws Exception;
    }

    private final Storage storage;
    private final ExecutorService executor;
    private final Logger logger;

    public StorageExecutor(Storage storage, int threads, Logger logger) {
        this.storage = storage;
        this.logger = logger;
        final AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "RexPerms-Storage-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.executor = Executors.newFixedThreadPool(Math.max(1, threads), factory);
    }

    public Storage storage() {
        return storage;
    }

    public <T> CompletableFuture<T> submit(final Task<T> task) {
        final CompletableFuture<T> future = new CompletableFuture<T>();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    future.complete(task.run(storage));
                } catch (Throwable e) {
                    logger.log(Level.SEVERE, "Storage operation failed (" + storage.getName() + ")", e);
                    future.completeExceptionally(e instanceof CompletionException ? e : new CompletionException(e));
                }
            }
        });
        return future;
    }

    public CompletableFuture<Void> run(final VoidTask task) {
        return submit(new Task<Void>() {
            @Override
            public Void run(Storage s) throws Exception {
                task.run(s);
                return null;
            }
        });
    }

    /** Finishes queued work and shuts down (server stop). */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                logger.warning("Some storage operations did not finish within 15 seconds; forcing shutdown.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        storage.shutdown();
    }
}
