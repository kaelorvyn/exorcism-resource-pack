# Exorcism Resource Pack

Paper plugin for the Exorcism backend.

## Single-Paper multiworld rooms

The Exorcism backend now runs one Paper server with up to four team rooms:

- Lobby world uses `exo_r00_main` / `exo_r00_aj` instance packs.
- Room worlds use `exo_r01_main` through `exo_r04_main` plus matching AJ packs.
- The compiler in `tools/compile-instance-packs.py` prefixes namespaces, scoreboard
  objectives, fake players, teams, bossbars, storages and entity selectors.
- The plugin dispatches each world's tick in that world instead of relying on global
  `minecraft:tick` tags.
- `/kteam` creates/joins a team; the owner starts a room; room end restores player
  snapshots and saves personal achievements under `player-data/`.

Console-only debug commands:

```text
/kteam debug createworld <slot>
/kteam debug cleanup <slot>
```

Build:

```powershell
./gradlew.bat build
```
