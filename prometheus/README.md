# Prometheus Nova

The immediate goal is dependable offline conversation in the existing Nova application.

- Application ID: `ai.prometheus.offline.nova`
- Label, icon, colors, layout, navigation, and composer: existing Nova
- Model: existing Huihui Qwen3.5 4B abliterated GGUF
- Runtime: unmodified upstream llama.cpp, pinned to `afe77e18fadc2e673c6a6299495a443e17378ce4`
- JNI: lifecycle adapter for upstream llama-server; no custom tokenizer, template fallback, sampling, or decoding loop
- Runtime communication: authenticated loopback inside the app process; no Termux service or internet needed for conversations

## Storage and lifecycle

The database stays at `databases/prometheus-native.db`. The model stays at
`files/models/prometheus.gguf`; originals stay under `files/books/`.
Existing tables are retained and schema setup is additive. Starting a new chat
sets a conversation boundary rather than deleting previous messages.

An application-owned worker performs model loading and generation. A foreground
service and a bounded wake lock keep user-initiated replies running when the
screen changes. Activity recreation does not own or cancel the work.

Partial replies are checkpointed. The final reply and removal of its recovery
record are committed in one SQLite transaction before UI notification. After a
process interruption, the existing recovery panel exposes the saved partial.
Force-stop ends active work; it does not erase the conversation.

Ordinary conversation uses real system/user/assistant messages and the model's
embedded template. Thinking is disabled through upstream template controls.
Full conversation text remains in SQLite; the oldest complete turns are omitted
from a request when the runtime's token count exceeds the bounded context.
Book retrieval, memory expansion, and companion development are paused.

## Termux maintenance

Existing commands remain available:

```sh
prometheus-sync --model-only
prometheus-sync --books-only
prometheus-sync --memory-only
prometheus-sync --probe
prometheus-sync --diagnostics
```

Run the command, open Nova, and let the temporary sender finish. All original
Termux files remain in place. File lengths and SHA-256 hashes are verified.
An existing private model with the same hash is reused. Replacing a model
retains the previous GGUF as a timestamped backup instead of deleting it.

## Build and runtime validation

Use Java 17, Gradle 8.13, SDK/build tools 35, NDK 27.2.12479018, and CMake 3.22.1.

```sh
cd prometheus
python3 scripts/fetch_runtime.py
gradle -PprometheusAbis=x86_64 :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
PROMETHEUS_TEST_MODEL=/absolute/path/Huihui-Qwen3.5-4B-abliterated.Q4_K_M.gguf \
    bash scripts/android_acceptance.sh
```

The phone build defaults to ARM64. The x86_64 test build uses the same package,
UI, Java code, native adapter, and pinned upstream runtime.

The Android acceptance test provisions the model through the existing Termux
transfer protocol, disables external connectivity, sends through the actual
composer, observes streamed content, recreates the screen, presses Home during
generation, returns, asks a follow-up, stops a response, and sends again. It also
checks saved books/memory and process-restart persistence. Test reports and
native/Android logs are retained; compilation alone is not acceptance.

The exact 4B model already produced a readable streamed reply in a Linux runtime
test. Android results are recorded in `RUNTIME_VALIDATION.md`; a physical phone
must still establish ARM64 behavior, memory use, speed, thermal behavior,
battery policy, keyboard layout, and real Termux integration.

## Phone update signing is currently blocked

The prior `Prometheus-Nova-v1.6.apk` uses this signing-certificate SHA-256:

```text
d3fdf460681daac8cab226a24f5f998692878d911846714651a4183ca6d080d6
```

The original private signing key was not found in the repository or the saved
project files examined. The saved ArcDeck signing backup belongs to another
application and does not match Nova. The old workflow created a debug-signed
APK without preserving that key.

Do not uninstall the installed Nova to get around a signature error. Its
private database/model/books would be at risk. A phone-ready in-place update
requires the matching private key.

The workflow builds and tests independently of release signing. It publishes
one `Prometheus-Nova.apk` only after runtime tests pass and
`scripts/check_signing.py` verifies the matching certificate. It does not
publish a newly signed experimental identity as an update.

For an original key supplied through repository secrets, use
`PROMETHEUS_SIGNING_KEY_B64`, `PROMETHEUS_KEYSTORE_PASSWORD`,
`PROMETHEUS_KEY_ALIAS`, and `PROMETHEUS_KEY_PASSWORD`.
Local release signing uses `PROMETHEUS_KEYSTORE` plus those password/alias
environment variables. Never commit a private key or its password.
