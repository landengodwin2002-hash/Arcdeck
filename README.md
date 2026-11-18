# ArcDeck Workspace

A personal Android launcher build based on pinned PlayFieldPortal 1.3.3, with one searchable index for games, apps, approved files, folders, commands and system tools.

## APK builds

Open [Build ArcDeck APK](https://github.com/landengodwin2002-hash/Arcdeck/actions/workflows/build-apk.yml) and select a successful run. Download the Full or Lite artifact, extract the APK, and install it on Android 10 or later. Artifact availability depends on the actual build result.

- **Full** includes upstream Discord Social SDK support.
- **Lite** has the same unified workspace without the native Discord SDK.
- The app appears as **ArcDeck Workspace**, installed separately from the official release.
- These are personal debug builds. Device testing remains pending. Different runs may use different debug signing keys; export your configuration before replacing a test installation.

## Workspace

Open **WS → Library** for unified search, or **Collections** for aliases, tags, favorites, pinned entries, manual collections, saved queries, file roots and organization backup/restore. Files, Terminal, Notes and System windows sit over the existing XMB.

Games retain PlayFieldPortal's own launch settings and database identities. Collections reference objects without moving files. File scanning uses Android's user-approved document-provider folders. Termux command shortcuts open a review screen before execution.

Example searches: `type:game tag:rpg sort:recent`, `type:file pdf`, `source:android`, `in:1 sort:manual`, `is:missing`.

## Reproduce

CI checks out upstream commit `5ee271ea9545685417aecdd13b5ace033cdb7806`, applies this repository's patch, and builds Full and Lite APKs. Each artifact includes source commits and an APK SHA-256 checksum.

```sh
git clone --branch 1.3.3 --depth 1 https://github.com/JohnnyCollado/PlayFieldPortal.git upstream
python3 scripts/prepare_build.py upstream
cd upstream
./gradlew :app:assembleLiteDebug
```

Use JDK 17, Android Platform 37 and Build Tools 37.0.0. Full additionally requires Git LFS SDK files, NDK 27.0.12077973 and CMake 3.22.1. The GitHub workflow installs these dependencies.

```sh
python3 test_installer.py
python3 tests/test_search_sql.py
```

See [implementation](docs/IMPLEMENTATION.md) and [device checks](docs/DEVICE_TEST_PLAN.md) for behavior and validation limits.

## Attribution

[PlayFieldPortal](https://github.com/JohnnyCollado/PlayFieldPortal) is maintained by JohnnyCollado. This repository contains the newly authored ArcDeck patch and downloads upstream sources and SDK files during builds. It does not vendor upstream source, artwork, SDK binaries or games. Upstream and dependency notices remain applicable. A root license file was not found at the pinned upstream revision; no general redistribution license is asserted here.
