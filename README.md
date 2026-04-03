# dynamic-areas

MVP плагин для Paper/Folia 1.21.11 с динамическими зонами.

## Что реализовано

- Загрузка `boxes` и `areas` из `plugins/dynamic-areas/boxes` и `plugins/dynamic-areas/areas`.
- `/da addboxtoarea` с TTL (по умолчанию 2) и refresh по ключу `(area_id, box_id, world_uuid, base_offset_xyz)`.
- `/da setpivot` и `/da savebox` (с координатами или выделением WorldEdit).
- Три-стейт флаги `allow | deny | ignore` для `block_breaking` и `interactions`.
- `listeners` в формате list-of-maps.
- Шаблоны команд listeners в формате `{key}` через простой `replace`.
- Индекс зон по чанкам и diff-модель enter/leave.
- `/da reload` очищает runtime и перезагружает конфиги.

## Формат box

Файл: `plugins/dynamic-areas/boxes/<box_id>.yml`

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

## Формат area

Файл: `plugins/dynamic-areas/areas/<area_id>.yml`

```yaml
block_breaking: deny
interactions: allow
listeners:
  - on_interact: "say clicked by {player_name}"
  - on_player_enter: "say enter {player_name}"
  - on_player_leave: "say leave {player_name}"
```

## Команды

- `/da addboxtoarea <box_id> <area_id> [offset_x offset_y offset_z] [ttl]`
- `/da setpivot <x> <y> <z>`
- `/da savebox <box_id> [<start_x start_y start_z end_x end_y end_z>]`
- `/da reload`

Требования доступа: OP или командный блок.

## Сборка

```powershell
Set-Location "C:\Projects\dynamic-areas"
.\gradlew.bat build
```

