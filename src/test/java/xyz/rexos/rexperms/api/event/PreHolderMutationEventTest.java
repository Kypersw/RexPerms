package xyz.rexos.rexperms.api.event;

import org.junit.jupiter.api.Test;
import xyz.rexos.rexperms.model.HolderType;
import xyz.rexos.rexperms.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PreHolderMutationEventTest {
    @Test
    void snapshotsCannotBeModifiedAfterConstruction() {
        List<Node> before = new ArrayList<Node>();
        before.add(Node.inheritance("default", xyz.rexos.rexperms.model.ContextSet.EMPTY));
        List<Node> after = new ArrayList<Node>();
        after.add(Node.inheritance("admin", xyz.rexos.rexperms.model.ContextSet.EMPTY));
        PreHolderMutationEvent event = new PreHolderMutationEvent(
                HolderType.USER, "some-user", PreHolderMutationEvent.Origin.API,
                null, before, after);
        before.clear();
        after.clear();
        assertEquals(1, event.getBefore().size());
        assertEquals(1, event.getAfter().size());
        assertThrows(UnsupportedOperationException.class, () -> event.getAfter().clear());
    }

    @Test
    void cancellationIsExplicitAndOriginIsNotElevated() {
        PreHolderMutationEvent event = new PreHolderMutationEvent(
                HolderType.GROUP, "admin", PreHolderMutationEvent.Origin.SYSTEM,
                null, Collections.<Node>emptyList(), Collections.<Node>emptyList());
        assertFalse(event.isCancelled());
        assertEquals(PreHolderMutationEvent.Origin.SYSTEM, event.getOrigin());
        assertNull(event.getActorIdentifier());
        event.setCancelled(true);
        assertTrue(event.isCancelled());
    }
}
