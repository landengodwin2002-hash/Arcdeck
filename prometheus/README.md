# Prometheus

One offline Android chat application, using the existing abliterated 4B GGUF and Termux maintenance data.

- Package: `ai.prometheus.offline.chat`
- Version: `1.0.1`
- Interface: existing Nova layout, colors and icon, labeled Prometheus
- Runtime: unmodified llama.cpp `afe77e18fadc2e673c6a6299495a443e17378ce4`
- Stable release certificate: `scripts/release-certificate.sha256`

This independent installation does not replace or uninstall Nova. Existing Termux
files stay in place. The new app uses its own private database/model/book copies;
private history held only inside Nova is not automatically accessible to another
Android application.

## Install and reuse the existing model

Install the signed `Prometheus.apk`. In the existing Termux setup, run:

```sh
prometheus-sync
```

Open **Prometheus** and allow the existing GGUF, saved data and books to transfer.
The Library page lists transferred originals and indexed sources, plus the last
transfer result. For a model-only transfer, use `prometheus-sync --model-only`;
that command deliberately does not send books or saved data. Then send a message. The conversation then runs inside the APK without Termux or internet.
The app uses the same transfer protocol as the installed maintenance command.
No new model download is required when the existing GGUF is available in Termux.

Once conversation works, reuse the existing Termux data:

```sh
prometheus-sync --memory-only
prometheus-sync --books-only
```

Open Prometheus after each command to receive it. The memory transfer imports
existing Termux chats, corrections, projects and indexed passages. Original
Termux databases and books are read without deleting or replacing them.
`prometheus-sync --probe` runs a real local-model self-test;
`prometheus-sync --diagnostics` reads the captured diagnostic report.
Model replacement retains the prior GGUF as a timestamped backup.

## Conversation and storage

Application-owned work streams answer text, checkpoints partial replies, and
commits the final answer independently of the Activity. A foreground service
and bounded wake lock support generation while switching apps. The composer
stays in place during runtime updates. A full process interruption retains
saved history and exposes any checkpointed partial for recovery.

Storage is `databases/prometheus-native.db`, `files/models/prometheus.gguf`,
and `files/books/`. The schema remains compatible with existing imported data.
Starting a new chat archives the old conversation rather than deleting it.
Ordinary chat uses the model's embedded template and bounded conversation
context; memory/retrieval/companion expansion remains paused.

## Build, test and sign

Use Java 17, Gradle 8.13, Android SDK/build tools 35, NDK 27.2.12479018 and CMake 3.22.1.

```sh
cd prometheus
python3 scripts/fetch_runtime.py
gradle -PprometheusAbis=x86_64 :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
python3 scripts/check_native_optimization.py
PROMETHEUS_TEST_MODEL=/absolute/path/existing-4b-abliterated.gguf bash scripts/android_acceptance.sh
gradle :app:assembleRelease
```

Acceptance provisioning requires a clean disposable emulator and host. It
refuses physical phones, existing app installations and existing host Termux
data. It exercises the real model through the keyboard/composer, displayed
streaming, Home, recreation, follow-up context, Stop, a fresh Send, database
restart, original-book bytes and real exports from old Termux schemas.

CI produces the tested unsigned ARM64 package. Sign it with the privately
backed-up permanent key using `scripts/sign_release.py`, supplying `--apk`,
`--credentials` (the private backup's `signing.json`), `--apksigner`, `--zipalign`,
`--aapt2` and `--out`. The signer verifies the new package, production build,
permanent certificate and 16 KiB APK alignment. Never commit private signing
credentials or generate a replacement key for routine updates.

Measured runtime evidence and remaining physical-phone checks are recorded in
`RUNTIME_VALIDATION.md`. Compilation alone is not acceptance.

The 1.0.1 regression target is the reported physical-phone stall/page-switch
failure. Generation state is displayed from the application worker on every
page. Native prompt-processing counts distinguish prefill from streamed output.
Prompt history is bounded to 1,024 tokens when trimming older turns, within a
2,048-token model context; the database retains full messages. CPU batches are
bounded to 32. Diagnostics → Copy includes the live native log, progress, RAM,
service state and recent Android process-exit reasons.
