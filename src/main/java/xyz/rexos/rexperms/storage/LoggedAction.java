package xyz.rexos.rexperms.storage;

/** One entry of the audit log. */
public final class LoggedAction {

    private final long time;
    private final String actorName;
    private final char targetType;
    private final String targetName;
    private final String description;

    /**
     * @param time        unix seconds
     * @param actorName   who did it (player name or "Console")
     * @param targetType  'U' user, 'G' group, 'T' track
     * @param targetName  readable target name
     * @param description what was done, e.g. "permission set essentials.fly true"
     */
    public LoggedAction(long time, String actorName, char targetType, String targetName, String description) {
        this.time = time;
        this.actorName = actorName;
        this.targetType = targetType;
        this.targetName = targetName;
        this.description = description;
    }

    public long time() {
        return time;
    }

    public String actorName() {
        return actorName;
    }

    public char targetType() {
        return targetType;
    }

    public String targetName() {
        return targetName;
    }

    public String description() {
        return description;
    }

    public String targetTypeName() {
        switch (targetType) {
            case 'U':
                return "user";
            case 'G':
                return "group";
            case 'T':
                return "track";
            default:
                return "?";
        }
    }
}
