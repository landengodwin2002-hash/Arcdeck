# Prometheus Companion v1.3 (native APK)

An offline Android ARM64 chatbot using a 4B abliterated GGUF in the app's own llama.cpp native library. Termux is used only to provision the native app's model and knowledge, not for chat.

## Changes in v1.3

- Minimal chat-first Android UI. Header is a custom, animated original virtual-pet character; all other pages are in the menu. Text is larger, and the conversation has the available screen space.
- Pet dialog provides **Feed**, **Praise**, **Discipline**, and **Rest**. Hunger, mood and trust persist in the Android app's SQLite settings. This is a simulated game; no model can literally suffer. Praise may save a positive example of the most recent assistant answer. Discipline may save a corrective instruction. These are prompt/memory updates, not model-weight training.
- Native inference records sampled tokens using `llama_sampler_accept`, reports prompt-processing and generation stages, caps contexts to avoid overload, and retries a fallback template on zero-token output. UTF-8 streaming bytes are turned into the Java answer, avoiding `NewStringUTF` on arbitrary Unicode model output.
- Optional **Diagnostics** screen provides useful model and error details; the Termux sync command can receive the most recent native diagnostic JSON.

## Installation (Termux-first)

1. Install the compiled `Prometheus-Companion-v1.3.apk`. Android will require installation approval when appropriate.
2. Start Termux and install the sync command:

   `curl -fsSL https://raw.githubusercontent.com/landengodwin2002-hash/Arcdeck/prometheus-native-app/prometheus/termux/prometheus-sync.py -o "$PREFIX/bin/prometheus-sync" && chmod +x "$PREFIX/bin/prometheus-sync"`

3. Run `prometheus-sync` and **then open the installed Prometheus Companion app**. The app retrieves the already-installed abliterated 4B GGUF, saved chats, memory, project records, and books from a temporary local server in Termux. The Termux process exits once transfer is acknowledged.
4. For a model-only retry use `prometheus-sync --model-only`; book-only `--books-only`; memory-only `--memory-only`.
5. To inspect native diagnostics run `prometheus-sync --diagnostics` after a previous sync (to refresh diagnostics, start `prometheus-sync --memory-only`, open the app, then run diagnostics).

**Important:** The app has a new package ID, `ai.prometheus.offline.pet`. It installs beside the previous debug-signed v1.2 because the earlier CI debug signing certificate is not suitable for authenticated seamless updates. Nothing in the old v1.2 app or Termux is removed. This is a debug/test APK; production releases need stable private-key signing.

## Known limitations

- Native model generation has not been verified with the actual user's GGUF on the target Android phone. A successful GitHub compilation verifies the APK structure, **not** that it will answer questions.
- CPU-only 4B inference may take considerable time on a phone, especially before the first token. The UI now shows the current stage and errors without silently finishing.
- No remote/cloud connection is needed to chat. A temporary localhost Termux server is used only for import, and is not cryptographically authenticated. Only start it while transferring files.
- Recognition of scanned PDFs still needs OCR; advanced retrieval remains keyword-based; direct app self-modification and advanced tools are not yet implemented.
- Pet feedback is persistent contextual preference data, not neural-weight fine-tuning. Its displayed happiness or distress is fictional.
