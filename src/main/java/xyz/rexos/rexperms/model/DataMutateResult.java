package xyz.rexos.rexperms.model;

public enum DataMutateResult {
    /** The change was applied. */
    SUCCESS,
    /** The node already exists with the same value. */
    ALREADY_HAS,
    /** The node to remove does not exist. */
    LACKS,
    /** A temporary node already exists and the merge behaviour is FAIL. */
    TEMP_EXISTS;

    public boolean wasSuccessful() {
        return this == SUCCESS;
    }
}
