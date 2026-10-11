# AzurPvP Utils

Purple/black PvP utility plugin targeting Paper 1.21.11 and Java 21.

## Build
1. Install JDK 21 and Maven.
2. From this directory run `mvn clean package`.
3. Copy `target/AzurPvP-Utils-1.0.0.jar` into your Paper server's `plugins/` folder.
4. Start the server once, edit `plugins/AzurPvPUtils/config.yml`, then restart.

## Included in this starter implementation
- SQLite-backed kills, deaths, streak and best-streak statistics.
- Combat tags with action bar/boss bar and configurable logout punishment.
- Spawn setup and teleport; spawn respawn routing.
- Configurable item kits and cooldown.
- Duel request/accept messaging (arena combat matchmaking is not implemented yet).
- Private messages, reply, in-memory ignore list.
- Rules/Discord info, ping/TPS, fly/heal/feed.
- Basic vanish, freeze, reports stored in SQLite, staff chat.
- Purple/black message styling and configurable combat commands.

## Important scope note
This is a compilable starter/core implementation, not yet a finished implementation of every menu and system in the full 12-section specification. Advanced arena management/rollback, live duels, ranked queues/ELO, GUI kit editor/selector, parties/clans/friends, punishment history and timers, full anti-cheat, leaderboards, quests/seasons, animated scoreboards/tablist, PlaceholderAPI expansion, webhook delivery, MySQL adapter, translations, backup tooling, and developer API still need dedicated modules and testing.

Test on a staging server before production. Anti-cheat heuristics are not a substitute for a mature dedicated anti-cheat. SQLite stats are local to this server instance.
