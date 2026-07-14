# Dynamic Areas

Dynamic Areas is a plugin for Paper and Folia 1.21.11 that lets map makers define behavior zones in YAML and place reusable instances of those zones into a world at runtime. Zones can be activated from command blocks, which makes them easy to include in redstone systems and other map logic. Optional WorldEdit integration can be used to capture selections and coordinates.

The plugin is designed for Minecraft maps that reuse the same structure in multiple locations. Build the structure once, define its zones relative to a pivot, and then copy the structure together with its command blocks. Each copy can activate the same zone layout at its own world position without requiring a separate set of absolute coordinates.

## How it works

- A **box** is a reusable, axis-aligned volume defined relative to a placement origin.
- An **area** describes the behavior applied inside a box, such as block rules, protection, and event commands.
- An **active box** combines a box template with an area, a world position, and a TTL. Repeating command blocks can refresh the TTL to keep a zone active only while the relevant map logic is running.
- Saved vectors provide reusable offsets for placing related structures and zones. Active boxes can also be assigned to spatial groups for lookup through the plugin API.

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

- `allow` — explicitly allow the action;
- `deny` — cancel the action;
- `ignore` — leave the event unchanged. This is also the default.

The values for `protect` are `enable`, `disable`, and `default`. An omitted or unrecognized value is treated as `default`.

Listener commands are executed by the server console. `{player_name}` is available for all listener events. `{world}`, `{x}`, `{y}`, and `{z}` are also available for interaction and enter events.

## Typical map-making workflow

1. Build a reusable structure and choose a single pivot for it.
2. Set that pivot once with `/da setpivot <x> <y> <z>`.
3. Define every zone relative to the same pivot. Select each volume with WorldEdit and run `/da savebox <box_id>`, or provide its two corners explicitly.
4. Create the area YAML files that define the behavior of those zones.
5. Place the command blocks that will activate the zones. For each command block, save its position as a vector from the structure pivot:

   ```text
   /da savevector room_zone_cb <command_block_x> <command_block_y> <command_block_z>
   ```

6. Use the inverse of that vector as the placement offset in the command block, and use the original vector as its group:

   ```text
   /da addboxtoarea room room_rules inv room_zone_cb groups room_zone_cb
   ```

7. Copy the complete structure together with its command blocks. The same command works unchanged in every copy.

This works because the saved vector points from the pivot to the original command block. In a copied structure, `inv room_zone_cb` moves from the new command block position back to the new copy's pivot, which is the origin used by all saved boxes. `groups room_zone_cb` then moves from that pivot to the command block again, so the active box is indexed under the absolute position of the command block that created it.

Use a separate saved vector for each command block position. Multiple boxes activated by the same command block vector receive the same group key and can be retrieved together by querying that command block's world position.

The default TTL is `2` ticks. A repeating command block that executes every tick can therefore keep a zone active; the zone disappears automatically when the command stops refreshing it.

## Groups

Groups are a runtime index for finding active boxes that belong to the same world position. They do not change area rules and they are not string labels. Each group is identified by:

- the UUID of the world containing the active box;
- an absolute block position in that world.

Group positions are supplied after the `groups` keyword in `/da addboxtoarea`. The command treats every supplied position as an offset from the box placement origin and converts it to an absolute world position. Boxes that resolve to the same world and absolute position become members of the same group.

Each group entry can be written as:

- `x y z` — a relative integer offset;
- `<vector_id>` — a saved vector;
- `inv <vector_id>` — the negated saved vector.

Multiple entries can be placed one after another without separators. Duplicate entries are collapsed, and unknown vector names are silently skipped so that a typo cannot break a repeating command block.

For example, if a command block at `100 64 100` runs:

```text
/da addboxtoarea room room_rules groups 0 0 0 10 0 0
```

the active box is indexed under group positions `100 64 100` and `110 64 100` in that world. Another active box that resolves one of its group entries to either position can be retrieved as part of the same group.

Saved vectors work the same way:

```text
/da addboxtoarea room room_rules north 5 groups entrance inv exit
```

Here `north` controls the box placement offset, `5` is the TTL, and the saved vectors `entrance` and the inverse of `exit` define two group positions relative to the resolved box origin.

Groups are exposed to other plugins through the runtime API:

```kotlin
val boxes = DynamicAreas.instance
    ?.getBoxesByGroup(world.uid, Vec3i(x, y, z))
    .orEmpty()
```

The result contains the currently active boxes indexed under that exact world position. Membership is removed automatically when a box expires or `/da reload` clears the runtime.

Group membership is assigned only when an active box is first created. Refreshing the same `(area, box, world, origin)` instance updates its TTL but does not replace its existing groups. Let the instance expire before activating it with a different group set.

## Commands

- `/da addboxtoarea <box_id> <area_id> [<x> <y> <z> | <vector_id> | inv <vector_id>] [ttl] [groups <group...>]` — add or refresh a box instance. The sender position is used when no offset is provided.
- `/da setpivot <x> <y> <z>` — set the current player's pivot for saving boxes and vectors.
- `/da savebox <box_id> [<start_x> <start_y> <start_z> <end_x> <end_y> <end_z>]` — save a box from explicit corners or the current WorldEdit selection.
- `/da savevector <name> [<x> <y> <z>]` — save an offset from the current pivot to an explicit position or the first WorldEdit selection position.
- `/da visualize [area_id]` — toggle particle outlines for all active boxes or a specific area.
- `/da reload` — reload all YAML files and clear the current runtime boxes.

Coordinates use Paper's native block-position arguments and may be absolute or relative. Entries after `groups` may be coordinate triples, saved vector names, or `inv <vector_id>` values.

Commands are available to command blocks, operators, and users with the `dynamicareas.use` permission.

## Requirements

- Paper or Folia 1.21.11
- Java version required by the server
- WorldEdit 7.4.x (optional, only needed for selection-based commands)

## Building

```powershell
Set-Location "C:\Projects\dynamic-areas"
.\gradlew.bat build
```
