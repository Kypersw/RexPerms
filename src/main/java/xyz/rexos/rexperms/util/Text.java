package xyz.rexos.rexperms.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import xyz.rexos.rexperms.model.ContextSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Colour code conversion and small text helpers. */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String LEGACY_CODES = "0123456789abcdefklmnor";
    private static final String[] TAGS = {
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
            "obfuscated", "bold", "strikethrough", "underlined", "italic", "reset"
    };

    /** Section-sign serializer with RGB (1.16+). */
    private static final LegacyComponentSerializer LEGACY_HEX = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    /** Section-sign serializer that downsamples RGB to the nearest of the 16 colours (pre-1.16). */
    private static final LegacyComponentSerializer LEGACY_16 = LegacyComponentSerializer.legacySection();

    private Text() {
    }

    /**
     * Converts text that may contain "&c&l" codes, "&#ff0000" hex codes and/or
     * MiniMessage tags into a component. Used for prefixes, suffixes and display names.
     */
    public static Component colorize(String input) {
        if (input == null || input.isEmpty()) return Component.empty();
        return MM.deserialize(legacyToMini(input));
    }

    /** Rewrites legacy colour codes as MiniMessage tags. */
    public static String legacyToMini(String input) {
        if (input.indexOf('&') < 0 && input.indexOf('§') < 0) return input;
        StringBuilder out = new StringBuilder(input.length() + 16);
        char[] c = input.toCharArray();
        for (int i = 0; i < c.length; i++) {
            char ch = c[i];
            if ((ch == '&' || ch == '§') && i + 1 < c.length) {
                // &#RRGGBB
                if (c[i + 1] == '#' && i + 7 < c.length && isHex(c, i + 2, 6)) {
                    out.append("<reset><#").append(c, i + 2, 6).append('>');
                    i += 7;
                    continue;
                }
                // &x&R&R&G&G&B&B (BungeeCord format)
                if ((c[i + 1] == 'x' || c[i + 1] == 'X') && i + 13 < c.length && isBungeeHex(c, i + 2)) {
                    out.append("<reset><#");
                    for (int k = 0; k < 6; k++) out.append(c[i + 3 + k * 2]);
                    out.append('>');
                    i += 13;
                    continue;
                }
                int idx = LEGACY_CODES.indexOf(Character.toLowerCase(c[i + 1]));
                if (idx >= 0) {
                    // In the legacy system a colour code resets formatting
                    if (idx < 16) out.append("<reset>");
                    out.append('<').append(TAGS[idx]).append('>');
                    i++;
                    continue;
                }
            }
            out.append(ch);
        }
        return out.toString();
    }

    private static boolean isHex(char[] c, int from, int len) {
        for (int i = from; i < from + len; i++) {
            if (Character.digit(c[i], 16) < 0) return false;
        }
        return true;
    }

    private static boolean isBungeeHex(char[] c, int from) {
        for (int k = 0; k < 6; k++) {
            int p = from + k * 2;
            if ((c[p] != '&' && c[p] != '§') || Character.digit(c[p + 1], 16) < 0) return false;
        }
        return true;
    }

    /** Component to '§' text, using RGB only where the server supports it. */
    public static String toLegacy(Component component) {
        return (ServerVersion.supportsHexColors() ? LEGACY_HEX : LEGACY_16).serialize(component);
    }

    /** Raw prefix text ("&#ff5555&l[Admin] ") to '§' text for the running server. */
    public static String legacy(String raw) {
        return raw == null || raw.isEmpty() ? "" : toLegacy(colorize(raw));
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Context display in messages: "" when global, otherwise " (world=x, server=y)". */
    public static String contextSuffix(ContextSet ctx) {
        if (ctx.isEmpty()) return "";
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, String> e : ctx.asMap().entrySet()) parts.add(e.getKey() + "=" + e.getValue());
        return " (" + join(", ", parts) + ")";
    }

    /** Context as command arguments: " world=x server=y". */
    public static String contextArgs(ContextSet ctx) {
        if (ctx.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : ctx.asMap().entrySet()) {
            sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** Quotes an argument that contains spaces, for suggested commands. */
    public static String quoteIfNeeded(String s) {
        if (s.isEmpty() || s.indexOf(' ') >= 0 || s.indexOf('"') >= 0) {
            return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        }
        return s;
    }

    public static String join(String sep, Iterable<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }
}
