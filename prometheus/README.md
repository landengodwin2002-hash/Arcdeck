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

## v1.4 Safe Chat changes

Android 15/16 system bars and keyboard insets are handled explicitly for reachable chat controls. Chat messages are queued during model load rather than silently discarded, with visible inference progress. Termux sync now runs on its own worker and loads the model before processing all books. Book/memory/model setup still runs through Termux. This preview uses a separate package ID because earlier CI debug APKs had independent signing keys; old installations and their data remain intact. Use `prometheus-sync` again with the new app open for migration.

## v1.5 persistent background work

- Foreground service for active offline inference, model loading and local imports while switching applications. Android may still terminate the process for severe RAM pressure; partial output is checkpointed in SQLite and can be retried.
- Activity recreation no longer explicitly stops native inference or unloads the model.
- Pending prompts and chat history are saved; recent completed answers are refreshed on return.
- Original archives and indexed knowledge sources are counted separately.
- Setup through Termux remains independent of native offline conversation.
- Separate preview package ID avoids old ephemeral debug signing conflicts and preserves previous versions.

## v1.5 Termux-only verification
1. Install the new APK (separate package ID because earlier debug builds have different signing certificates).
2. In Termux, update the management script: `curl -fL https://raw.githubusercontent.com/landengodwin2002-hash/Arcdeck/prometheus-native-app/prometheus/termux/prometheus-sync.py -o "$PREFIX/bin/prometheus-sync" && chmod +x "$PREFIX/bin/prometheus-sync"`.
3. Run `prometheus-sync --status` to check the actual found model, original books, indexed Termux passages, and memory databases.
4. Run `prometheus-sync --model-only`, then open the new APK while the terminal command is running. It will receive and load the existing 4B abliterated GGUF.
5. Run `prometheus-sync --probe`, return to the APK, and read the response printed in Termux. This is an actual inference test, not a connection test.
6. Only then run `prometheus-sync --memory-only` and `prometheus-sync --books-only`, opening the app during each transfer. The original Termux data is not deleted.
7. If a reply is interrupted, the chat page displays its saved unfinished response and a Retry option.
8. For information on failures, run `prometheus-sync --diagnostics` after a sync.

## v1.6 Nova (distinct branded APK)
- Distinct launcher icon: the blue/pink Prometheus torch mascot, derived from the new approved visual mockup. Launcher label: Prometheus Nova, separate from old test apps.
- Better visible Send button, larger focusable message editor and fewer buttons covering messages; Android insets retained.
- Messages typed during busy/model-loading states are queued and automatically submitted after current work.
- Live inference stage and elapsed-time diagnostics. Normal reply budget begins at 192 tokens for faster responsiveness on the existing 4B abliterated model; user may change response length in settings.
- Full knowledge library and persisted chats/memory remain part of native app. The canonical imported model remains the existing locally stored abliterated GGUF.
- The app has *not* been proven to generate on a physical phone until `prometheus-sync --probe` succeeds; compilation alone is not inference validation.
