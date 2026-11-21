# Prometheus Native v1.2 — Termux-managed setup, independent offline chat

**What is different:** All one-time model/library/memory setup is initiated from a Termux command, not Android document pickers. The native app uses the on-device GGUF with llama.cpp; Termux is **not** used for ongoing inference or chat.

## Install

The new Android application ID is `ai.prometheus.offline.termux`, with display name **Prometheus Native**. It can be installed beside the previous test APK, which has a different debug signing key. This is a debug build intended for device verification, not a signed Play Store release.

Install the APK through Android's system installer (Android still requires confirmation for sideloading). You can open the installer from Termux after putting the APK in Downloads: `termux-open ~/storage/downloads/Prometheus-Native-v1.2.apk`.

## Set up entirely from Termux

Download the script `prometheus-sync.py`, then:

```bash
mkdir -p "$PREFIX/bin"
cp ~/storage/downloads/prometheus-sync.py "$PREFIX/bin/prometheus-sync"
chmod +x "$PREFIX/bin/prometheus-sync"
prometheus-sync --dry-run
prometheus-sync
```

Or install the script directly from its tracked GitHub branch:

```bash
curl -fL https://raw.githubusercontent.com/landengodwin2002-hash/Arcdeck/prometheus-native-app/prometheus/termux/prometheus-sync.py -o "$PREFIX/bin/prometheus-sync"
chmod +x "$PREFIX/bin/prometheus-sync"
prometheus-sync
```

**Leave that command running in Termux and simply open Prometheus Native on your phone.** The app checks `127.0.0.1:18765` on resume, verifies SHA-256 for every transferred file, archives imported books, and imports GGUF plus memory. The Termux sender stops after confirmation. It does not require you to choose a file, pair a server, or grant access to Termux's private directory from Android's file picker.

This local handoff needs enough free storage to copy the ~2.7 GB model into the native app's private directory. Don't delete original files until a successful device test. The Termux model is never modified or moved.

### Commands

- `prometheus-sync` — model, books, memories, corrections, projects, chat history
- `prometheus-sync --model-only` — existing abliterated/obliterated GGUF
- `prometheus-sync --books-only` — discovered PDFs/EPUB/DOCX/ODT/TXT/MD/FB2/RTF
- `prometheus-sync --memory-only` — export current Termux databases and imported text passages
- `prometheus-sync --include ~/storage/downloads/OtherBooks` — add a specific book folder
- `prometheus-sync --dry-run` — preview fingerprints and sizes, no sender
- `prometheus-sync --status` — show selected Termux model and number of eligible book files

The importer searches the existing archived library (`~/local-ai/knowledge/originals` and `downloaded`), dedicated book folders, Documents, and root-level Downloads with extension and directory filtering. It does not intentionally crawl game textures or programming assets. Scanned PDFs still need OCR.

## The setup workflow is **not** a permanent server

The only HTTP traffic is temporary *on-device* file transfer over loopback, while you explicitly run `prometheus-sync` in Termux. No public network or cloud service is involved. Chat is subsequently fully native/offline and the Termux sender exits.

**Privacy limitation:** Android's localhost networking is shared between apps. While the transfer server is running, another app on the device with networking permission could attempt to read a model or book from that local port. Use the command only while actively importing, avoid sensitive files if you distrust other installed apps, and stop it when done. This preview does not provide cryptographic app-to-app identity authentication. No API keys or network ports are opened beyond the temporary loopback sender.

## Known limitations

- The native model engine is CPU-backed, so the 4B model can be slow or exceed RAM on a given device.
- Offline technical textbook packs are not shipped within the APK; the sync imports those already stored in your Termux collection. If you want more, first add them through Termux library tools then sync.
- Not all previous requests (OCR, advanced semantic search, autonomous self-edit review, extended tool execution) are complete.
- Native Android user acceptance testing and performance measurements on the Motorola Edge 2024 are still required.
- Future native APK updates need stable private signing. This preview uses a new app ID to avoid breaking existing test installation data.
