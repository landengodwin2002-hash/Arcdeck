# ArcDeck Workspace 1.3

An Android home launcher and windowed workspace built on PlayFieldPortal 1.3.3. Games, installed apps, approved storage folders, files, command shortcuts and system tools share one searchable library and organization system.

## Landscape multitasking and scripts

1.3 adds a native command/search bar above PFP, with direct Library, ChatGPT, Discord, Media, game connections and Android controls. Library now owns search, installed apps and games together; the Apps filter uses an icon grid. Old Search/Apps/Collections window references converge on this one destination. Landscape mode is enabled by default and can be disabled under System. Android's keyboard, its voice typing and an optional speech-recognition button provide text input. Physical typing in the command bar is routed to the text field instead of PFP controller mappings.

ChatGPT, Discord and Media are actual HTTPS WebView windows inside the launcher, using the services' web clients. They can be tiled side by side with the ▥ dock control or arranged using the window ⋮ menu and position/size sliders. The window frame, resize grip and taskbar targets are larger. Window animations default to 120 ms with a 0–400 ms slider. Short landscape windows collapse library filters/actions into Views to leave space for results. Existing PFP icon/layout/theme editors remain available from Customize and PFP features.

Web windows are not embedded copies of native Android apps. Sign-in support is controlled by each site; Open app/browser is the fallback for unsupported embedded authentication. Native-app calls, microphone/camera features and native split-screen remain Android activities. Web panels grant no microphone/camera permission automatically and expose no JavaScript-to-Android script bridge. The page origin stays visible. File attachments use Android's file picker. The Clipboard pad provides deliberate Copy/Paste here and voice text, without a background clipboard watcher.

Minimizing, closing or covering a web window with a maximized window unloads its renderer; leaving ArcDeck releases web renderers too. Reopening restores page history and the web profile's existing sign-in cookies, not arbitrary unsaved page drafts or running JavaScript. Save unfinished text in Notes/Clipboard before unloading. Media plays alongside other visible web windows; minimizing/unloading stops that window. YouTube native background playback depends on YouTube's account/features; ArcDeck does not bypass those requirements. Android and native apps control their own background processes—ArcDeck cannot force-stop arbitrary native apps. Folder refresh cancels on workspace hide/launcher stop, and scans retain old records when cancelled.

The PFP command bar reviews a pasted command before starting it in Termux, then shows captured output in the launcher terminal. Background runs use a managed Bash process group with Pause, Resume and Stop controls. They pause when the terminal window is minimized or ArcDeck is left, and closing the terminal stops managed runs. Resume is explicit. Process identities include PID start times so stale records cannot signal an unrelated reused PID. Clear finished history preserves active controls. Interactive sessions remain in Termux. Scripts that deliberately create separate sessions or independent services remain outside the managed process group.

Automation is optional and off by default. Start its local script connection in Automation, then copy the curl example into Termux. It binds only to 127.0.0.1:47831, requires a private per-install bearer token, rejects browser-origin requests, and stops on screen off or its notification's Stop action. The token and app permissions are device-local and excluded from backups. Window/clipboard commands need no accessibility service. Native tap/swipe/Back and focused-field text replacement require the optional Android accessibility service and an explicitly allowed app in front; password fields are rejected. No endpoint reads/export app messages or clicks Send automatically. See [script connection examples](docs/SCRIPT_CONNECTION.md).

## Papaya and GameHub Lite

Open **Spaces → Game connections**. ArcDeck detects Papaya and installed GameHub-family launchers, including verified package variants. Create/open each app's space, then connect the existing folders on your phone as **Games / ROMs**, **PC game IDs**, **Saves**, **Artwork** or **App files**. **Use connected folder** reuses an existing Android folder grant and lets both apps' spaces reference the same directory. Connection indexes names and references; it does not copy, move or replace games, saves or components.

When the installed GameHub build exposes its MT document provider, **Connect GameHub provider** opens Android's folder picker at that app's provider. Allow the desired folder there. **Find saves / components** resolves standard save-container, component, Steam and log directories using provider queries; discovered folders can be browsed and pinned to the app space. Availability depends on the installed GameHub build. Android's ordinary storage picker cannot grant another app's restricted `Android/data` directory on Android 11 and later; a source app's own document provider or an export/shared folder is needed.

**Review PC IDs** recognizes small `.steam`, `.steamappid`, `.localgameid` and ID-only `.txt` files, shows their titles/IDs, and lets you choose a detected GameHub build before importing. Steam and local ID namespaces stay distinct. Imports create ordinary PFP Windows game records and attach them to the source app's space. Repeating the same import matches its launch handle and preserves tags/aliases. Real ISO images and ordinary notes are never interpreted as launchers. Scans are bounded to 6,000 entries, 12 directory levels and 500 recognized IDs per review; select a smaller folder for larger libraries. No automatic import runs in the background.

For ROM platform assignment, emulator configuration and PFP's richer `.desktop`/`.pfpgame` import formats, **Import ROMs / PFP exports** opens the original Library Manager. Choose the same existing root used in Papaya there. Papaya's private database, scraped metadata, settings and permissions are not publicly exposed by its asset/config repository and are not claimed as synchronized. Folder connections, roles and space references are backed up; a new installation/device must grant its own folder access.

## App spaces and Android integration

Create a space for any installed app (including Discord, Brave, ChatGPT, file managers or video apps), or create an independent/game space. Each space connects an app with notes, links, folders and items from the shared catalog. An item can belong to multiple spaces without duplicating its tags or source. Spaces are ordinary editable collections in Library too. A saved layout can be linked in Edit space and applied when its app opens, giving that app its own workspace arrangement and appearance.

In **System → Enable floating workspace over other apps**, allow Android's display-over-other-apps permission. A movable **WS** bubble opens the selected space over another app. It provides notes, search, previews, file sharing, context copying and a route back to the full workspace. The panel runs only when enabled, has an ongoing notification with Stop, and does not inspect other apps' private content or messages.

Use another app's Android **Share → Save to ArcDeck** action to choose a destination space. Temporary shared files are copied into ArcDeck's private storage so they remain accessible after the source app closes. Shares are reviewed before saving; outbound shares open Android's chooser or the connected app's composer. They never send a message automatically. File imports are bounded to 32 files, 512 MB per file and 1 GB per batch; connect a storage folder for larger files.

Third-party apps run as Android activities. ArcDeck does not pretend to embed their full UI inside its own windows. **Open adjacent** requests Android's adjacent launch when the device is already in split-screen; Android and the target app control whether it is honored. The floating space remains available over ordinary full-screen apps that permit overlays.

**PFP features** exposes the real original categories, library manager, notifications, player status, icons and layout/color editors. The permanent **PFP** dock action returns to the exact previous original screen. If an original dialog is active, finish/close it before switching sections; the workspace preserves it rather than discarding an edit.

**Customize** controls palette, opacity, dimming, corners, text scale, dock order/visibility, compact controls and default window sizing. Named layout profiles save geometry and appearance. System can expose Android status/navigation bars, native Internet/volume panels, app information and launcher app shortcuts (shortcut access requires the default Home role).

Files supports multi-selection, batch copy/cut/paste/share/delete, shared tags, breadcrumbs and name/size/type sorting. Images, text and PDFs preview inside the workspace. Approved folders refresh on return (throttled to five minutes); visible folders also refresh from provider change notifications. Backups include app spaces, notes, profiles and appearance, while Android permission grants, widget/app internals and shared-file bytes remain device-local.

## Editions

- **Full:** PlayFieldPortal game launching, XMB, artwork, themes and Discord Social SDK, plus the complete ArcDeck workspace.
- **Lite:** the same workspace and game launcher without the native Discord Social SDK.
- Android 10 or later. Package IDs `dev.arcdeck.workspace` and `dev.arcdeck.workspace.lite` keep ArcDeck separate from the official launcher and earlier debug builds.

The owner signs the optimized release APKs with a private, reusable signing key. CI artifacts ending in **unsigned.apk** require signing before installation. Never install an unsigned artifact as if it were the finished download.

## Using the workspace

Tap **WS** or press **F10**. **System** lets you choose a different controller shortcut. On phones, windows open maximized; on larger screens they can be dragged, resized, minimized and restored from the taskbar. Portrait use is supported, and the XMB landscape reminder is optional.

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

Use JDK 17, Android Platform `37.0`, Build Tools `37.0.0`. Full also needs upstream Git LFS SDK files, NDK `27.0.12077973` and CMake `3.22.1`. The workflow provisions these dependencies.

To sign locally, restore the private signing directory and run:

```sh
python3 scripts/sign_release.py --input INPUT-unsigned.apk --output ArcDeck.apk \
  --apksigner /path/to/apksigner.jar --keydir /private/path/to/signing
```

`--initialize` creates the first private signing identity in an empty directory. Keep the private key and its password out of the public repository; the saved signing backup is required for compatible future updates.

See [implementation](docs/IMPLEMENTATION.md) and [phone test guide](docs/DEVICE_TEST_PLAN.md). Automated checks supplement the owner's physical-device, emulator, controller and external-app checks.

## Attribution

[PlayFieldPortal](https://github.com/JohnnyCollado/PlayFieldPortal), maintained by JohnnyCollado, supplies the underlying launcher. This repository contains ArcDeck's authored extension and build automation, and fetches the pinned upstream source and original dependencies during builds. Games, ROMs and BIOS files are not included. Upstream and dependency notices remain applicable; a root license file was not found at the pinned revision and no general redistribution license is asserted here.
