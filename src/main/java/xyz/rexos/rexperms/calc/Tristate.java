package xyz.rexos.rexperms.calc;

public enum Tristate {
    TRUE,
    FALSE,
    /** No node defines this permission; the platform default (op / plugin.yml) applies. */
    UNDEFINED;

    public static Tristate of(Boolean b) {
        if (b == null) return UNDEFINED;
        return b ? TRUE : FALSE;
    }

    public boolean asBoolean() {
        return this == TRUE;
    }
}
