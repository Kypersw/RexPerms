package xyz.rexos.rexperms.api.mutation;

import xyz.rexos.rexperms.model.Node;

import java.util.*;

/** Immutable state used for authorization and detached transactional proposals. */
public final class HolderSnapshot {
    public final List<Node> nodes;
    public final String primaryGroup;

    public HolderSnapshot(Collection<Node> nodes, String primaryGroup) {
        this.nodes = Collections.unmodifiableList(new ArrayList<Node>(nodes));
        this.primaryGroup = primaryGroup;
    }

    public HolderSnapshot nodes(Collection<Node> nodes) {
        return new HolderSnapshot(nodes, primaryGroup);
    }

    public HolderSnapshot primary(String primary) {
        return new HolderSnapshot(nodes, primary);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof HolderSnapshot)) return false;
        HolderSnapshot s = (HolderSnapshot) other;
        return nodes.equals(s.nodes) && Objects.equals(primaryGroup, s.primaryGroup);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodes, primaryGroup);
    }
}
