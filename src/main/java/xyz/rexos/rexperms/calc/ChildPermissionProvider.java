package xyz.rexos.rexperms.calc;

import java.util.Collections;
import java.util.Map;

/**
 * Supplies the platform's child permission definitions (Bukkit: "children" in plugin.yml).
 * E.g. granting "essentials.kits.*" also grants every kit the plugin declared under it.
 */
public interface ChildPermissionProvider {

    ChildPermissionProvider NONE = new ChildPermissionProvider() {
        @Override
        public Map<String, Boolean> children(String permission) {
            return Collections.emptyMap();
        }
    };

    /** Direct children: key -> (true: same value as parent, false: inverted). */
    Map<String, Boolean> children(String permission);
}
