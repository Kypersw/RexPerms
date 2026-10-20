package xyz.rexos.rexperms.model;

import java.util.Locale;

/**
 * Node type, derived from the key prefix. The encoding is identical to
 * LuckPerms so data can be migrated without conversion:
 * <pre>
 *   essentials.fly          -> PERMISSION
 *   group.vip               -> INHERITANCE
 *   prefix.100.&c[Admin]    -> PREFIX
 *   suffix.10.&7*           -> SUFFIX
 *   meta.homes.5            -> META
 *   weight.100              -> WEIGHT
 *   displayname.Admin       -> DISPLAY_NAME
 * </pre>
 */
public enum NodeType {
    PERMISSION,
    INHERITANCE,
    PREFIX,
    SUFFIX,
    META,
    WEIGHT,
    DISPLAY_NAME;

    public static NodeType of(String key) {
        int dot = key.indexOf('.');
        if (dot <= 0) return PERMISSION;
        String head = key.substring(0, dot).toLowerCase(Locale.ROOT);
        if (head.equals("group")) return INHERITANCE;
        if (head.equals("prefix")) return hasPriority(key) ? PREFIX : PERMISSION;
        if (head.equals("suffix")) return hasPriority(key) ? SUFFIX : PERMISSION;
        if (head.equals("meta")) return key.indexOf('.', dot + 1) > dot + 1 ? META : PERMISSION;
        if (head.equals("weight")) return isInt(key.substring(dot + 1)) ? WEIGHT : PERMISSION;
        if (head.equals("displayname")) return DISPLAY_NAME;
        return PERMISSION;
    }

    /** Is the key shaped like "prefix.100.text"? */
    private static boolean hasPriority(String key) {
        int first = key.indexOf('.');
        int second = key.indexOf('.', first + 1);
        return second > first + 1 && isInt(key.substring(first + 1, second));
    }

    private static boolean isInt(String s) {
        if (s.isEmpty() || s.length() > 10) return false;
        int start = s.charAt(0) == '-' ? 1 : 0;
        if (start == s.length()) return false;
        for (int i = start; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}
