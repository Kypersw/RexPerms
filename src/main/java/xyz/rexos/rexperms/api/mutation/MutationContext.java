package xyz.rexos.rexperms.api.mutation;

import org.bukkit.command.*;
import org.bukkit.entity.Player;

import xyz.rexos.rexperms.api.event.PreHolderMutationEvent.Origin;

/** Attribution is explicit and scoped; missing context is never console. */
public final class MutationContext {
    public final Origin origin;
    public final String actor, operation;
    private static final ThreadLocal<MutationContext> CURRENT = new ThreadLocal<MutationContext>();

    public MutationContext(Origin origin, String actor, String operation) {
        this.origin = origin;
        this.actor = actor;
        this.operation = operation;
    }

    public static MutationContext current() {
        MutationContext c = CURRENT.get();
        return c == null ? new MutationContext(Origin.API, null, "DIRECT") : c;
    }

    public static MutationContext sender(CommandSender s, String operation) {
        return new MutationContext(
                s instanceof Player
                        ? Origin.PLAYER
                        : s instanceof ConsoleCommandSender ? Origin.CONSOLE : Origin.API,
                s instanceof Player
                        ? ((Player) s).getUniqueId().toString()
                        : s instanceof ConsoleCommandSender ? "Console" : null,
                operation);
    }

    public void run(Runnable action) {
        MutationContext old = CURRENT.get();
        CURRENT.set(this);
        try {
            action.run();
        } finally {
            if (old == null) CURRENT.remove();
            else CURRENT.set(old);
        }
    }
}
