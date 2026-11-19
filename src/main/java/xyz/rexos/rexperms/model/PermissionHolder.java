package xyz.rexos.rexperms.model;

import xyz.rexos.rexperms.api.mutation.*;

import java.util.*;

/** Managed holders stage every public mutator through the authorization coordinator. */
public abstract class PermissionHolder {
    public interface NodeFilter {
        boolean test(Node node);
    }

    protected final DataEpoch epoch;
    private MutationCoordinator.Cell<HolderSnapshot> state;

    protected PermissionHolder(DataEpoch epoch) {
        this.epoch = epoch;
    }

    protected final void initialize(HolderSnapshot initial) {
        if (state != null) throw new IllegalStateException("Already initialized");
        state =
                new MutationCoordinator.Cell<HolderSnapshot>(
                        epoch.coordinator(),
                        getType().name(),
                        getIdentifier(),
                        initial,
                        epoch::bump);
    }

    private synchronized MutationCoordinator.Cell<HolderSnapshot> cell() {
        if (state == null)
            state =
                    new MutationCoordinator.Cell<HolderSnapshot>(
                            epoch.coordinator(),
                            getType().name(),
                            getIdentifier(),
                            new HolderSnapshot(
                                    Collections.<Node>emptyList(),
                                    this instanceof User ? "default" : null),
                            epoch::bump);
        return state;
    }

    public final DataEpoch getEpoch() {
        return epoch;
    }

    public abstract String getIdentifier();

    public abstract String getFriendlyName();

    public abstract HolderType getType();

    public List<Node> getNodes() {
        return cell().get().nodes;
    }

    public List<Node> getNodes(NodeType type) {
        List<Node> out = new ArrayList<Node>();
        for (Node n : getNodes()) if (n.type() == type) out.add(n);
        return out;
    }

    public Node findNode(NodeFilter filter) {
        for (Node n : getNodes()) if (filter.test(n)) return n;
        return null;
    }

    public List<Node> getParentNodes() {
        return getNodes(NodeType.INHERITANCE);
    }

    protected String storedPrimaryGroup() {
        return cell().get().primaryGroup;
    }

    protected void updatePrimaryGroup(String group) {
        cell().update(s -> s.primary(group));
    }

    public DataMutateResult setNode(Node node) {
        return setNode(node, TemporaryMergeBehaviour.REPLACE);
    }

    public DataMutateResult setNode(final Node requested, TemporaryMergeBehaviour merge) {
        Objects.requireNonNull(requested);
        final DataMutateResult[] result = {DataMutateResult.SUCCESS};
        cell().update(
                        s -> {
                            Node node = requested;
                            List<Node> next = new ArrayList<Node>(s.nodes);
                            Node existing = null;
                            for (Node n : next)
                                if (n.sameSlot(node)) {
                                    existing = n;
                                    break;
                                }
                            if (existing != null) {
                                if (!node.isTemporary()) {
                                    if (existing.value() == node.value()) {
                                        result[0] = DataMutateResult.ALREADY_HAS;
                                        return s;
                                    }
                                } else if (merge == TemporaryMergeBehaviour.FAIL) {
                                    result[0] = DataMutateResult.TEMP_EXISTS;
                                    return s;
                                } else if (merge == TemporaryMergeBehaviour.ACCUMULATE) {
                                    long now = System.currentTimeMillis() / 1000L;
                                    node =
                                            node.withExpiry(
                                                    Math.max(existing.expiry(), now)
                                                            + Math.max(0L, node.expiry() - now));
                                } else if (existing.value() == node.value()
                                        && existing.expiry() >= node.expiry()) {
                                    result[0] = DataMutateResult.ALREADY_HAS;
                                    return s;
                                }
                                next.remove(existing);
                            }
                            next.add(node);
                            return s.nodes(next);
                        });
        return result[0];
    }

    public DataMutateResult unsetNode(Node node) {
        return removeIf(n -> n.sameSlot(node)) > 0
                ? DataMutateResult.SUCCESS
                : DataMutateResult.LACKS;
    }

    public int removeIf(NodeFilter filter) {
        final int[] count = {0};
        cell().update(
                        s -> {
                            List<Node> next = new ArrayList<Node>();
                            for (Node n : s.nodes) {
                                if (filter.test(n)) count[0]++;
                                else next.add(n);
                            }
                            return s.nodes(next);
                        });
        return count[0];
    }

    public List<Node> removeExpired(long now) {
        final List<Node> expired = new ArrayList<Node>();
        removeIf(
                n -> {
                    if (n.hasExpired(now)) {
                        expired.add(n);
                        return true;
                    }
                    return false;
                });
        return expired;
    }

    public void replaceNodes(Collection<Node> nodes) {
        final Set<Node> copy = new LinkedHashSet<Node>(nodes);
        cell().update(s -> s.nodes(copy));
    }

    public boolean hasNodes() {
        return !getNodes().isEmpty();
    }

    public int nodeCount() {
        return getNodes().size();
    }
}
