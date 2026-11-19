# ArcDeck Workspace 1.5

An Android home launcher and windowed workspace built on PlayFieldPortal 1.3.3. Games, installed apps, approved storage folders, files, command shortcuts and system tools share one searchable library and organization system.

## 1.5 desktop and browsing update

ArcDeck now opens to an editable main menu with direct Games, Apps, Library, Files, Terminal, Spaces and Android tools. One control bar combines menu access, running windows, command entry and arrangement; place it at the top, bottom, left or right. The command bar no longer reserves 112dp above the original interface.

Console pages, game details and PFP editors open inside a managed workspace window. Freeform windows remain draggable and resizable. Fullscreen hides the surrounding controls and exposes a small return strip; Windowed restores the prior bounds. Native apps use the physical-phone keyboard by default, preserve display aspect and have independent text scaling.

Games and apps share the same index while retaining direct views. Choose carousel, grid or list, six artwork treatments, artwork size, title placement and carousel alignment. `platform:psp` and other platform filters select the actual mirrored platform rather than matching a game title. Per-space window arrangements, pane queries, file-pane locations and browser appearance are included in version-3 backups; old backups remain readable.

The public Papaya catalog is pinned to commit `fbe96e0ea3c5100f65f31e397e8e23c1412a6b8e`: 52 platforms, 433 emulator recipes and 14 streaming apps. The compatibility pane exposes these definitions; six compatible standalone recipes augment the existing emulator knowledge base. Existing PFP recipes, RetroArch core detection, PC launcher adapters and user choices retain precedence. Files, Library, Apps and Games can open additional independent windows from Arrange. The original platform file-extension lists are not broadened by generic Papaya ZIP/TXT/PNG rules. MIT attribution ships with the data. Papaya's Android application and private database are not bundled.

PFP Social sign-in, Shiba Coins routes and the native Discord SDK are removed from both editions. Installed Discord remains a normal native app. Four native display slots remain the simultaneous limit; an inactive display can be released when another space needs a slot. Arrangement restoration does not guarantee another app preserves every internal screen or unsaved field.

This update is validated by automated regressions and release checks; actual phone app compatibility, keyboard behavior and CPU/battery use still require device testing.

## Landscape multitasking and scripts

1.3 adds a native command/search bar above PFP, with direct Library, ChatGPT, Discord, Media, game connections and Android controls. Library now owns search, installed apps and games together; the Apps filter uses an icon grid. Old Search/Collections references converge on Library; Apps and Games have direct filtered views of the same index. Landscape mode is enabled by default and can be disabled under System. Android's keyboard, its voice typing and an optional speech-recognition button provide text input. Physical typing in the command bar is routed to the text field instead of PFP controller mappings.

1.4 replaces the ChatGPT, Discord and Media website panels with installed Android app windows. Library's Apps grid and **Open app** in a space also request native windows. **Apps** opens a picker for any launchable installed app and any Android profile made available through LauncherApps. These are the original apps using their existing account/data; ArcDeck does not duplicate installations or transfer accounts. Choose another accessible profile from **App / profile**. Login accounts inside an app remain managed by that app.

Native windows require [Shizuku](https://shizuku.rikka.app/download/). Start Shizuku using Android wireless debugging (Android 11+) or its supported computer/root method, then choose **Connect native windows** and authorize ArcDeck on the phone. This cannot be enabled remotely or restored from a backup. Shizuku normally needs restarting after a reboot. **Phone library** shows setup and permission state on the first 1.4 launch. **Open installed app normally** remains available when Shizuku is disconnected.

Up to four native displays use direct GPU surfaces, with touch, Back and Android keyboard/IME routing. Windows can overlap, tile, resize and select their Android profile. No screen-video/JPEG encoding loop is used. Minimize, hiding behind a maximized window and leaving ArcDeck detach their surfaces; close finishes tasks only on that ArcDeck display. Android and each app control background processes, audio, microphone/camera permissions and task retention. Reopening a minimized window retains its display task when Android keeps it alive; closing does not promise to retain unsaved drafts. YouTube background playback still depends on YouTube's own account/features. Apps, device policies and protected video can refuse secondary displays; ArcDeck reports failed display placement rather than opening a substitute website. Native game launches with ordinary scalar intent extras use the same verified window route when connected; specialized parcelable/array intents and harvested launcher shortcuts keep Android's original launch path.

The command bar, Library, layout sliders, 44 dp controls, 120 ms animation default, original PFP editors and Android voice input from 1.3 remain available. The Clipboard pad offers explicit copy/paste without monitoring other apps' text. Folder refresh cancels when ArcDeck stops; incomplete scans retain existing organization.

The PFP command bar reviews a pasted command before starting it in Termux, then shows captured output in the launcher terminal. Background runs use a managed Bash process group with Pause, Resume and Stop controls. They pause when the terminal window is minimized or ArcDeck is left, and closing the terminal stops managed runs. An explicitly active script connection permits cross-app automation to continue while its target app is in front; stopping that connection pauses managed runs. Resume is explicit. Process identities include PID start times so stale records cannot signal an unrelated reused PID. Clear finished history preserves active controls. Interactive sessions remain in Termux. Scripts that deliberately create separate sessions or independent services remain outside the managed process group.

Automation is optional and off by default. Start its local script connection in Automation, then copy the curl example into Termux. It binds only to 127.0.0.1:47831, requires a private per-install bearer token, rejects browser-origin requests, and stops on screen off or its notification's Stop action. The token and app permissions are device-local and excluded from backups. Window/clipboard commands need no accessibility service. Native tap/swipe/Back and focused-field text replacement require the optional Android accessibility service and an explicitly allowed app in front; password fields are rejected. The connection does not export app messages; text replacement adds no Send action. Gestures perform only the actions requested by your script. See [script connection examples](docs/SCRIPT_CONNECTION.md).

## Automatic phone organization

Open **Phone library**, allow **phone storage** once or grant specific folders, and connect Shizuku for native windows and connected game apps' external `Android/data/<package>/files` folders. ArcDeck scans shared internal storage, available removable volumes and existing read-granted document trees. Detected Papaya/GameHub external folders are attached to their app spaces automatically when readable. Emulator detection/configuration uses PFP's existing adapters.

Recognized ROM formats and console folder names populate a read-only virtual **Phone games** root and normal PFP memory cards/game records. Files remain at their original paths. Multi-file disc companions remain accessible from original locations. Uncertain ISO/ZIP/BIN candidates appear as unclassified. Existing tags, aliases, favorites and saves are preserved. Valid PFP/launcher exports use PFP's importers; small `.steamappid`/`.localgameid` files use a verified GameHub adapter automatically when their source launcher is clear (or exactly one GameHub build is installed). ID-only text files are considered automatically only inside detected GameHub external folders. Unknown databases, encrypted archives, app-internal `/data/user` files and opaque private save schemas are not guessed or rewritten.

Updates run on launcher return with a five-minute throttle and via Android WorkManager, nominally every 15 minutes subject to battery/storage and Android scheduling. Discovery checkpoints directory work across 20-second/20,000-entry passes; it pauses foreground discovery when ArcDeck stops. A directory/provider view is bounded to 20,000 children, with depth 32 and incomplete/unreadable status retained. Very large views need narrower sources. Previous records from sources that could not be scanned are retained. This scans the actual phone only after phone-side access is available; building an APK does not scan the owner's device.

## Papaya and GameHub Lite

Open **Spaces → Game connections**. ArcDeck detects Papaya and installed GameHub-family launchers, including verified package variants. Create/open each app's space, then connect the existing folders on your phone as **Games / ROMs**, **PC game IDs**, **Saves**, **Artwork** or **App files**. **Use connected folder** reuses an existing Android folder grant and lets both apps' spaces reference the same directory. Connection indexes names and references; it does not copy, move or replace games, saves or components.

When the installed GameHub build exposes its MT document provider, **Connect GameHub provider** opens Android's folder picker at that app's provider. Allow the desired folder there. **Find saves / components** resolves standard save-container, component, Steam and log directories using provider queries; discovered folders can be browsed and pinned to the app space. Availability depends on the installed GameHub build. Android's ordinary storage picker cannot grant another app's restricted `Android/data` directory on Android 11 and later; a source app's own document provider, an export/shared folder, or the new Shizuku connection is needed.

**Review PC IDs** recognizes small `.steam`, `.steamappid`, `.localgameid` and ID-only `.txt` files, shows their titles/IDs, and lets you choose a detected GameHub build before importing. Steam and local ID namespaces stay distinct. Imports create ordinary PFP Windows game records and attach them to the source app's space. Repeating the same import matches its launch handle and preserves tags/aliases. Real ISO images and ordinary notes are never interpreted as launchers. Scans are bounded to 6,000 entries, 12 directory levels and 500 recognized IDs per review; select a smaller folder for larger libraries. The manual review remains available for uncertain sources and choosing between multiple installed GameHub builds.

For ROM platform assignment, emulator configuration and PFP's richer `.desktop`/`.pfpgame` import formats, **Import ROMs / PFP exports** opens the original Library Manager. Choose the same existing root used in Papaya there. Papaya's private database, scraped metadata, settings and permissions are not publicly exposed by its asset/config repository and are not claimed as synchronized. Folder connections, roles and space references are backed up; a new installation/device must grant its own folder access.

## App spaces and Android integration

Create a space for any installed app (including Discord, Brave, ChatGPT, file managers or video apps), or create an independent/game space. Each space connects an app with notes, links, folders and items from the shared catalog. An item can belong to multiple spaces without duplicating its tags or source. Spaces are ordinary editable collections in Library too. A saved layout can be linked in Edit space and applied when its app opens, giving that app its own workspace arrangement and appearance.

In **System → Enable floating workspace over other apps**, allow Android's display-over-other-apps permission. A movable **WS** bubble opens the selected space over another app. It provides notes, search, previews, file sharing, context copying and a route back to the full workspace. The panel runs only when enabled, has an ongoing notification with Stop, and does not inspect other apps' private content or messages.

Use another app's Android **Share → Save to ArcDeck** action to choose a destination space. Temporary shared files are copied into ArcDeck's private storage so they remain accessible after the source app closes. Shares are reviewed before saving; outbound shares open Android's chooser or the connected app's composer. They never send a message automatically. File imports are bounded to 32 files, 512 MB per file and 1 GB per batch; connect a storage folder for larger files.

The main workspace hosts native Android activities on Shizuku-owned virtual displays. The optional floating WS bubble is a separate notes/files/space overlay. **Open adjacent** still requests Android split-screen; Android and the target app decide whether to honor it.

**Console & editors** hosts the original categories, library manager, notifications, icons and layout/color editors inside an ArcDeck window. If an original dialog is active, finish/close it before switching sections; the workspace preserves it rather than discarding an edit.

**Customize** controls palette, opacity, dimming, corners, text scale, dock order/visibility, compact controls and default window sizing. Named layout profiles save geometry and appearance. System can expose Android status/navigation bars, native Internet/volume panels, app information and launcher app shortcuts (shortcut access requires the default Home role).

Files supports multi-selection, batch copy/cut/paste/share/delete, shared tags, breadcrumbs and name/size/type sorting. Images, text and PDFs preview inside the workspace. Approved folders refresh on return (throttled to five minutes); visible folders also refresh from provider change notifications. Backups include app spaces, notes, profiles and appearance, while Android permission grants, widget/app internals and shared-file bytes remain device-local.

## Editions

- **Full:** the complete ArcDeck workspace and PFP game, media, artwork and theme tools. Native Discord SDK removed; preserves the existing Full package ID.
- **Lite:** the same workspace and game launcher with the existing Lite package ID. Neither edition includes the native Discord Social SDK.
- Android 10 or later. Package IDs `dev.arcdeck.workspace` and `dev.arcdeck.workspace.lite` keep ArcDeck separate from the official launcher and earlier debug builds.

The owner signs the optimized release APKs with a private, reusable signing key. CI artifacts ending in **unsigned.apk** require signing before installation. Never install an unsigned artifact as if it were the finished download.

## Using the workspace

ArcDeck opens to **Menu**. **System** lets you choose a keyboard/controller shortcut. Windows can be dragged, resized, minimized, maximized or opened full screen; the control bar restores running windows. Default phone sizing is configurable. Portrait use is supported, and the XMB landscape reminder is optional.

- **Library / Search / Apps:** one index with game artwork and app icons; prefix search; type/source/favorite/pinned/recent/missing filters; aliases and tags; manual collection order; editable smart collections; editable website and command shortcuts.
- **Collections & storage:** connect storage folders, index them, browse them, rescan or remove them from the index. Removing a root does not delete its files. Game records remain owned by PlayFieldPortal.
- **Files:** direct navigation from folder search results and containing-folder actions; browse, search, create folders, rename, copy, move, share and delete through Android's document providers. Copies never overwrite existing files. Destructive actions require explicit confirmation.
- **Terminal:** reviewed Termux commands, optional interactive sessions, captured output, exit codes and bounded command history. Install Termux separately, grant its command permission, and enable `allow-external-apps=true` in its properties.
- **Notes:** automatically saved, selectable text with a manual Save option.
- **Backup / Restore:** organization, source references, tags, manual ordering, custom shortcuts, smart collections, notes, layout and workspace preferences. A new device still needs its own file permissions and game library.
- **System:** ordinary Android settings, default home selection, wireless/storage settings, shortcut choice and window reset. Choosing ArcDeck as the default Home app remains an Android system decision.

Examples: `type:game tag:rpg sort:recent`, `source:files pdf`, `is:favorite`, `is:pinned`, `in:"My Games" sort:manual`, `is:missing`.

## Build and verify

CI first runs the installer checks and real Android/Robolectric database, document-provider, callback and Compose phone-layout tests. Both optimized release builds are gated on that test job. Source commits and unsigned APK SHA-256 are included in their artifacts.

```sh
python3 test_installer.py
git clone --branch 1.3.3 --depth 1 https://github.com/JohnnyCollado/PlayFieldPortal.git upstream
python3 scripts/prepare_build.py upstream
cd upstream
./gradlew :app:testLiteDebugUnitTest --tests 'com.playfieldportal.launcher.workspace.*'
./gradlew :app:assembleLiteRelease
```

Use JDK 17, Android Platform `37.0`, Build Tools `37.0.0`. The Discord SDK/LFS/NDK requirement is removed. Both editions use the same test-gated release pipeline.

To sign locally, restore the private signing directory and run:

```sh
python3 scripts/sign_release.py --input INPUT-unsigned.apk --output ArcDeck.apk \
  --apksigner /path/to/apksigner.jar --keydir /private/path/to/signing
```

`--initialize` creates the first private signing identity in an empty directory. Keep the private key and its password out of the public repository; the saved signing backup is required for compatible future updates.

See [implementation](docs/IMPLEMENTATION.md) and [phone test guide](docs/DEVICE_TEST_PLAN.md). Automated checks supplement the owner's physical-device, emulator, controller and external-app checks.

## Attribution

[PlayFieldPortal](https://github.com/JohnnyCollado/PlayFieldPortal), maintained by JohnnyCollado, supplies the underlying launcher. This repository contains ArcDeck's authored extension and build automation, and fetches the pinned upstream source and original dependencies during builds. Games, ROMs and BIOS files are not included. Upstream and dependency notices remain applicable; a root license file was not found at the pinned revision and no general redistribution license is asserted here.
