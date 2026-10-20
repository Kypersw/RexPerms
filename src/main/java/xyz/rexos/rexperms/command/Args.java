package xyz.rexos.rexperms.command;

import xyz.rexos.rexperms.model.ContextSet;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Argument list with quote support.
 *
 * <pre>
 * /rp group admin meta setprefix 100 "&c[Admin] " world=survival
 *   -> [group, admin, meta, setprefix, 100, "&c[Admin] " (quoted), world=survival]
 * </pre>
 * Unquoted {@code key=value} arguments can be extracted as contexts.
 */
public final class Args {

    public static final class Arg {
        private final String value;
        private final boolean quoted;

        Arg(String value, boolean quoted) {
            this.value = value;
            this.quoted = quoted;
        }

        public String value() {
            return value;
        }

        public boolean quoted() {
            return quoted;
        }
    }

    private static final Pattern CONTEXT = Pattern.compile("^[a-zA-Z0-9_\\-:]+=[^=\\s]+$");

    private final List<Arg> args;

    private Args(List<Arg> args) {
        this.args = args;
    }

    public static Args parse(String[] raw) {
        StringBuilder sb = new StringBuilder();
        for (String r : raw) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(r);
        }
        return new Args(tokenize(sb.toString()));
    }

    static List<Arg> tokenize(String input) {
        List<Arg> out = new ArrayList<Arg>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        boolean wasQuoted = false;
        boolean hasToken = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' && inQuotes && i + 1 < input.length()) {
                char next = input.charAt(i + 1);
                if (next == '"' || next == '\\') {
                    cur.append(next);
                    i++;
                    continue;
                }
            }
            if (c == '"') {
                inQuotes = !inQuotes;
                wasQuoted = true;
                hasToken = true;
                continue;
            }
            if (c == ' ' && !inQuotes) {
                if (hasToken) {
                    out.add(new Arg(cur.toString(), wasQuoted));
                    cur.setLength(0);
                    wasQuoted = false;
                    hasToken = false;
                }
                continue;
            }
            cur.append(c);
            hasToken = true;
        }
        if (hasToken) out.add(new Arg(cur.toString(), wasQuoted));
        return out;
    }

    public int size() {
        return args.size();
    }

    public boolean has(int index) {
        return index < args.size();
    }

    public String get(int index) {
        return args.get(index).value();
    }

    public String lower(int index) {
        return get(index).toLowerCase(Locale.ROOT);
    }

    /** Remaining arguments from index on. */
    public Args from(int index) {
        return new Args(index >= args.size()
                ? new ArrayList<Arg>()
                : new ArrayList<Arg>(args.subList(index, args.size())));
    }

    /** Removes unquoted key=value arguments and returns them as contexts. */
    public ContextSet extractContext() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        Iterator<Arg> it = args.iterator();
        while (it.hasNext()) {
            Arg a = it.next();
            if (a.quoted()) continue;
            if (CONTEXT.matcher(a.value()).matches()) {
                int eq = a.value().indexOf('=');
                map.put(a.value().substring(0, eq), a.value().substring(eq + 1));
                it.remove();
            }
        }
        return ContextSet.of(map);
    }

    /** true/false/yes/no; null if not recognised. */
    public static Boolean parseBoolean(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        if (l.equals("true") || l.equals("yes") || l.equals("1") || l.equals("on")) return Boolean.TRUE;
        if (l.equals("false") || l.equals("no") || l.equals("0") || l.equals("off")) return Boolean.FALSE;
        return null;
    }

    public static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
