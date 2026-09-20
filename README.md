# Dynamic Areas

Dynamic Areas is a plugin for Paper 26.2 that lets map makers define behavior zones in YAML and place reusable instances of those zones into a world at runtime. Zones can be activated from command blocks, which makes them easy to include in redstone systems and other map logic. Optional WorldEdit integration can be used to capture selections and coordinates.

The plugin is designed for Minecraft maps that reuse the same structure in multiple locations. Build the structure once, define its zones relative to a pivot, and then copy the structure together with its command blocks. Each copy can activate the same zone layout at its own world position without requiring a separate set of absolute coordinates.

## How it works

- A **box** is a reusable, axis-aligned volume defined relative to a placement origin.
- An **area** describes the behavior applied inside a box, such as block rules, protection, and event commands.
- An **active box** combines a box template with an area, a world position, and a TTL. Repeating command blocks can refresh the TTL to keep a zone active only while the relevant map logic is running.
- Saved vectors provide reusable offsets for placing related structures and zones. Active boxes can also be assigned to named spatial groups for lookup through the plugin API.

The same box and area definitions can be instantiated any number of times in different worlds or at different positions.

## Area features

Areas can:

- allow, deny, or ignore block breaking;
- allow, deny, or ignore block placement;
- allow, deny, or ignore interactions with interactable blocks;
- protect blocks from explosions, piston movement, and entity-driven block changes;
- run console commands when a player enters or leaves a zone, or interacts with a block inside it;
- substitute event context such as the player name, world, and coordinates into listener commands;
- overlap with other areas while their rules are aggregated;
- be visualized in-game with particle outlines.

For overlapping areas, `allow` takes precedence over `deny` for breaking, placement, and interaction rules. For protection, an explicit `disable` takes precedence over `enable`.

## Configuration

Configuration files are loaded recursively from:

- `plugins/dynamic-areas/boxes`
- `plugins/dynamic-areas/areas`
- `plugins/dynamic-areas/vectors`
- `plugins/dynamic-areas/clusters`

### Box format

File: `plugins/dynamic-areas/boxes/<box_id>.yml`

```yaml
offset:
  x: 10
  y: 10
  z: 10
size:
  x: 20
  y: 20
  z: 20
```

`offset` is relative to the origin used when the box is activated. `size` is an inclusive coordinate delta: a size of `20` spans from the minimum coordinate through minimum + 20.

Boxes can be written manually or saved from two coordinates or a WorldEdit selection with `/da savebox`.

### Area format

File: `plugins/dynamic-areas/areas/<area_id>.yml`

```yaml
block_breaking: deny
block_placing: deny
interactions: allow
protect: enable
listeners:
  - on_interact: "say {player_name} clicked a block at {x} {y} {z}"
  - on_player_enter: "say Welcome, {player_name}"
  - on_player_leave: "say Goodbye, {player_name}"
```

The values for `block_breaking`, `block_placing`, and `interactions` are:

- `allow` — allow the action;
- `deny` — cancel the action;
- `ignore` — leave the event unchanged. This is also the default.

The values for `protect` are `enable`, `disable`, and `default`. An omitted or unrecognized value is treated as `default`.

Listener commands are executed by the server console. `{player_name}` is available for all listener events. `{world}`, `{x}`, `{y}`, and `{z}` are also available for interaction and enter events.

## Building a cluster in-game

A **cluster** is a saved list of named entries. Each entry references a box and an area and may specify a placement offset and named groups. Clusters have no editing pivot or editing session. The placement pivot is supplied only when activating the cluster.

Create boxes separately with the existing `/da setpivot` and `/da savebox` commands, then build a cluster:

```text
/da cluster create trial
/da cluster add trial entrance_box spawn_rules
/da cluster add trial walls_box protection_rules as walls
/da cluster group add trial entrance_box players
/da cluster show trial
```

The entry name defaults to the box ID; `as <entry>` gives it a different name. Entry names are stable identifiers, not changing row numbers. The same box can be referenced by multiple entries. Duplicate entry names are rejected.

Put this single command in the main repeating command block, located at the structure pivot:

```text
da setcluster trial ~ ~ ~
```

Copy the structure and its command block. Each copy uses the same template at its own pivot. The default TTL is **2 ticks**: the command block must keep refreshing the cluster, and its boxes expire when it stops.

### Capture a box and entry together

`capture` is an optional shortcut. Select the structure pivot once with `/da setpivot`, make a WorldEdit selection, and run:

```text
/da cluster capture trial entrance_box spawn_rules
/da cluster capture trial other_box spawn_rules as second_entrance
```

Alternatively, provide both corners with `from <x> <y> <z> to <x> <y> <z>`. Capture requires a player with an explicitly selected pivot and rejects an existing box ID or entry name. It saves both the box and the cluster entry; a failed save does not leave a half-created capture. Ordinary `cluster add` needs no player pivot or WorldEdit.

### Edit and remove entries

Every successful edit is saved immediately under `plugins/dynamic-areas/clusters` and becomes available in memory. No `/da reload` is required. Each active copy reconciles the edited template on its next `setcluster` call, including geometry, area, group, and entry-removal changes.

```text
/da cluster set trial entrance_box box replacement_box
/da cluster set trial entrance_box area other_rules
/da cluster set trial entrance_box offset 5 0 0
/da cluster group add trial entrance_box triggers
/da cluster group remove trial entrance_box triggers
/da cluster group clear trial entrance_box
/da cluster remove trial entrance_box
```

The entry offset is an integer displacement from the cluster's placement pivot; the box's own saved offset is added to it. Entry displacement does **not** move the point used by the entry's default groups. Reset it with `offset 0 0 0`.

Removing an entry or deleting a cluster never deletes the referenced box templates or areas. Shared boxes remain shared: changing a box through `/da savebox` affects every placement that references it when refreshed. An invalid edit leaves the previous saved definition unchanged. A cluster is checked in full before activation so missing references cannot cause partial placement.

## Named groups

A group is identified by **world UUID + absolute point + name**. There is no separate cluster-only group type or subgroup hierarchy. A box can belong to several groups. Different clusters may deliberately share a group by resolving to the same world, point, and name; identical names at different points remain independent.

Every cluster entry automatically belongs to `(world, cluster pivot, cluster ID)`. Explicit groups are additional memberships; clearing them never removes the automatic group.

```text
/da cluster group add trial entrance_box players
/da cluster group add trial entrance_box shared offset 10 0 0
/da cluster group remove trial entrance_box shared offset 10 0 0
```

Without `offset`, the group's point is the cluster pivot. A supplied offset is always relative to that pivot, even when the entry itself has a placement offset. Removal addresses the exact name and offset pair.

Direct activation supports names at the box placement origin:

```text
da addboxtoarea gate gate_rules inv gate_cb groups trial doors
```

Here `gate_cb` is a saved vector from the structure pivot to this separate command block. Its inverse recovers the structure pivot. The box joins the main cluster's `trial` group and an additional `doors` group. Keep this redstone-controlled box out of the main cluster if it must disappear when its own command block stops.

Explicit direct group points use `groups at <x> <y> <z> <names...>` or `groups at [inv] <vector> <names...>`. Here absolute coordinates are world positions, and `~` coordinates/vectors resolve from the command source, not the box placement origin. For example: `da addboxtoarea gate gate_rules inv gate_cb groups at ~ ~ ~ local_gate`.

A shared group does not share TTL: direct placements, different clusters, and different entries maintain independent lifetimes. Refreshing an instance updates its geometry and memberships as well as its TTL.

Other plugins can query the named group:

```kotlin
val boxes = DynamicAreas.instance
    ?.getBoxesByGroup(world.uid, Vec3i(x, y, z), "players")
    .orEmpty()
```

Each active box also exposes its `groups`. This replaces the old positional-only group contract; consumers such as Redstone Challenge must be updated separately. Area enter/leave behavior still aggregates by `area_id` and does not distinguish cluster copies.

## Commands and help

Use `/da help` for the command overview and `/da cluster help [1-3]` for the three-page guide (create, edit, activate). Cluster lists and details use colored text, hover explanations, and pagination. Read-only navigation can be clicked; editing/deletion actions suggest commands for review before execution. Console output remains readable.

| Command | Purpose |
| --- | --- |
| `/da cluster create <cluster>` | Create an empty template. |
| `/da cluster add <cluster> <box> <area> [as <entry>]` | Add a reference to an existing box. |
| `/da cluster capture <cluster> <box> <area> [as <entry>] [from <start> to <end>]` | Create a box and entry using the player's selected pivot. |
| `/da cluster list [page]` | List saved clusters. |
| `/da cluster show <cluster> [entry <entry> \| page <page>]` | Inspect entries or one entry's details. |
| `/da cluster set <cluster> <entry> box <box>` | Change the box reference. |
| `/da cluster set <cluster> <entry> area <area>` | Change the area reference. |
| `/da cluster set <cluster> <entry> offset <dx> <dy> <dz>` | Change the entry's placement displacement. |
| `/da cluster group add/remove <cluster> <entry> <name> [offset <dx> <dy> <dz>]` | Add/remove an exact named group membership. |
| `/da cluster group clear <cluster> <entry>` | Remove all additional memberships. |
| `/da cluster remove <cluster> <entry>` | Remove an entry. |
| `/da cluster delete <cluster>` | Delete a template, keeping its boxes and areas. |
| `/da setcluster <cluster> [<x> <y> <z> \| <vector> \| inv <vector>] [ttl]` | Activate/refresh a complete cluster. |
| `/da addboxtoarea <box> <area> [<x> <y> <z> \| <vector> \| inv <vector>] [ttl] [groups <names...>]` | Activate/refresh a single box. |
| `/da setpivot <x> <y> <z>` | Select the player's pivot for saving boxes/vectors and capture. |
| `/da savebox <box> [<start> <end>]` | Save a box from explicit corners or WorldEdit. |
| `/da savevector <name> [<x> <y> <z>]` | Save an offset from the player's pivot. |
| `/da visualize [area]` | Toggle outlines of active boxes. |
| `/da reload` | Reload YAML definitions and clear runtime boxes. |

Activation coordinates are native Minecraft block positions (absolute or `~` relative to the command source). Named vectors are displacements from the command source; `inv` negates them. Cluster entry and group offsets are raw integer displacements, not world coordinates.

Commands are available to command blocks, operators, and users with `dynamicareas.use`. Cluster and group names must not contain whitespace. The direct-group keyword `at` is case-sensitive; to use a group named `at`, provide an explicit point (`groups at ~ ~ ~ at`). Box/cluster IDs can use nested paths; quote them in commands, for example `"trials/entrance"`. Click actions quote IDs automatically.

## Requirements

- Paper 26.2
- Java 25 or newer
- WorldEdit 7.4.4 or newer (optional, only needed for selection-based commands)

## Building

```powershell
.\gradlew.bat clean build --no-daemon
```
