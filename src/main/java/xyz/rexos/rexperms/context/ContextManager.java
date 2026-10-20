package xyz.rexos.rexperms.context;

import org.bukkit.World;
import org.bukkit.entity.Player;
import xyz.rexos.rexperms.config.Settings;
import xyz.rexos.rexperms.model.ContextSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Calculates a player's current contexts.
 * Built-in keys: server, world, gamemode, dimension.
 */
public final class ContextManager {

    private volatile Settings settings;

    public ContextManager(Settings settings) {
        this.settings = settings;
    }

    public void update(Settings settings) {
        this.settings = settings;
    }

    public ContextSet staticContext() {
        Settings s = settings;
        return s.hasServerContext() ? ContextSet.of("server", s.server()) : ContextSet.EMPTY;
    }

    public ContextSet calculate(Player player) {
        Settings s = settings;
        Map<String, String> map = new TreeMap<String, String>();
        if (s.hasServerContext()) map.put("server", s.server());
        World world = player.getWorld();
        if (s.contextWorld()) map.put("world", world.getName());
        if (s.contextGamemode()) map.put("gamemode", player.getGameMode().name().toLowerCase(Locale.ROOT));
        if (s.contextDimension()) map.put("dimension", dimension(world));
        return ContextSet.of(map);
    }

    private static String dimension(World world) {
        switch (world.getEnvironment()) {
            case NETHER:
                return "the_nether";
            case THE_END:
                return "the_end";
            default:
                return "overworld";
        }
    }

    /** Context keys suggested by tab completion. */
    public List<String> knownKeys() {
        Settings s = settings;
        List<String> keys = new ArrayList<String>();
        keys.add("server");
        if (s.contextWorld()) keys.add("world");
        if (s.contextGamemode()) keys.add("gamemode");
        if (s.contextDimension()) keys.add("dimension");
        return keys;
    }
}
