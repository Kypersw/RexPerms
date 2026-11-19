package xyz.rexos.rexperms.api.event;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import xyz.rexos.rexperms.model.ContextSet;
import xyz.rexos.rexperms.model.HolderType;
import xyz.rexos.rexperms.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class AuthorizationEventTest {
    @Test
    void mutationSnapshotsAreImmutable() {
        List<Node> before = new ArrayList<Node>();
        before.add(Node.inheritance("default", ContextSet.EMPTY));
        PreHolderMutationEvent e =
                new PreHolderMutationEvent(
                        HolderType.USER,
                        "uuid",
                        PreHolderMutationEvent.Origin.API,
                        null,
                        before,
                        Collections.<Node>emptyList(),
                        "default",
                        "admin");
        before.clear();
        assertEquals(1, e.getBefore().size());
        assertThrows(UnsupportedOperationException.class, () -> e.getBefore().clear());
        assertEquals("admin", e.getAfterPrimaryGroup());
        assertFalse(e.isCancelled());
        e.setCancelled(true);
        assertTrue(e.isCancelled());
        assertEquals(PreHolderMutationEvent.Origin.API, e.getOrigin());
        assertNull(e.getActorIdentifier());
    }
}
