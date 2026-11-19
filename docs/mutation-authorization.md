# Mutation authorization API

## Implemented enforcement

`MutationCoordinator` stages immutable holder states, manager registries and track lists in a main-thread unit of work. Other threads see committed state, not pending drafts. The coordinator checks every final changed resource before publishing any resource. A veto or exception discards all drafts and deferred effects; nested failures mark the outer operation for rollback. Authorization listeners cannot mutate managed resources reentrantly.

Every public node mutator on a managed user/group and every managed track/registry mutator enters this mechanism, including `replaceNodes`, primary group, clear, remove, expiry, manager unregister and retain operations. Manager collection views cannot remove objects. Detached objects remain useful as drafts; service persistence rejects arbitrary detached objects and noncanonical tracks/groups.

Administrative commands and their asynchronous user callbacks carry explicit sender context. API and Vault writes use drafts; default-group normalization occurs before authorization. Vault supplies no initiating actor and is labeled VAULT, never console. Off-thread writes fail rather than dispatching Bukkit events asynchronously.

Group rewrites load offline snapshots first and then stage affected groups, loaded users, offline users and tracks together. Imports parse and load before staging and do not write while parsing. Full reload and network replication stage their application on the main thread. SYSTEM expiry can be denied independently for each holder. Direct unattributed calls default to API, never CONSOLE.

Storage submission, success messages, change notifications, refresh and action logs from managed operations are deferred until authorization succeeds. Persistence snapshots are made from the accepted final state, not from intermediate drafts. Cancellation completes deferred write futures exceptionally.

## Public contracts

- `RexPermsApi.authorizationApiVersion()` returns 2.
- `PreHolderMutationEvent` is cancelable and includes immutable node snapshots, primary groups, origin, actor identifier and requested operation context.
- `PreStructuralMutationEvent` covers tracks and registry changes with immutable structural snapshots and attribution.
- `PreAdministrativeOperationEvent` preserves lifecycle preflight compatibility; `PreAdministrativeCommandEvent` is an additional early hook, not the enforcement boundary.
- `registerAuthorizationPolicy(owner, callback)` registers a required fail-closed provider. Its exceptions propagate to the coordinator; disabling its owner rejects subsequent mutations until RexPerms restarts. Bukkit event listeners remain supported, but Bukkit itself catches listener exceptions, so security policies should use the required-provider contract.
- `DataEpoch.coordinator().run/call` groups synchronous managed mutations. Load all offline targets first. Asynchronous continuations are separate units of work and must explicitly preserve context.
- `modifyUserAs` provides player attribution for administrative UIs. `modifyUser` and `modifyGroup` remain unattributed API calls. Consumers modify the supplied draft only.

Plugins can implement their own delegation policy.

## Security boundary and remaining limitations

1. Installed plugin code, console/RCON, server files and the database are trusted. This is **not a JVM sandbox**: reflection, forged sender/context objects and direct use of the public low-level storage/backend API can bypass the managed authorization boundary. Do not give untrusted plugins Java access to the server. The public storage executor remains an internal persistence integration surface, not an authorized administrative mutation API.
2. Authorization is atomic with respect to vetoes **before** persistence. The YAML/SQL backends do not implement a durable multi-file/multi-row transaction for the whole batch. I/O failure or a crash after acceptance can leave partial persistence; restore a backup and inspect logs. Concurrent network writers also require coordination outside this patch.
3. Startup/login hydration trusts persisted server data. A rejected inbound network update does not undo changes already written by another server to the shared database. Do not deploy a shared database with a less restrictive writer.
4. Username caches are identity/display bookkeeping, not rank delegation. A callback must not perform unrelated side effects outside the supplied draft and then expect the coordinator to undo arbitrary plugin code.

## Validation

Run `mvn clean verify` with a complete JDK. The core passed 36 tests in the contributor's build with `--release 8`. The contributor also reported server startup and persistence of a console-assigned group after restart with an external policy plugin installed. Cross-player delegation and multi-server behavior have not been verified.
