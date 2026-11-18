# ArcDeck implementation

PlayFieldPortal's GameRepository remains the game authority. `pfp-game:<id>`, `pfp-shortcut:<id>`, `android:<package>`, exact document URIs, workspace tools and UUID custom shortcuts have stable identities in a separate SQLite catalog. Aliases, tags, favorites for non-game entries, pins and collection membership remain separate from source records.

Schema 2 migrates schema 1 in place, adding artwork and parent-folder references. Source reconciliation uses a transaction-scoped generation counter; complete snapshots mark missing records without deleting organization. Incomplete storage scans retain unvisited records. A failed document-provider query is an error, not an empty directory. Scans are bounded at 12,000 objects and 16 levels and run off the UI thread.

Search binds SQL values and sanitizes text before FTS4 prefix matching. Pagination ends in a stable identity tie-breaker. Smart-collection references are expanded with AND semantics and guarded against cycles. Exact ROM-URI duplicates are hidden only while their matching game is available. Shared revision notifications refresh every open library pane.

Document operations use Android DocumentsContract with user-approved tree grants. Copies snapshot their source first, reject descendant destinations by opaque document identity, stream file content, avoid overwrites and attempt rollback of newly created destinations on failure. Moves remove the source only after successful copying; a failed source removal is reported as a copy. Provider URI changes during rename preserve index organization. Renamed/moved ROMs can need a PlayFieldPortal rescan.

Workspace backups version and validate the full catalog user layer plus notes, window layout and preferences. Source references are restored as missing placeholders, preserving organization until sources return. Old catalog-only backups remain accepted. Imports never execute command shortcuts. Catalog changes merge transactionally; file reads and writes are bounded.

Termux execution follows its RUN_COMMAND API. A review captures the exact command; Android permission and Termux's external-app setting remain required. An explicit one-shot mutable PendingIntent delivers results to a non-exported receiver. Exact outstanding nonces associate results with runs; unsolicited or replayed results are ignored. History is bounded to 20 entries and 100 KB of output per entry. Interactive terminal sessions remain in Termux; completed output is shown in ArcDeck.

The workspace is a Compose surface over the existing XMB, with its own dark color scheme and density-independent saved window coordinates. Toolbars scroll on narrow displays, windows maximize by default on phones, portrait access works, and controller workspace binding is selectable. Android apps run as ordinary Android activities. System default-home controls provide the independent recovery route.

CI tests the actual query parser, Android SQLite helper, schema migration, backup round trips and rollback, source disappearance/return, collection order, ROM deduplication, streamed document copies and failure paths, Termux result association, and Compose phone controls. Release builds disable debugging and the upstream performance overlay and use resource/code shrinking. Final owner-side signing keeps the reusable private identity out of GitHub.
