# Unified library — actual code paths and invariant contract

## Identity and ownership

A search record is identified by `(source, stable key)`, not by its title.

- `pfp-game:<game id>` — PFP GameRepository is authoritative for game existence, favorite, play time, ROM URI, emulator and launch history.
- `pfp-shortcut:<game id>` — PFP app-shortcut rows, not game ROMs.
- `android:<package>` — Android installed app package, launched via PackageManager.
- `file:<content-uri>` — user-approved document-provider object; URIs are opaque, never reconstructed as filesystem paths.
- `workspace:<tool>` and `tool:<setting>` — searchable internal controls and official Android settings intents.
- `user:<uuid>` — custom command or website shortcut.

`records` are an **index mirror**. `metadata`, `tags`, `collections` and `memberships` are the only persistent organizational authority. The separate SQLite database is non-destructive to PFP's game database. The PFP object is not duplicated when it appears in several collections.

`game_files` links a PFP game to a file that has the *exact same* SAF ROM URI; the default universal search suppresses the duplicate file row. `type:file` still reveals it for file operations. There is deliberately no aggressive filename/size matching that might link two different ROMs to one game.

## Indexing policy

- PFP `GameRepository.observeAll()` feeds real, non-mutating snapshots (including missing flags); the initial nullable state is ignored until the repository produces its first value.
- Android apps are populated from PackageManager. An anomalous zero-app discovery cannot mark the entire previous app catalog missing.
- File scans use breadth-first enumeration with hard depth/item caps (16 levels, 12,000 objects per scan). Scans require persisted SAF read permission and are explicitly initiated by the user.
- An unreadable, incomplete, or permission-denied scan never mass-deletes or marks unvisited files missing. Complete scans mark missing rather than delete. Tags and collections survive missing sources and remounts.
- Background file reads/indexing run on `Dispatchers.IO`; the interface can still display its last saved records.

## Query grammar

Words are full-text AND prefix matches. `type:game`, `tag:rpg`, `source:android`, `in:1`, `sort:recent`, `sort:name`, `sort:manual`, `is:missing` refine results. Unknown keys become text; unknown values for known keys return empty results (not an accidental broad search). SQL is parameterized; the FTS parser receives only sanitized Unicode letter/digit tokens. Result sets are bounded and can be expanded in increments.

## Collection semantics

- Manual: many-to-many membership with independent per-collection position; moving one shortcut never alters the source object.
- Smart: a stored query re-evaluated against the latest index; membership is computed, not frozen.
- Aliases, tags, pinned flags and links are metadata separate from original filenames, game IDs and package labels.
- PFP games use PFP's own favorite state. Other object favorites are stored in the sidecar.
- `source_recent` reflects PFP play history; `last_opened` records opening from the new overlay.

## Safety and recovery

All third-party Android apps leave the internal workspace and run as ordinary Android activities. `Termux RUN_COMMAND` requires opt-in permission and a confirmation dialog. No root, shell escalation, other-app data access or hidden terminal execution. `SWITCH HOME` / Android default Home settings remain available. Boot to Motorola launcher remains an independent OS setting. No destructive file operations are implemented by this patch.

## Known limits

A true compiled APK, visual/device tests, and end-to-end PFP launch tests are pending. Title/artwork search currently uses text labels; unified results do not yet render all PFP artwork. Folder search can find a folder but the separate Files tool still relies on its own tree navigation; jumping directly to an arbitrary nested document-provider folder is planned. Android will not let every external app live inside a composited XMB window. Backup restores aliases/collections but does not recreate file grants, ROM files or PFP database history.
