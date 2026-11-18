# Evaluated GitHub projects — what should actually be integrated

A literal audit of every GitHub repository is not possible or useful. This is a curated review of relevant, established projects and Android platform libraries. **Status** distinguishes shipped implementation from future adoption. Do not mistake a feature inspiration for vendored code.

| Source | Relevant capability | Checked status / reuse | ArcDeck decision |
|---|---|---|---|
| [Play Field Portal](https://github.com/JohnnyCollado/PlayFieldPortal) | Existing XMB shell, sounds, artwork, games/ROMs, game launch dispatcher | Public code, `LICENSE` referenced but missing at inspected path | **Direct source extension via patch**, no PFP sources or artwork redistributed |
| [Kvaesitso](https://github.com/MM2-0/Kvaesitso) | Search-first launcher, efficient organization | GPLv3 file confirmed | Study search UX and actions; **not bundled** |
| [Lawnchair](https://github.com/LawnchairLauncher/lawnchair) | Shortcuts, app drawer, widgets, Android home behavior | Apache-2.0 file confirmed; check file-level notices | Study Android integration; **not bundled** |
| [Neo Launcher](https://github.com/NeoApplications/Neo-Launcher) | Launcher customization, desktop layout options | GPLv3 file confirmed | Study preferences/gestures; **not bundled** |
| [Material Files](https://github.com/zhanghai/MaterialFiles) | File manager flows, SAF, content actions | GPLv3 file confirmed | Study file UX; **not bundled** |
| [Fossify File Manager](https://github.com/FossifyOrg/File-Manager) | File actions, media organization | GPLv3 file confirmed | Study bulk file operations; **not bundled** |
| [Amaze File Manager](https://github.com/TeamAmaze/AmazeFileManager) | File operations, archives, network protocols | License must be confirmed for exact code | Study integration boundaries, **not bundled** |
| [Termux app](https://github.com/termux/termux-app) | Linux command sessions, Python/Git/SSH | GPLv3; separate Android app | **Supported command bridge**, user opt-in; do not transplant full emulator |
| [Termux:Widget](https://github.com/termux/termux-widget) | User-defined script shortcuts | GPLv3 file confirmed | Future optional script discovery; **not bundled** |
| [Termux:API](https://github.com/termux/termux-api) | Battery, clipboard, sensors via Termux ecosystem | Permissions/device-specific; license not confirmed here | Future optional adapters; **not bundled** |
| [KDE Plasma](https://github.com/KDE/plasma-desktop), [KWin](https://github.com/KDE/kwin) | Panel model, window focus, snapping, desktop widgets | Desktop-specific mixed licenses | **Use interaction concepts**, not native Linux window manager source |
| [GNOME Shell](https://github.com/GNOME/gnome-shell) | Search overview, task/workspace switching | GPL family | **Use interaction concepts**, not desktop-native source |
| [Microsoft PowerToys](https://github.com/microsoft/PowerToys) | Power launcher, FancyZones layouts and command actions | MIT file confirmed | Ideas for windows/command palette; **not bundled** |
| [Windows Terminal](https://github.com/microsoft/terminal) | Profiles, command tabs, terminal UI | MIT file confirmed | Ideas for terminal session design; **not bundled** |
| [AndroidX DocumentFile / SAF](https://developer.android.com/training/data-storage/shared/documents-files) | User-granted file trees with persisted access | Android platform API | **Implemented**: multi-root indexing and file actions |
| [SQLite FTS4 / Android Room](https://developer.android.com/reference/kotlin/androidx/room/Fts4) | Local indexed full-text search | Platform SQLite used; no external search SDK | **Implemented** using `SQLiteOpenHelper` + FTS4 |
| [Android multi-window APIs](https://developer.android.com/develop/ui/views/layout/support-multi-window-mode) | System app multitasking | OEM/device dependent | Internal windows only in this patch; system activities launch normally |

## Why this is not a bundle of twenty apps

Transplanting all these codebases would create incompatible navigation systems, dependencies, data schemas, release processes, security assumptions and licenses. The strongest architecture is **one PFP XMB shell** plus small, well-defined adapters. The adapters talk to the existing source of truth through stable APIs. This also avoids duplicating game/ROM databases, achievements and state.

## Next high-value integrations, in priority order

1. **PFP-backed artwork and detail actions in search** — show PFP artwork without re-scraping; use its real game dispatcher and play stats. Current game result opens native PFP detail; custom search rows are text-first.
2. **Metadata/relationship migration** — preserve aliases and memberships when PFP merges duplicate games into a surviving ID. Current orphan references remain marked missing for manual reconciliation.
3. **Document-provider operations** — folder breadcrumbs, rename, copy, move, archive extraction, undo/redo. Must honor SAF capabilities and confirmations rather than demand unrestricted filesystem access.
4. **Session-aware terminal** — embed a terminal renderer with documented Termux PTY or command result IPC after permission review, not background execution from untrusted sources.
5. **System commands** — searchable settings via supported intents, optional notification/usage access, and deep links when valid; no attempt to gain OS capabilities the launcher is not granted.
6. **Index adapters** — contacts/calendar/media where supported and opt-in; remote SMB/WebDAV via dedicated authenticated adapters; incremental WorkManager scans with cancellation/battery rules.
7. **Advanced organization** — drag sorting, saved workspaces, rule-based multi-source collections, custom metadata fields, schema version migrations, JSON export mapping across devices.
8. **Quality** — screenshot baselines, accessibility focus, controller test matrix, memory/thermal profiling, and actual Gradle build validation.

Each optional integration must remain removable and must not silently expand storage, contacts, network or command permissions.
