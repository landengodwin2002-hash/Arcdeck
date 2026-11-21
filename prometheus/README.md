# Prometheus Native — standalone APK source

This is a **native Android application**, not the Termux-backed website or a PWA. Chat inference uses llama.cpp compiled as an Android JNI library (`arm64-v8a`). It never pairs with a local server, and it works offline after a model is imported. The package ID is `ai.prometheus.offline` so it will not overwrite an older, differently signed Prometheus APK.

## Includes now

- Offline llama.cpp GGUF inference; supports the **Huihui-Qwen3.5-4B-abliterated.Q4_K_M.gguf** model already used in Termux. Users can import the file with the Android picker, or download from a published Hugging Face GGUF repository. The 2.7GB model is **not embedded in the debug APK**; it is installed separately into the app's private directory.
- Streaming chat and Stop control; 192/384/640 response-length settings.
- Large terminal-inspired blue-and-pink Android UI; companion face and Chat, Library, Projects, Settings tabs.
- Persistent local SQLite chats, memory (`/remember`), behavioral corrections (`/correct`), profile, projects, tasks and notes (`/project ...`).
- Source-based book retrieval when relevant; supports PDF text, EPUB, ODT, DOCX, TXT and selected text file formats. Original imported books are copied into the app's private archive and indexed, excluding XML/JSON texture noise. You can select multiple files or an entire book folder (Android document tree picker).
- No implicit self-modifying autonomous code; any future code edits must be explicitly proposed and reviewed.

## Important gaps

- **Existing Termux data migration is available via export/import, not automatic.** From Termux run `python export-termux-for-native.py` (script included in this source package), then in native Prometheus choose **Settings → Import Termux migration** and select `Downloads/Prometheus-Native-Migration.jsonl`. It imports chats, corrections, memories, project items, and indexed book passages. Original book files must still be imported separately into the app. The migration file contains private chat information; keep it local.
- OCR for scanned PDFs, semantic embedding retrieval, automatic internet downloads of whole subject collections, self-modification with review UI, advanced tools for electronics/chemistry, and broad phone/Termux control are not yet implemented.
- Only CPU-based native GGUF is configured. It can be slow. Performance and Android memory-pressure handling require device tests.
- The companion face currently changes only between states by label; complex animations are not yet included.
- An APK produced by CI is a *debug test build*, not a signed production release.

## Building with GitHub Actions

The workflow `.github/workflows/build-apk.yml` checks out source, installs Android SDK/NDK, clones llama.cpp and builds `:app:assembleDebug`. On success, download the APK from the `Prometheus-native-abliterated-APK` artifact. It must be installed and tested on device before claiming compatibility.

## Model migration

For your existing Termux model:

```bash
termux-setup-storage
mkdir -p ~/storage/downloads/PrometheusModels
cp -n ~/local-ai/models/*4B*abliterated*.gguf ~/storage/downloads/PrometheusModels/
```

This requires ~3GB free space. In Android Prometheus, choose **Model → Choose your 4B GGUF**, then choose the copied file. Models remain on the device, and no Termux service is necessary to run the APK.
