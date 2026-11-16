# Pre-mutation authorization API — design notes

Status: **draft, not yet enforced**. The new `PreHolderMutationEvent` is a proposed contract only. No mutation path dispatches it yet; do not rely on it for security.

## Requirements

- Fire before changing live objects, persistence, caches, audit logs or network state.
- Run listeners synchronously on the Bukkit main thread; never block waiting on an async Bukkit callback.
- Treat absent actor attribution as unknown, **not console**. Console, player, Vault, API, system expiry and network replication must remain distinguishable.
- Expose immutable before/after snapshots so addons can inspect all changes, including permission grants, group inheritance, metadata, weight and removal.
- Denying a mutation must leave the in-memory state and persisted state unchanged.
- Apply this policy to user and group node changes, track changes, group creation/deletion/rename, imports, and bulk edits. A node-only event is insufficient for full protection.
- For command and API mutations, perform the change on detached working copies, authorize the diff, then apply it atomically. Do **not** mutate a live holder and attempt to cancel in `DataService.commit`.
- Document whether system expiry and network sync are subject to veto, and prevent divergent state across network nodes.
- Preserve existing behavior when no listeners are registered.

## Security pitfalls

- Bukkit OP and wildcard permissions must not bypass an addon's authorization policy.
- A player may try to change another player's group or their own permissions; inspect both the **actor** and **target**.
- A player may try to grant access indirectly through inheritance, tracks, permission wildcards, or changing the weight of a group.
- Unattributed third-party integrations should not be silently elevated to console.
- An operator with console, filesystem, database or plugin-install access is outside the threat model.

## Test plan

1. Cancel a player-initiated group assignment; verify no memory, storage or event side effects.
2. Cancel an API-initiated change; verify identical state.
3. Cancel a Vault-initiated change.
4. Cancel a track promotion and an inheritance edit.
5. Cancel a bulk clear, group rename/delete, and import.
6. Confirm legitimate console changes succeed and cannot be forged by a player.
7. Verify async storage and network sync do not reintroduce denied changes.
8. Run compatibility tests on Paper 1.8.8 / Java 8 and a current Paper version.

The API event is generic. Server-specific rank policy belongs in a separate addon (RexPermsGuard), not in RexPerms core.
