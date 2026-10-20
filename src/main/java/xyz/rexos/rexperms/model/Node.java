package xyz.rexos.rexperms.model;

import java.util.Locale;

/**
 * A single piece of data assigned to a permission holder (a user or a group).
 * Immutable.
 *
 * <ul>
 *   <li>{@code key}: e.g. "essentials.fly", "group.vip", "prefix.100.&c[Admin] "</li>
 *   <li>{@code value}: true grants, false explicitly denies</li>
 *   <li>{@code expiry}: unix time in seconds; 0 means permanent</li>
 *   <li>{@code contexts}: where the node applies</li>
 * </ul>
 */
public final class Node {

    private final String key;
    private final boolean value;
    private final long expiry;
    private final ContextSet contexts;

    public Node(String key, boolean value, long expiry, ContextSet contexts) {
        if (key == null) throw new NullPointerException("key");
        if (key.trim().isEmpty()) throw new IllegalArgumentException("Node key cannot be empty");
        if (expiry < 0) throw new IllegalArgumentException("expiry cannot be negative");
        this.key = normalize(key);
        this.value = value;
        this.expiry = expiry;
        this.contexts = contexts == null ? ContextSet.EMPTY : contexts;
    }

    // ------------------------------------------------------------------ factories

    public static Node of(String key, boolean value, long expiry, ContextSet contexts) {
        return new Node(key, value, expiry, contexts);
    }

    public static Node permission(String key, boolean value, ContextSet contexts) {
        return new Node(key, value, 0L, contexts);
    }

    public static Node inheritance(String group, ContextSet contexts) {
        return new Node("group." + group, true, 0L, contexts);
    }

    public static Node prefix(int priority, String text, ContextSet contexts) {
        return new Node("prefix." + priority + "." + text, true, 0L, contexts);
    }

    public static Node suffix(int priority, String text, ContextSet contexts) {
        return new Node("suffix." + priority + "." + text, true, 0L, contexts);
    }

    public static Node meta(String metaKey, String metaValue, ContextSet contexts) {
        if (metaKey.indexOf('.') >= 0) {
            throw new IllegalArgumentException("Meta key cannot contain a dot: " + metaKey);
        }
        return new Node("meta." + metaKey + "." + metaValue, true, 0L, contexts);
    }

    public static Node weight(int weight) {
        return new Node("weight." + weight, true, 0L, ContextSet.EMPTY);
    }

    public static Node displayName(String name) {
        return new Node("displayname." + name, true, 0L, ContextSet.EMPTY);
    }

    public Node withExpiry(long newExpiry) {
        return new Node(key, value, newExpiry, contexts);
    }

    public Node withValue(boolean newValue) {
        return new Node(key, newValue, expiry, contexts);
    }

    // ------------------------------------------------------------------ accessors

    public String key() {
        return key;
    }

    public boolean value() {
        return value;
    }

    public long expiry() {
        return expiry;
    }

    public ContextSet contexts() {
        return contexts;
    }

    public NodeType type() {
        return NodeType.of(key);
    }

    public boolean isTemporary() {
        return expiry > 0L;
    }

    public boolean hasExpired(long nowSeconds) {
        return expiry > 0L && expiry <= nowSeconds;
    }

    /** Remaining seconds, or -1 for permanent nodes. */
    public long secondsLeft(long nowSeconds) {
        return expiry == 0L ? -1L : Math.max(0L, expiry - nowSeconds);
    }

    /** Group name of an INHERITANCE node. */
    public String groupName() {
        requireType(NodeType.INHERITANCE);
        return key.substring("group.".length());
    }

    /** Priority of a PREFIX / SUFFIX node. */
    public int chatPriority() {
        requireChat();
        int first = key.indexOf('.');
        int second = key.indexOf('.', first + 1);
        return Integer.parseInt(key.substring(first + 1, second));
    }

    /** Text of a PREFIX / SUFFIX node. */
    public String chatValue() {
        requireChat();
        int first = key.indexOf('.');
        int second = key.indexOf('.', first + 1);
        return key.substring(second + 1);
    }

    public String metaKey() {
        requireType(NodeType.META);
        int first = key.indexOf('.');
        int second = key.indexOf('.', first + 1);
        return key.substring(first + 1, second);
    }

    public String metaValue() {
        requireType(NodeType.META);
        int first = key.indexOf('.');
        int second = key.indexOf('.', first + 1);
        return key.substring(second + 1);
    }

    public int weightValue() {
        requireType(NodeType.WEIGHT);
        return Integer.parseInt(key.substring("weight.".length()));
    }

    public String displayNameValue() {
        requireType(NodeType.DISPLAY_NAME);
        return key.substring("displayname.".length());
    }

    /**
     * Do two nodes occupy the same "slot"? Same key, same contexts and same
     * temporary-ness, ignoring value and expiry. A holder keeps one node per slot.
     */
    public boolean sameSlot(Node other) {
        return key.equals(other.key)
                && contexts.equals(other.contexts)
                && isTemporary() == other.isTemporary();
    }

    /** Same key and contexts, regardless of temporary-ness. */
    public boolean sameKeyAndContext(Node other) {
        return key.equals(other.key) && contexts.equals(other.contexts);
    }

    private void requireType(NodeType t) {
        if (type() != t) throw new IllegalStateException(key + " is not a " + t + " node");
    }

    private void requireChat() {
        NodeType t = type();
        if (t != NodeType.PREFIX && t != NodeType.SUFFIX) {
            throw new IllegalStateException(key + " is not a prefix/suffix node");
        }
    }

    /**
     * Permission and inheritance keys are case-insensitive; prefix/suffix text
     * and meta values are preserved exactly (including trailing spaces, which
     * are intentional in prefixes like "&c[Admin] ").
     */
    private static String normalize(String raw) {
        String key = stripLeading(raw);
        switch (NodeType.of(key)) {
            case PERMISSION:
            case INHERITANCE:
            case WEIGHT:
                return key.trim().toLowerCase(Locale.ROOT);
            case META: {
                int first = key.indexOf('.');
                int second = key.indexOf('.', first + 1);
                return "meta." + key.substring(first + 1, second).toLowerCase(Locale.ROOT) + key.substring(second);
            }
            case PREFIX:
            case SUFFIX: {
                int first = key.indexOf('.');
                return key.substring(0, first).toLowerCase(Locale.ROOT) + key.substring(first);
            }
            case DISPLAY_NAME:
            default:
                return "displayname" + key.substring(key.indexOf('.'));
        }
    }

    private static String stripLeading(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(i);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Node)) return false;
        Node n = (Node) o;
        return value == n.value && expiry == n.expiry && key.equals(n.key) && contexts.equals(n.contexts);
    }

    @Override
    public int hashCode() {
        int h = key.hashCode();
        h = 31 * h + (value ? 1 : 0);
        h = 31 * h + (int) (expiry ^ (expiry >>> 32));
        h = 31 * h + contexts.hashCode();
        return h;
    }

    @Override
    public String toString() {
        return "Node{" + key + "=" + value + (expiry > 0 ? ", expiry=" + expiry : "")
                + (contexts.isEmpty() ? "" : ", " + contexts) + "}";
    }
}
