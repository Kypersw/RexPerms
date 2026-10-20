package xyz.rexos.rexperms.command;

import xyz.rexos.rexperms.model.ContextSet;

import java.util.ArrayList;
import java.util.List;

/** Gives tests access to package-private {@link Args} methods. */
public final class ArgsAccess {

    private ArgsAccess() {
    }

    public static List<String> tokens(String input) {
        List<String> out = new ArrayList<String>();
        for (Args.Arg a : Args.tokenize(input)) out.add(a.value());
        return out;
    }

    public static ContextSet context(String input) {
        return Args.parse(input.split(" ")).extractContext();
    }
}
