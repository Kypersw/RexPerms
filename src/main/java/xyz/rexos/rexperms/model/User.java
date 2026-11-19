package xyz.rexos.rexperms.model;

import xyz.rexos.rexperms.calc.CachedUserData;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class User extends PermissionHolder {

    private final UUID uuid;
    private volatile String username;
    private final CachedUserData cachedData;

    public User(UUID uuid, String username, DataEpoch epoch) {
        super(epoch);
        this.uuid = uuid;
        this.username = username;
        this.cachedData = new CachedUserData(this);
    }

    public User(User source, DataEpoch epoch) {
        this(source.getUniqueId(), source.getUsername(), epoch);
        initialize(
                new xyz.rexos.rexperms.api.mutation.HolderSnapshot(
                        source.getNodes(), source.getPrimaryGroup()));
    }

    public UUID getUniqueId() {
        return uuid;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        if (username != null && !username.trim().isEmpty()) this.username = username;
    }

    /** Stored primary group (set via command). */
    public String getPrimaryGroup() {
        return storedPrimaryGroup();
    }

    public void setPrimaryGroup(String group) {
        updatePrimaryGroup(group == null ? "default" : group.toLowerCase(Locale.ROOT));
    }

    public CachedUserData getCachedData() {
        return cachedData;
    }

    /**
     * Does this user only carry the default data? Such users are not written to storage, which
     * keeps it small on servers with many visitors.
     */
    public boolean isDefaultOnly() {
        List<Node> list = getNodes();
        if (list.isEmpty()) return "default".equals(getPrimaryGroup());
        if (list.size() != 1) return false;
        Node n = list.get(0);
        return n.key().equals("group.default")
                && n.value()
                && !n.isTemporary()
                && n.contexts().isEmpty()
                && "default".equals(getPrimaryGroup());
    }

    @Override
    public String getIdentifier() {
        return uuid.toString();
    }

    @Override
    public String getFriendlyName() {
        return username != null ? username : uuid.toString();
    }

    @Override
    public HolderType getType() {
        return HolderType.USER;
    }
}
