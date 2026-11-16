package xyz.rexos.rexperms.api.event;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import xyz.rexos.rexperms.model.ContextSet;
import xyz.rexos.rexperms.model.HolderType;
import xyz.rexos.rexperms.model.Node;

import java.util.ArrayList;
import java.util.List;

class PreHolderMutationEventTest {
    @Test
    void snapshotsAreImmutable() {
        List<Node> old = new ArrayList<Node>();
        old.add(Node.inheritance("default", ContextSet.EMPTY));
        List<Node> updated = new ArrayList<Node>();
        updated.add(Node.inheritance("admin", ContextSet.EMPTY));
        PreHolderMutationEvent event =
                new PreHolderMutationEvent(
                        HolderType.USER,
                        "uuid",
                        PreHolderMutationEvent.Origin.API,
                        null,
                        old,
                        updated,
                        "default",
                        "admin");
        old.clear();
        updated.clear();
        assertEquals(1, event.getBefore().size());
        assertEquals(1, event.getAfter().size());
        assertThrows(UnsupportedOperationException.class, () -> event.getAfter().clear());
        event.setCancelled(true);
        assertTrue(event.isCancelled());
        assertEquals("admin", event.getAfterPrimaryGroup());
    }
}
