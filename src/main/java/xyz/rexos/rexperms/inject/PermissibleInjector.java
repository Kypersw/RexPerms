package xyz.rexos.rexperms.inject;

import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.PermissionAttachment;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the "perm" field of CraftBukkit's player object by reflection and
 * swaps in a {@link RexPermissible}.
 *
 * The field has been called "perm" from 1.8 to today; we still fall back to
 * searching by type in case a fork renames it.
 */
public final class PermissibleInjector {

    private static volatile Field cachedField;
    private static volatile Field attachmentsField;

    private PermissibleInjector() {
    }

    private static Field findField(Class<?> start) throws NoSuchFieldException {
        Field f = cachedField;
        if (f != null && f.getDeclaringClass().isAssignableFrom(start)) return f;

        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field candidate : c.getDeclaredFields()) {
                if (candidate.getName().equals("perm") && PermissibleBase.class.isAssignableFrom(candidate.getType())) {
                    candidate.setAccessible(true);
                    cachedField = candidate;
                    return candidate;
                }
            }
        }
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field candidate : c.getDeclaredFields()) {
                if (PermissibleBase.class.isAssignableFrom(candidate.getType())) {
                    candidate.setAccessible(true);
                    cachedField = candidate;
                    return candidate;
                }
            }
        }
        throw new NoSuchFieldException("No PermissibleBase field found in " + start.getName());
    }

    /**
     * Injects. Attachments other plugins already added are carried over.
     */
    public static void inject(Player player, RexPermissible permissible) throws ReflectiveOperationException {
        Field field = findField(player.getClass());
        PermissibleBase old = (PermissibleBase) field.get(player);
        if (old instanceof RexPermissible) {
            old = ((RexPermissible) old).getPrevious();
        }
        permissible.setPrevious(old);

        if (old != null) {
            List<PermissionAttachment> attachments = readAttachments(old);
            if (!attachments.isEmpty()) {
                readAttachments(permissible).addAll(attachments);
            }
        }
        field.set(player, permissible);
        permissible.recalculatePermissions();
    }

    /** Puts the original object back (plugin disable). */
    public static void uninject(Player player) {
        try {
            Field field = findField(player.getClass());
            Object current = field.get(player);
            if (current instanceof RexPermissible) {
                PermissibleBase previous = ((RexPermissible) current).getPrevious();
                PermissibleBase restored = previous != null ? previous : new PermissibleBase(player);
                field.set(player, restored);
                restored.recalculatePermissions();
            }
        } catch (ReflectiveOperationException ignored) {
            // player may be leaving; not important
        }
    }

    public static RexPermissible getInjected(Player player) {
        try {
            Object current = findField(player.getClass()).get(player);
            return current instanceof RexPermissible ? (RexPermissible) current : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<PermissionAttachment> readAttachments(PermissibleBase base) {
        try {
            Field f = attachmentsField;
            if (f == null) {
                f = PermissibleBase.class.getDeclaredField("attachments");
                f.setAccessible(true);
                attachmentsField = f;
            }
            Object value = f.get(base);
            if (value instanceof List) return (List<PermissionAttachment>) value;
        } catch (ReflectiveOperationException ignored) {
            // field renamed: attachments cannot be moved, not critical
        }
        return new ArrayList<PermissionAttachment>();
    }
}
