# RexPerms
[![RexPerms trailer](https://img.youtube.com/vi/cMLJXUCrY_E/maxresdefault.jpg)](https://youtu.be/cMLJXUCrY_E)
A permission plugin for Minecraft servers that works the same way as LuckPerms: groups, inheritance,
weights, temporary permissions, contexts (world/server/gamemode), tracks for promotion and demotion,
prefixes/suffixes/meta, Vault and PlaceholderAPI support, YAML/SQLite/MySQL storage and network sync.

One jar runs on **every version from 1.8.8 to 26.x**, on **Spigot, Paper and Purpur**.

Node keys use the same encoding as LuckPerms (`group.vip`, `prefix.100.&c[Admin] `, `meta.homes.5`,
`weight.100`), so a `/lp export` file can be imported with a single command.

For Support and Direct Download Link: https://craftrank.org/resources/1146-rexperms
---

## Compatibility

Every version below was tested on a real server with a connected player running the same
18-step scenario (join, live permission grant, vanilla command unlock, world-context deny, `/rp check`,
chat prefix, tab list prefix, PlaceholderAPI, temporary permission expiry, promotion, Vault, restart persistence).

| Server | Java | Result |
|---|---|---|
| Paper 1.8.8 (YAML and SQLite) | 8 | all passed |
| Paper 1.12.2 | 8 | all passed |
| Paper 1.16.5 | 8 | all passed |
| Paper 1.20.6 | 21 | all passed |
| Paper 1.21.11 | 21 | all passed |
| Spigot 1.21.11 | 21 | all passed |
| Paper 26.1.2 (YAML and SQLite) | 25 | all passed |
| Paper 26.3 | 25 | startup + console commands passed (no test client for 26.3 yet) |

Versions in between (1.9–1.11, 1.13–1.15, 1.17–1.19) use the same API surface and are expected to work.
**Folia is not supported** (it needs a different scheduler model); the plugin disables itself there with a clear message.

How it stays compatible:

- Compiled to Java 8 bytecode against the 1.8.8 API, so it never calls a method old servers lack.
  The few newer features (command-tree refresh on 1.13+) are reached through reflection.
- Messages use a bundled, relocated copy of Adventure + MiniMessage. Hover and click work on every version;
  RGB colours appear on 1.16+ and fall back to the nearest classic colour on older servers.
- HikariCP and Gson are bundled and relocated (old servers do not provide them).
- SQL statements avoid modern upsert syntax because 1.8–1.12 ship 2015-era SQLite and MySQL drivers.

---

## Installation

1. Put `RexPerms-2.0.0.jar` in `plugins/`.
2. Start the server. `plugins/RexPerms/config.yml` and `messages.yml` are created.
3. Vault and PlaceholderAPI are hooked automatically when installed.

### Quick start

```
/rp creategroup vip 10 "&6VIP"
/rp group vip permission set essentials.fly
/rp group vip meta setprefix "&6[VIP] "

/rp creategroup admin 100
/rp group admin parent add vip
/rp group admin permission set worldedit.* true world=creative

/rp createtrack staff vip admin
/rp user Steve promote staff
/rp user Alex parent addtemp vip 30d accumulate
```

---

## Concepts

| Concept | Meaning |
|---|---|
| **Node** | One piece of data: `essentials.fly`, `group.vip`, `prefix.100.&c[Admin] `, `meta.homes.5` |
| **Value** | `true` grants, `false` explicitly denies (and overrides an inherited `true`) |
| **Inheritance** | Groups can inherit from other groups (`admin → vip → default`) |
| **Weight** | Heavier groups win over lighter siblings; also decides the primary group and prefix |
| **Context** | A node only applies under a condition: `world=survival`, `server=lobby`, `gamemode=creative` |
| **Temporary node** | Removed automatically when its time is up (`30m`, `12h`, `7d`, `1mo`, `1y`) |
| **Track** | An ordered list of groups used by `promote` / `demote` |

### Resolution order

1. Temporary permissions added by other plugins (PermissionAttachment)
2. The user's own nodes (nodes with more specific contexts first)
3. Groups — depth-first, siblings ordered by weight
4. Wildcards: `a.b.c` → `a.b.*` → `a.*` → `*` (the most specific one wins)
5. The plugin's own default from its plugin.yml (`default: op/true/false`)

A permission is fixed where it is first defined: `fly=false` set directly on a player always
overrides `fly=true` coming from a group.

---

## Commands

Main command: `/rexperms` — aliases `/rp`, `/rperms`, `/perms`, `/perm`.
Add contexts by appending `key=value`. Quote text containing spaces: `"&c[Admin] "`.

### Users — `/rp user <name|uuid> ...`

| Command | Description |
|---|---|
| `info` | Groups, primary group, prefix, status |
| `permission info [page]` | Permissions (click a line to get the remove command) |
| `permission set <node> [true\|false] [context]` | Grant or deny |
| `permission unset <node> [context]` | Remove |
| `permission settemp <node> <true\|false> <duration> [accumulate\|replace\|deny] [context]` | Temporary permission |
| `permission unsettemp <node> [context]` | Remove a temporary permission |
| `permission check <node> [context]` | Result **and where it comes from** |
| `permission clear [context]` | Remove all permissions |
| `parent info` | Groups |
| `parent set <group> [context]` | Remove other permanent groups and keep only this one |
| `parent add / remove <group> [context]` | Add / remove a group |
| `parent addtemp <group> <duration> [accumulate\|replace\|deny] [context]` | Temporary group |
| `parent removetemp <group> [context]` | Remove a temporary group |
| `parent switchprimarygroup <group>` | Pick the primary group (with `primary-group-calculation: stored`) |
| `parent clear [context]` | Remove all groups (default stays) |
| `meta info` | Prefixes, suffixes, meta |
| `meta setprefix [priority] <text> [context]` | Prefix (priority chosen automatically when omitted) |
| `meta setsuffix [priority] <text> [context]` | Suffix |
| `meta addtempprefix / addtempsuffix <priority> <text> <duration>` | Temporary prefix/suffix |
| `meta removeprefix / removesuffix <priority> [text]` | Remove |
| `meta set / unset <key> [value]` | Meta (e.g. `homes 5`) |
| `meta settemp / unsettemp <key> <value> <duration>` | Temporary meta |
| `meta clear [context]` | Remove all prefix/suffix/meta |
| `promote / demote <track> [context]` | Move up / down a track |
| `showtracks` | Where the user stands on each track |
| `clear [context]` | Reset the user |

### Groups — `/rp group <group> ...`

All `permission`, `parent` and `meta` sub-commands work like for users, plus:

| Command | Description |
|---|---|
| `info` | Weight, parents, prefix, tracks |
| `setweight <number>` | Weight |
| `setdisplayname <name>` | Display name (`-` clears it) |
| `listmembers [page]` | Every member, offline ones included |
| `showtracks` | Tracks containing the group |
| `rename <new>` | Rename — **every user, group and track reference is updated** |
| `clone <new>` | Copy |
| `delete` | Delete (references are cleaned up) |

### Tracks — `/rp track <track> ...`

`info`, `append <group>`, `insert <group> <position>`, `remove <group>`, `clear`, `rename <new>`, `clone <new>`, `delete`

### General

| Command | Description |
|---|---|
| `/rp creategroup <name> [weight] [display-name]` | Create a group |
| `/rp deletegroup <name>` | Delete a group |
| `/rp listgroups [page]` | List groups |
| `/rp createtrack <name> [group1 group2 ...]` | Create a track |
| `/rp deletetrack <name>` / `/rp listtracks` | Delete / list tracks |
| `/rp check <player> <permission>` | Shortcut: the result and where it comes from |
| `/rp search <node>` | Everyone holding a node. End with `*` for a prefix search (`essentials.*`) |
| `/rp verbose on [player\|permission-part]` / `off` | Watch permission checks live (hover a line for its source) |
| `/rp log [target] [page]` | Change log |
| `/rp sync` | Reload everything from storage and notify other servers |
| `/rp reload` | Re-read config.yml and messages.yml |
| `/rp export [name]` | Export everything to `.json.gz` (console/OP) |
| `/rp import <file>` | Import (console only) |
| `/rp info` | Plugin status |

---

## Command permissions

Pattern: `rexperms.<area>.<sub>.<action>` — `rexperms.*` for everything. Operators have all of them by default.

```
rexperms.user.info                 rexperms.group.info
rexperms.user.permission.set       rexperms.group.permission.set
rexperms.user.permission.check     rexperms.group.parent.add
rexperms.user.parent.add           rexperms.group.meta.setprefix
rexperms.user.parent.addtemp       rexperms.group.setweight
rexperms.user.meta.setprefix       rexperms.group.listmembers
rexperms.user.promote              rexperms.group.rename
rexperms.user.demote               rexperms.track.append
rexperms.creategroup  rexperms.deletegroup  rexperms.createtrack  rexperms.deletetrack
rexperms.listgroups  rexperms.listtracks  rexperms.search  rexperms.verbose  rexperms.log
rexperms.sync  rexperms.reload  rexperms.export  rexperms.import  rexperms.info
rexperms.log.notify      -> receives change notifications
rexperms.chat.color      -> may use colour codes in chat (when the chat module is on)
```

Example: a moderator who may only promote and demote

```
/rp group mod permission set rexperms.user.promote
/rp group mod permission set rexperms.user.demote
/rp group mod permission set rexperms.user.info
```

---

## Storage and networks

```yaml
storage:
  type: mysql        # yaml | sqlite | mysql | mariadb
  mysql:
    host: localhost
    port: 3306
    database: minecraft
    username: root
    password: ''
server: survival     # a different name per server -> server=survival context
sync:
  enabled: true
  interval-seconds: 3
```

On a BungeeCord/Velocity network every server uses the same MySQL database. A change on one server
reaches the others within a few seconds through the `rexperms_messages` table — no Redis needed.

YAML files can be edited by hand; run `/rp sync` afterwards:

```yaml
# plugins/RexPerms/yaml-storage/groups/admin.yml
name: admin
nodes:
- weight.100
- 'prefix.100.&c[Admin] '
- group.vip
- key: worldedit.*
  context:
    world: creative
- key: essentials.nick
  value: false
```

---

## Migrating from LuckPerms

1. With LuckPerms installed: `/lp export migration`
2. Copy `plugins/LuckPerms/migration.json.gz` to `plugins/RexPerms/`
3. Remove LuckPerms, install RexPerms, start the server
4. From the console: `rp import migration.json.gz`

Groups, tracks, users, temporary nodes and contexts are imported. Already-expired nodes are skipped.

---

## PlaceholderAPI

| Placeholder | Output |
|---|---|
| `%rexperms_prefix%` / `%rexperms_suffix%` | Current prefix/suffix (coloured) |
| `%rexperms_prefix_raw%` / `%rexperms_suffix_raw%` | Raw text (`&c[Admin] `) |
| `%rexperms_primary_group%` | Primary group name |
| `%rexperms_primary_group_display%` | Display name of the primary group |
| `%rexperms_groups%` | Direct groups (comma separated) |
| `%rexperms_weight%` | Weight of the heaviest group (useful for tab sorting) |
| `%rexperms_has_permission_<permission>%` | yes / no |
| `%rexperms_in_group_<group>%` | Direct member? |
| `%rexperms_inherits_group_<group>%` | Member including inheritance? |
| `%rexperms_meta_<key>%` | Meta value |
| `%rexperms_expiry_time_<permission>%` | Time left on a temporary permission |
| `%rexperms_group_expiry_time_<group>%` | Time left on a temporary group (e.g. VIP) |
| `%rexperms_track_next_<track>%` / `%rexperms_track_previous_<track>%` | Next / previous group on a track |

---

## Vault

With Vault installed, the `Permission` and `Chat` services are registered as RexPerms (`/vault-info`).
For queries, the world argument is used as a context. Changes made through Vault (for example a shop
plugin giving a rank) are added **globally**, so a rank bought in one world works everywhere.

---

## Developer API

```java
RexPermsApi api = Bukkit.getServicesManager().load(RexPermsApi.class);

// 30 days of VIP; stacks on any time already left
long expiry = System.currentTimeMillis() / 1000 + 30L * 86400;
api.modifyUser(uuid, user -> user.setNode(
        Node.of("group.vip", true, expiry, ContextSet.EMPTY),
        TemporaryMergeBehaviour.ACCUMULATE));

String prefix = api.getPrefix(player);           // "&6[VIP] "
String homes  = api.getMeta(player, "homes");    // "5"
boolean staff = api.inheritsGroup(player, "mod");
```

Events (fired on the main thread):

- `HolderDataChangeEvent` — a user's or group's data changed (command, API, expiry, network sync)
- `UserTrackChangeEvent` — promotion/demotion, with the old and new group

---

## Optional modules

```yaml
chat:
  enabled: true
  format: '{prefix}{name}{suffix}&8 » &7{message}'
tablist:
  enabled: true
  format: '{prefix}{name}{suffix}'
```

If you use another chat plugin (EssentialsChat, ChatControl) keep `chat.enabled: false`;
those plugins read the prefix through Vault or PlaceholderAPI.

---

## Building

```
mvn package
```

Output: `target/RexPerms-2.0.0.jar` (dependencies shaded and relocated). Building needs JDK 11+;
the result runs on Java 8+. Unit tests cover the calculation engine, YAML and SQLite storage, and
duration/context/argument parsing.

## Project layout

```
model/       Node, ContextSet, User, Group, Track — plain data, no Bukkit dependency
calc/        Inheritance, wildcards, meta, per-context cache
storage/     YAML, SQLite/MySQL/MariaDB (HikariCP), audit log, network sync
service/     Load/save flow, expiry, group rename/delete, promotion
inject/      Replaces the player's PermissibleBase (reflection)
command/     /rp command tree and tab completion
hook/        Vault and PlaceholderAPI
migration/   Export/import (LuckPerms export compatible)
util/        Colour conversion, durations, server version helpers
```
