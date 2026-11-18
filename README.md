# ArcDeck Workspace 1.0

An Android home launcher and windowed workspace built on PlayFieldPortal 1.3.3. Games, installed apps, approved storage folders, files, command shortcuts and system tools share one searchable library and organization system.

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
