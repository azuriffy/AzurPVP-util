# AzurPvP Utils — Revamp baseline

Purple/black Paper plugin targeting Paper 1.21.11 and Java 21.

## Build
1. Install JDK 21 and Maven.
2. Run `mvn clean package` in this directory.
3. Copy `target/AzurPvP-Utils-1.0.0.jar` to your Paper server's `plugins/` folder.
4. Start the server and configure `plugins/AzurPvPUtils/config.yml`.

## Included in this revamp baseline
- Persistent role system (`roles.yml`) with `dev`, `staff`, and normal `player` roles.
- `/role list`, `/role create <name>`, `/role delete <name>`, `/role set <online-player> <role>`, and `/role info <name>`.
- The built-in `dev` role is locked and cannot be deleted. Dev can manage plugin roles; staff commands are gated by the staff role rather than requiring operator.
- First-run bootstrap: if no dev role is assigned, the first online operator is assigned `dev`. This gives server owners a way to get started. Verify the role in-game before removing operator.
- SQLite stats, kills/deaths/streaks, combat tag with actionbar/bossbar and logout punishment, spawn setup/teleport, configured kits, duel request/accept messaging, PM/reply/ignore, reports, staff chat, freeze, vanish, ping/TPS, basic staff utilities, and top stats.
- Maintenance toggle and configuration switches for the broader non-economy feature roadmap.
- Economy features are intentionally excluded.

## Important implementation status
This is a revamp baseline, **not a complete implementation of every item in the original 112-feature list**. Advanced live arena matchmaking/rollback, ranked queues/ELO, GUI kit builder/editor, parties/clans/friends, full punishment durations/history, robust anti-cheat, holograms/animated scoreboard and tablist, PlaceholderAPI expansion, webhook delivery, MySQL adapter, translations, backup tools, and the full developer API still require dedicated implementation and testing. Several roadmap commands are registered as placeholders and explicitly report that their module is not implemented yet. Do not treat a registered command as a completed feature.

Test on a staging server before production. The basic anti-cheat placeholder is not a substitute for a dedicated anti-cheat plugin.
