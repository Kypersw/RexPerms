package xyz.rexos.rexperms.service;

/** Outcome of a promotion or demotion. */
public final class TrackResult {

    public enum Outcome {
        /** Moved to the next / previous group. */
        SUCCESS,
        /** Was on no group of the track and got the first one (promote only). */
        ADDED_TO_FIRST,
        /** Was on the first group and has been taken off the track (demote only). */
        REMOVED_FROM_FIRST,
        /** Already on the last group. */
        END_OF_TRACK,
        /** Not on any group of the track (demote). */
        NOT_ON_TRACK,
        /** On several groups of the track; unclear which to move from. */
        AMBIGUOUS,
        /** The track is empty. */
        EMPTY_TRACK,
        /** The target group no longer exists. */
        GROUP_MISSING
    }

    private final Outcome outcome;
    private final String from;
    private final String to;

    public TrackResult(Outcome outcome, String from, String to) {
        this.outcome = outcome;
        this.from = from;
        this.to = to;
    }

    public static TrackResult of(Outcome outcome) {
        return new TrackResult(outcome, null, null);
    }

    public Outcome outcome() {
        return outcome;
    }

    public String from() {
        return from;
    }

    public String to() {
        return to;
    }
}
