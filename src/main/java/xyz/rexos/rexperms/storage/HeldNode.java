package xyz.rexos.rexperms.storage;

import xyz.rexos.rexperms.model.HolderType;
import xyz.rexos.rexperms.model.Node;

/** Search result: which holder carries which node. */
public final class HeldNode {

    private final HolderType holderType;
    private final String holderId;
    private final String holderName;
    private final Node node;

    public HeldNode(HolderType holderType, String holderId, String holderName, Node node) {
        this.holderType = holderType;
        this.holderId = holderId;
        this.holderName = holderName;
        this.node = node;
    }

    public HolderType holderType() {
        return holderType;
    }

    public String holderId() {
        return holderId;
    }

    public String holderName() {
        return holderName;
    }

    public Node node() {
        return node;
    }
}
