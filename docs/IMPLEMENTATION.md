# ArcDeck implementation

1.1 adds app spaces as named references to ordinary catalog collections, with per-space notes and an optional Android package. The collection name, rather than a database row ID, survives backup collection remapping. Shared sources keep one identity across spaces. Draft notes remain available to Copy/Share context before their disk-save debounce completes.

Incoming Android SEND/SEND_MULTIPLE content is reviewed in a dedicated activity. Only content URIs are accepted; bounded streams stage private copies and commit catalog memberships after every input succeeds. Failure removes staging files. FileProvider exposes only the imported-files directory in addition to upstream's existing paths. No imported commands are executed.

The user-enabled overlay is a private foreground specialUse service with an ongoing Stop notification. A bounded native bubble and a Compose panel use TYPE_APPLICATION_OVERLAY with an explicit lifecycle/saved-state/view-model owner. The panel does not register activity-result launchers or embed external app activities. App content is exchanged through explicit Android sharing and clipboard actions, without accessibility or screen capture.

The original AppXmbHost remains mounted once. Workspace shortcuts call existing PFP category/editor actions and preserve blocking dialogs. The permanent PFP action closes only the workspace. Native status/navigation bars are a preference applied by MainActivity; app shortcuts use LauncherApps and its real Home-role permission check.

Appearance and layout profiles are validated before restoring any catalog data. Backup v2 preserves the earlier v1 and catalog-only import paths. Overlay enablement/Android grants and private shared-file bytes are intentionally not restored from organization backups. File moves carry catalog aliases/tags/collection memberships to their returned destination URIs after successful source deletion.

PlayFieldPortal's GameRepository remains the game authority. `pfp-game:<id>`, `pfp-shortcut:<id>`, `android:<package>`, exact document URIs, workspace tools and UUID custom shortcuts have stable identities in a separate SQLite catalog. Aliases, tags, favorites for non-game entries, pins and collection membership remain separate from source records.

Schema 2 migrates schema 1 in place, adding artwork and parent-folder references. Source reconciliation uses a transaction-scoped generation counter; complete snapshots mark missing records without deleting organization. Incomplete storage scans retain unvisited records. A failed document-provider query is an error, not an empty directory. Scans are bounded at 12,000 objects and 16 levels and run off the UI thread.

Search binds SQL values and sanitizes text before FTS4 prefix matching. Pagination ends in a stable identity tie-breaker. Smart-collection references are expanded with AND semantics and guarded against cycles. Exact ROM-URI duplicates are hidden only while their matching game is available. Shared revision notifications refresh every open library pane.

Document operations use Android DocumentsContract with user-approved tree grants. Copies snapshot their source first, reject descendant destinations by opaque document identity, stream file content, avoid overwrites and attempt rollback of newly created destinations on failure. Moves remove the source only after successful copying; a failed source removal is reported as a copy. Provider URI changes during rename preserve index organization. Renamed/moved ROMs can need a PlayFieldPortal rescan.

Workspace backups version and validate the full catalog user layer plus notes, window layout and preferences. Source references are restored as missing placeholders, preserving organization until sources return. Old catalog-only backups remain accepted. Imports never execute command shortcuts. Catalog changes merge transactionally; file reads and writes are bounded.

Termux execution follows its RUN_COMMAND API. A review captures the exact command; Android permission and Termux's external-app setting remain required. An explicit one-shot mutable PendingIntent delivers results to a non-exported receiver. Exact outstanding nonces associate results with runs; unsolicited or replayed results are ignored. History is bounded to 20 entries and 100 KB of output per entry. Interactive terminal sessions remain in Termux; completed output is shown in ArcDeck.

The workspace is a Compose surface over the existing XMB, with its own dark color scheme and density-independent saved window coordinates. Toolbars scroll on narrow displays, windows maximize by default on phones, portrait access works, and controller workspace binding is selectable. Android apps run as ordinary Android activities. System default-home controls provide the independent recovery route.

CI tests the actual query parser, Android SQLite helper, schema migration, backup round trips and rollback, source disappearance/return, collection order, ROM deduplication, streamed document copies and failure paths, Termux result association, and Compose phone controls. Release builds disable debugging and the upstream performance overlay and use resource/code shrinking. Final owner-side signing keeps the reusable private identity out of GitHub.
