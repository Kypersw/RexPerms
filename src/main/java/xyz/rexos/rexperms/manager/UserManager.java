package xyz.rexos.rexperms.manager;

import xyz.rexos.rexperms.model.DataEpoch;
import xyz.rexos.rexperms.model.User;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Holds only loaded users (online players). */
public final class UserManager {

    private final ConcurrentHashMap<UUID, User> users = new ConcurrentHashMap<UUID, User>();
    private final DataEpoch epoch;

    public UserManager(DataEpoch epoch) {
        this.epoch = epoch;
    }

    public User get(UUID uuid) {
        return users.get(uuid);
    }

    public void register(User user) {
        users.put(user.getUniqueId(), user);
    }

    public User unregister(UUID uuid) {
        return users.remove(uuid);
    }

    public Collection<User> all() {
        return users.values();
    }

    public boolean isLoaded(UUID uuid) {
        return users.containsKey(uuid);
    }

    public User create(UUID uuid, String name) {
        return new User(uuid, name, epoch);
    }
}
