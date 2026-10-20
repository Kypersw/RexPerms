package xyz.rexos.rexperms.calc;

import xyz.rexos.rexperms.model.Group;

public interface GroupLookup {
    /** Returns the loaded group, or null. */
    Group get(String name);
}
