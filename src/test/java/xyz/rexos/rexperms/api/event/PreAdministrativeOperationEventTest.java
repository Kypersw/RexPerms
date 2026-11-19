package xyz.rexos.rexperms.api.event;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PreAdministrativeOperationEventTest {
    @Test
    void lifecycleCanBeCancelled() {
        PreAdministrativeOperationEvent event =
                new PreAdministrativeOperationEvent(
                        PreAdministrativeOperationEvent.Operation.GROUP_DELETE,
                        PreAdministrativeOperationEvent.Origin.PLAYER,
                        "uuid",
                        "dono",
                        null);
        assertFalse(event.isCancelled());
        assertEquals("dono", event.getTarget());
        assertEquals(PreAdministrativeOperationEvent.Origin.PLAYER, event.getOrigin());
        event.setCancelled(true);
        assertTrue(event.isCancelled());
    }
}
