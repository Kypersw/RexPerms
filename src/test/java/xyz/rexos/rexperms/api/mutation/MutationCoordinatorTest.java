package xyz.rexos.rexperms.api.mutation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import xyz.rexos.rexperms.manager.*;
import xyz.rexos.rexperms.model.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class MutationCoordinatorTest {
    @Test
    void cancelledBatchLeavesAllStateEpochAndEffectsUntouched() {
        AtomicInteger effects = new AtomicInteger(), checks = new AtomicInteger();
        MutationCoordinator c =
                new MutationCoordinator(
                        (kind, id, before, after) -> {
                            if (checks.incrementAndGet() == 2)
                                throw new SecurityException("veto second resource");
                        },
                        () -> {});
        DataEpoch epoch = new DataEpoch(c);
        User a = new User(UUID.randomUUID(), "a", epoch),
                b = new User(UUID.randomUUID(), "b", epoch);
        assertThrows(
                SecurityException.class,
                () ->
                        c.run(
                                () -> {
                                    a.setNode(Node.inheritance("admin", ContextSet.EMPTY));
                                    b.setPrimaryGroup("admin");
                                    c.afterCommit(effects::incrementAndGet);
                                }));
        assertTrue(a.getNodes().isEmpty());
        assertEquals("default", b.getPrimaryGroup());
        assertEquals(0, epoch.get());
        assertEquals(0, effects.get());
    }

    @Test
    void directMutatorsCannotBypassVeto() {
        MutationCoordinator c =
                new MutationCoordinator(
                        (k, id, b, a) -> {
                            throw new SecurityException();
                        },
                        () -> {});
        User user = new User(UUID.randomUUID(), "test", new DataEpoch(c));
        assertThrows(
                SecurityException.class,
                () -> user.setNode(Node.inheritance("admin", ContextSet.EMPTY)));
        assertThrows(SecurityException.class, () -> user.setPrimaryGroup("admin"));
        assertThrows(
                SecurityException.class,
                () ->
                        user.replaceNodes(
                                Arrays.asList(Node.permission("*", true, ContextSet.EMPTY))));
        assertTrue(user.getNodes().isEmpty());
        assertEquals("default", user.getPrimaryGroup());
    }

    @Test
    void listenersSeeCommittedStateAndReentrantMutationIsRejected() {
        final User[] user = {null};
        final MutationCoordinator[] ref = {null};
        MutationCoordinator c =
                new MutationCoordinator(
                        (k, id, b, a) -> {
                            assertEquals("default", user[0].getPrimaryGroup());
                            assertThrows(
                                    SecurityException.class, () -> user[0].setPrimaryGroup("dono"));
                        },
                        () -> {});
        ref[0] = c;
        user[0] = new User(UUID.randomUUID(), "test", new DataEpoch(c));
        user[0].setPrimaryGroup("vip");
        assertEquals("vip", user[0].getPrimaryGroup());
    }

    @Test
    void otherThreadsCannotObserveDrafts() throws Exception {
        MutationCoordinator c = new MutationCoordinator((k, id, b, a) -> {}, () -> {});
        User user = new User(UUID.randomUUID(), "test", new DataEpoch(c));
        c.run(
                () -> {
                    user.setPrimaryGroup("admin");
                    assertEquals("admin", user.getPrimaryGroup());
                    CompletableFuture<String> other =
                            CompletableFuture.supplyAsync(user::getPrimaryGroup);
                    assertEquals("default", other.join());
                });
        assertEquals("admin", user.getPrimaryGroup());
    }

    @Test
    void exceptionOrCaughtNestedFailureRollsBack() {
        MutationCoordinator c = new MutationCoordinator((k, id, b, a) -> {}, () -> {});
        User user = new User(UUID.randomUUID(), "test", new DataEpoch(c));
        assertThrows(
                SecurityException.class,
                () ->
                        c.run(
                                () -> {
                                    user.setPrimaryGroup("admin");
                                    try {
                                        c.run(
                                                () -> {
                                                    throw new IllegalArgumentException();
                                                });
                                    } catch (IllegalArgumentException ignored) {
                                    }
                                }));
        assertEquals("default", user.getPrimaryGroup());
    }

    @Test
    void registryAndTrackChangesArePartOfSameTransaction() {
        MutationCoordinator c =
                new MutationCoordinator(
                        (k, id, b, a) -> {
                            if (k.equals("TRACK")) throw new SecurityException();
                        },
                        () -> {});
        GroupManager groups = new GroupManager(new DataEpoch(c));
        TrackManager tracks = new TrackManager(c);
        assertThrows(
                SecurityException.class,
                () ->
                        c.run(
                                () -> {
                                    groups.getOrCreate("admin")
                                            .setNode(Node.permission("*", true, ContextSet.EMPTY));
                                    tracks.getOrCreate("staff").append("admin");
                                }));
        assertNull(groups.get("admin"));
        assertNull(tracks.get("staff"));
        assertThrows(UnsupportedOperationException.class, () -> groups.all().clear());
    }

    @Test
    void rollbackCompletesDeferredFailures() {
        MutationCoordinator c =
                new MutationCoordinator(
                        (k, id, b, a) -> {
                            throw new SecurityException();
                        },
                        () -> {});
        CompletableFuture<Void> future = new CompletableFuture<Void>();
        User user = new User(UUID.randomUUID(), "test", new DataEpoch(c));
        assertThrows(
                SecurityException.class,
                () ->
                        c.run(
                                () -> {
                                    c.onRollback(future::completeExceptionally);
                                    c.afterCommit(() -> future.complete(null));
                                    user.setPrimaryGroup("admin");
                                }));
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void oneHolderProducesOneFinalSnapshotInCompoundWrite() {
        AtomicInteger checks = new AtomicInteger();
        MutationCoordinator c =
                new MutationCoordinator(
                        (k, id, b, a) -> {
                            checks.incrementAndGet();
                            HolderSnapshot after = (HolderSnapshot) a;
                            assertEquals("admin", after.primaryGroup);
                            assertEquals(1, after.nodes.size());
                        },
                        () -> {});
        User user = new User(UUID.randomUUID(), "test", new DataEpoch(c));
        c.run(
                () -> {
                    user.setNode(Node.inheritance("admin", ContextSet.EMPTY));
                    user.setPrimaryGroup("admin");
                });
        assertEquals(1, checks.get());
    }
}
