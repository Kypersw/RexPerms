package xyz.rexos.rexperms.manager;

import xyz.rexos.rexperms.model.DataEpoch;
import xyz.rexos.rexperms.model.User;

import java.util.Collection;
import java.util.UUID;

/** Holds only loaded users (online players). */
public final class UserManager {

    private final xyz.rexos.rexperms.api.mutation.MutationCoordinator.Cell<
                    java.util.Map<UUID, User>>
            state;

    private java.util.Map<UUID, User> users() {
        return state.get();
    }

    private <T> T edit(java.util.function.Function<java.util.Map<UUID, User>, T> action) {
        final Object[] result = {null};
        state.update(
                old -> {
                    java.util.Map<UUID, User> next = new java.util.LinkedHashMap<UUID, User>(old);
                    result[0] = action.apply(next);
                    return java.util.Collections.unmodifiableMap(next);
                });
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }

    private final DataEpoch epoch;

    public UserManager(DataEpoch epoch) {
        this.epoch = epoch;
        state =
                new xyz.rexos.rexperms.api.mutation.MutationCoordinator.Cell<
                        java.util.Map<UUID, User>>(
                        epoch.coordinator(),
                        "USER_REGISTRY",
                        "*",
                        java.util.Collections.<UUID, User>emptyMap(),
                        epoch::bump);
    }

    public User get(UUID uuid) {
        return users().get(uuid);
    }

    public void register(User user) {
        final User managed = new User(user, epoch);
        edit(map -> map.put(user.getUniqueId(), managed));
    }

    public User unregister(UUID uuid) {
        return edit(map -> map.remove(uuid));
    }

    public Collection<User> all() {
        return java.util.Collections.unmodifiableCollection(users().values());
    }

    public boolean isLoaded(UUID uuid) {
        return users().containsKey(uuid);
    }

    public User create(UUID uuid, String name) {
        return new User(uuid, name, new DataEpoch());
    }
}
