package xyz.rexos.rexperms.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects the running Minecraft version and wraps the few API calls that
 * do not exist on every version, so the same jar runs from 1.8.8 to the newest release.
 */
public final class ServerVersion {

    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private static int major = 1;
    private static int minor = 8;
    private static int patch = 0;
    private static boolean detected;

    private static Method updateCommands;
    private static boolean updateCommandsResolved;

    private ServerVersion() {
    }

    private static synchronized void detect() {
        if (detected) return;
        detected = true;
        try {
            // e.g. "1.8.8-R0.1-SNAPSHOT", "1.21.1-R0.1-SNAPSHOT", "26.1-R0.1-SNAPSHOT"
            Matcher m = VERSION.matcher(Bukkit.getBukkitVersion());
            if (m.find()) {
                major = Integer.parseInt(m.group(1));
                minor = Integer.parseInt(m.group(2));
                patch = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            }
        } catch (Throwable ignored) {
            // no server (unit tests) — keep the safe 1.8 defaults
        }
    }

    /** Is the server at least the given version? (e.g. atLeast(1, 16)) */
    public static boolean atLeast(int maj, int min) {
        detect();
        return major > maj || (major == maj && minor >= min);
    }

    public static String describe() {
        detect();
        return major + "." + minor + (patch > 0 ? "." + patch : "");
    }

    /** RGB colours in chat arrived in 1.16. */
    public static boolean supportsHexColors() {
        return atLeast(1, 16);
    }

    /**
     * Re-sends the command tree so tab completion reflects new permissions.
     * {@code Player#updateCommands()} exists from 1.13; older clients have no command tree.
     */
    public static void updateCommands(Player player) {
        if (!updateCommandsResolved) {
            updateCommandsResolved = true;
            try {
                updateCommands = Player.class.getMethod("updateCommands");
            } catch (NoSuchMethodException ignored) {
                updateCommands = null;
            }
        }
        if (updateCommands == null) return;
        try {
            updateCommands.invoke(player);
        } catch (Throwable ignored) {
            // never let a cosmetic refresh break a permission change
        }
    }

    /** Folia uses regional schedulers that this plugin does not support. */
    public static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
