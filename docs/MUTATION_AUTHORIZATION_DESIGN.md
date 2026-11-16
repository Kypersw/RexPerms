# Mutation authorization API (work in progress)

This branch introduces `MutationAuthorizationEvent` as an initial API contract.
**It is not yet dispatched by the mutation pipeline and does not enforce security.**
Do not deploy this branch expecting protected groups to be protected.

## Goals
- Generic, group-name-independent policy extensions.
- Author identity distinguishes player, console, and unknown/API/system; null is never console.
- Authorize before changing active objects, not in `DataService.commit` (which is post-mutation).
- Denied operations must leave memory, epoch, cache, persistence, audit and network sync unchanged.
- Cover direct node changes, bulk clear, group create/delete/rename/clone, track operations, promotion/demotion, API, Vault, import and network sync.
- Avoid trusting weight or OP as authorization.
- Preserve Java 8 / Paper 1.8.8 compatibility.

## Remaining implementation
1. Introduce a proposed immutable change set and a central authorization dispatcher.
2. Refactor mutating command/service/API/Vault paths to prepare changes before applying.
3. Specify treatment of trusted internal changes (expiry and network sync) vs unknown plugin calls.
4. Define atomic behavior for batch operations and async persistence.
5. Add tests for denial, no side effects, bypass attempts, and backward compatibility.
6. Document addon integration and examples.

The current event has no caller by design: dispatching it after mutation would create a false sense of protection.
