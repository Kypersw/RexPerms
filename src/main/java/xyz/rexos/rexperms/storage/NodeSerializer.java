package xyz.rexos.rexperms.storage;

import org.bukkit.configuration.ConfigurationSection;
import xyz.rexos.rexperms.model.ContextSet;
import xyz.rexos.rexperms.model.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Reads and writes nodes as human-friendly YAML.
 *
 * <pre>
 * nodes:
 *   - essentials.fly                      # simple: true, permanent, global
 *   - key: essentials.home.multiple
 *     value: false
 *   - key: group.vip
 *     expiry: 1767225600                  # unix seconds
 *   - key: worldedit.*
 *     context:
 *       world: creative
 * </pre>
 */
public final class NodeSerializer {

    private NodeSerializer() {
    }

    public static Object toYaml(Node node) {
        if (node.value() && !node.isTemporary() && node.contexts().isEmpty()) {
            return node.key();
        }
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("key", node.key());
        if (!node.value()) map.put("value", false);
        if (node.isTemporary()) map.put("expiry", node.expiry());
        if (!node.contexts().isEmpty()) map.put("context", new LinkedHashMap<String, String>(node.contexts().asMap()));
        return map;
    }

    public static List<Object> toYaml(List<Node> nodes) {
        List<Object> out = new ArrayList<Object>(nodes.size());
        for (Node n : nodes) out.add(toYaml(n));
        return out;
    }

    /** Invalid entries are skipped so one bad hand-edited line does not break the file. */
    public static List<Node> fromYaml(List<?> raw, Logger logger, String where) {
        List<Node> out = new ArrayList<Node>();
        if (raw == null) return out;
        for (Object o : raw) {
            try {
                Node n = parse(o);
                if (n != null) out.add(n);
            } catch (RuntimeException e) {
                logger.warning("Skipped invalid node (" + where + "): " + o + " -> " + e.getMessage());
            }
        }
        return out;
    }

    private static Node parse(Object o) {
        if (o instanceof String) {
            return Node.permission((String) o, true, ContextSet.EMPTY);
        }
        Map<String, Object> map = toMap(o);
        if (map == null) return null;
        Object keyObj = map.get("key");
        if (keyObj == null) return null;
        Object valueObj = map.get("value");
        boolean value = !(valueObj instanceof Boolean) || (Boolean) valueObj;
        Object expiryObj = map.get("expiry");
        long expiry = expiryObj instanceof Number ? ((Number) expiryObj).longValue() : 0L;
        ContextSet ctx = ContextSet.EMPTY;
        Map<String, Object> c = toMap(map.get("context"));
        if (c != null) {
            Map<String, String> cm = new LinkedHashMap<String, String>();
            for (Map.Entry<String, Object> e : c.entrySet()) cm.put(e.getKey(), String.valueOf(e.getValue()));
            ctx = ContextSet.of(cm);
        }
        return Node.of(String.valueOf(keyObj), value, expiry, ctx);
    }

    private static Map<String, Object> toMap(Object o) {
        if (o instanceof ConfigurationSection) {
            return ((ConfigurationSection) o).getValues(false);
        }
        if (o instanceof Map) {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) map.put(String.valueOf(e.getKey()), e.getValue());
            return map;
        }
        return null;
    }
}
