package xyz.rexos.rexperms.api.event;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PreAdministrativeCommandEventTest {
    @Test
    void commandArgumentsAreCopied() {
        String[] args = {"user", "alex", "parent", "add", "vip"};
        PreAdministrativeCommandEvent event = new PreAdministrativeCommandEvent(null, args);
        args[0] = "deletegroup";
        assertEquals("user", event.getArguments()[0]);
        event.getArguments()[0] = "deletegroup";
        assertEquals("user", event.getArguments()[0]);
        event.setCancelled(true);
        assertTrue(event.isCancelled());
    }
}
