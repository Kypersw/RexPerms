package xyz.rexos.rexperms.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Duration parsing and formatting.
 *
 * Accepted: {@code 30s}, {@code 15m}, {@code 2h}, {@code 7d}, {@code 2w},
 * {@code 1mo}, {@code 1y} and combinations ({@code 1d12h30m}).
 * An absolute unix timestamp (seconds) is also accepted as an expiry.
 */
public final class Durations {

    private static final Pattern PART = Pattern.compile("(\\d+)\\s*(mo|s|m|h|d|w|y)", Pattern.CASE_INSENSITIVE);

    private static final long[] UNITS = {31_536_000L, 2_592_000L, 604_800L, 86_400L, 3_600L, 60L, 1L};
    private static final String[] NAMES = {"year", "month", "week", "day", "hour", "minute", "second"};
    private static final String[] SHORT = {"y", "mo", "w", "d", "h", "m", "s"};

    private Durations() {
    }

    /**
     * @return the duration in seconds, or -1 if invalid
     */
    public static long parseSeconds(String input) {
        if (input == null || input.trim().isEmpty()) return -1;
        String s = input.trim().toLowerCase(Locale.ROOT);

        Matcher m = PART.matcher(s);
        long total = 0;
        int consumed = 0;
        while (m.find()) {
            if (m.start() != consumed) return -1; // garbage between parts
            long amount;
            try {
                amount = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
            long unit;
            String u = m.group(2).toLowerCase(Locale.ROOT);
            if (u.equals("s")) unit = 1L;
            else if (u.equals("m")) unit = 60L;
            else if (u.equals("h")) unit = 3_600L;
            else if (u.equals("d")) unit = 86_400L;
            else if (u.equals("w")) unit = 604_800L;
            else if (u.equals("mo")) unit = 2_592_000L;     // 30 days
            else if (u.equals("y")) unit = 31_536_000L;     // 365 days
            else return -1;
            try {
                total = Math.addExact(total, Math.multiplyExact(amount, unit));
            } catch (ArithmeticException e) {
                return -1;
            }
            consumed = m.end();
        }
        if (consumed != s.length() || total <= 0) return -1;
        return total;
    }

    /**
     * Converts a duration or an absolute unix timestamp into an expiry time.
     *
     * @return expiry in unix seconds, or -1 if invalid
     */
    public static long parseExpiry(String input, long nowSeconds) {
        if (input != null && input.length() >= 10 && isDigits(input)) {
            long abs = Long.parseLong(input);
            return abs > nowSeconds ? abs : -1;
        }
        long secs = parseSeconds(input);
        return secs < 0 ? -1 : nowSeconds + secs;
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    /** "2 days 3 hours 5 minutes" — at most three units. */
    public static String format(long seconds) {
        if (seconds <= 0) return "0 seconds";
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        long rest = seconds;
        for (int i = 0; i < UNITS.length && shown < 3; i++) {
            long v = rest / UNITS[i];
            if (v > 0) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(v).append(' ').append(NAMES[i]).append(v == 1 ? "" : "s");
                rest -= v * UNITS[i];
                shown++;
            }
        }
        return sb.toString();
    }

    /** Compact form: "2d 3h". */
    public static String formatShort(long seconds) {
        if (seconds <= 0) return "0s";
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        long rest = seconds;
        for (int i = 0; i < UNITS.length && shown < 2; i++) {
            long v = rest / UNITS[i];
            if (v > 0) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(v).append(SHORT[i]);
                rest -= v * UNITS[i];
                shown++;
            }
        }
        return sb.toString();
    }
}
