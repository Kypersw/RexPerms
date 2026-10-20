package xyz.rexos.rexperms.model;

import java.util.Locale;
import java.util.regex.Pattern;

public final class Group extends PermissionHolder {

    /** Group names: lower-case letters, digits, underscore and dash. 1-36 characters. */
    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_\\-]{1,36}$");

    private final String name;

    public Group(String name, DataEpoch epoch) {
        super(epoch);
        this.name = name.toLowerCase(Locale.ROOT);
    }

    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name.toLowerCase(Locale.ROOT)).matches();
    }

    public String getName() {
        return name;
    }

    /** Group weight. Higher weight = higher priority. 0 when not set. */
    public int getWeight() {
        boolean found = false;
        int max = 0;
        for (Node n : getNodes(NodeType.WEIGHT)) {
            if (!n.contexts().isEmpty()) continue;
            int w = n.weightValue();
            if (!found || w > max) {
                max = w;
                found = true;
            }
        }
        return max;
    }

    /** Display name, or null if none is set. */
    public String getDisplayName() {
        for (Node n : getNodes(NodeType.DISPLAY_NAME)) {
            if (n.contexts().isEmpty()) return n.displayNameValue();
        }
        return null;
    }

    /** Display name if set, otherwise the group name. */
    public String getPlainDisplayName() {
        String d = getDisplayName();
        return d == null ? name : d;
    }

    @Override
    public String getIdentifier() {
        return name;
    }

    @Override
    public String getFriendlyName() {
        return name;
    }

    @Override
    public HolderType getType() {
        return HolderType.GROUP;
    }
}
