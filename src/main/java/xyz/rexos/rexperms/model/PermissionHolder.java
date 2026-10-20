package xyz.rexos.rexperms.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Common base for everything that carries nodes (users and groups).
 * All mutators are thread-safe.
 */
public abstract class PermissionHolder {

    /** Simple predicate to stay independent of java.util.function in hot paths. */
    public interface NodeFilter {
        boolean test(Node node);
    }

    private final Set<Node> nodes = new LinkedHashSet<Node>();
    protected final DataEpoch epoch;

    protected PermissionHolder(DataEpoch epoch) {
        this.epoch = epoch;
    }

    /** Storage identifier (UUID string or group name). */
    public abstract String getIdentifier();

    /** Name shown in messages. */
    public abstract String getFriendlyName();

    public abstract HolderType getType();

    public final DataEpoch getEpoch() {
        return epoch;
    }

    // ------------------------------------------------------------------ reading

    public synchronized List<Node> getNodes() {
        return Collections.unmodifiableList(new ArrayList<Node>(nodes));
    }

    public synchronized List<Node> getNodes(NodeType type) {
        List<Node> out = new ArrayList<Node>();
        for (Node n : nodes) {
            if (n.type() == type) out.add(n);
        }
        return out;
    }

    /** First node matching the filter, or null. */
    public synchronized Node findNode(NodeFilter filter) {
        for (Node n : nodes) {
            if (filter.test(n)) return n;
        }
        return null;
    }

    /** Inheritance nodes (direct parents, any context). */
    public List<Node> getParentNodes() {
        return getNodes(NodeType.INHERITANCE);
    }

    // ------------------------------------------------------------------ writing

    /** Adds a permanent or temporary node, replacing whatever was in the same slot. */
    public DataMutateResult setNode(Node node) {
        return setNode(node, TemporaryMergeBehaviour.REPLACE);
    }

    public DataMutateResult setNode(Node node, TemporaryMergeBehaviour mergeBehaviour) {
        synchronized (this) {
            Node existing = null;
            for (Node n : nodes) {
                if (n.sameSlot(node)) {
                    existing = n;
                    break;
                }
            }

            if (existing != null) {
                if (!node.isTemporary()) {
                    if (existing.value() == node.value()) return DataMutateResult.ALREADY_HAS;
                } else {
                    switch (mergeBehaviour) {
                        case FAIL:
                            return DataMutateResult.TEMP_EXISTS;
                        case ACCUMULATE: {
                            long now = System.currentTimeMillis() / 1000L;
                            long added = node.expiry() - now;
                            long base = Math.max(existing.expiry(), now);
                            node = node.withExpiry(base + Math.max(0L, added));
                            break;
                        }
                        case REPLACE:
                        default:
                            if (existing.value() == node.value() && existing.expiry() >= node.expiry()) {
                                return DataMutateResult.ALREADY_HAS;
                            }
                            break;
                    }
                }
                nodes.remove(existing);
            }
            nodes.add(node);
        }
        epoch.bump();
        return DataMutateResult.SUCCESS;
    }

    /** Removes the node in the same slot (key + contexts + temporary-ness). */
    public DataMutateResult unsetNode(Node node) {
        boolean removed = false;
        synchronized (this) {
            Iterator<Node> it = nodes.iterator();
            while (it.hasNext()) {
                if (it.next().sameSlot(node)) {
                    it.remove();
                    removed = true;
                }
            }
        }
        if (removed) epoch.bump();
        return removed ? DataMutateResult.SUCCESS : DataMutateResult.LACKS;
    }

    /** Removes every node matching the filter; returns how many were removed. */
    public int removeIf(NodeFilter filter) {
        int count = 0;
        synchronized (this) {
            Iterator<Node> it = nodes.iterator();
            while (it.hasNext()) {
                if (filter.test(it.next())) {
                    it.remove();
                    count++;
                }
            }
        }
        if (count > 0) epoch.bump();
        return count;
    }

    /** Removes temporary nodes whose time is up. */
    public List<Node> removeExpired(long nowSeconds) {
        List<Node> expired = new ArrayList<Node>();
        synchronized (this) {
            Iterator<Node> it = nodes.iterator();
            while (it.hasNext()) {
                Node n = it.next();
                if (n.hasExpired(nowSeconds)) {
                    expired.add(n);
                    it.remove();
                }
            }
        }
        if (!expired.isEmpty()) epoch.bump();
        return expired;
    }

    /** Replaces all nodes at once (used when loading from storage). */
    public void replaceNodes(Collection<Node> newNodes) {
        synchronized (this) {
            nodes.clear();
            nodes.addAll(newNodes);
        }
        epoch.bump();
    }

    public synchronized boolean hasNodes() {
        return !nodes.isEmpty();
    }

    public synchronized int nodeCount() {
        return nodes.size();
    }
}
