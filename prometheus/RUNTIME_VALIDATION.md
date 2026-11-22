# Runtime validation

Status: real-model Android emulator acceptance and ARM64 packaging passed.
Phone release is blocked by the missing original Nova signing key; no update APK was published.

Tested application source: `6d1182cf1e97553dacf8a8cfafff3b0acfcc4642`.
Evidence: [Actions run 44988603632](https://github.com/landengodwin2002-hash/Arcdeck/actions/runs/44988603632),
artifact `Prometheus-Nova-validation` (ID `14741013514`, SHA-256
`f82ddbed102c88289fce53455ee57f8ed034d0ba62944cd6b09e9e2eabbe0f23`).

| Check | Result |
| --- | --- |
| Existing identity | Package `ai.prometheus.offline.nova`; Nova label, icon, colors and layout retained |
| Existing model | 2,707,514,688 bytes; SHA-256 `423f10b6ec2d99c3378143d7cd3b80eb4887b3ed92103103ac59173b404f4f7c`; verified before real Android inference |
| Maintained inference | Unmodified llama.cpp `afe77e18fadc2e673c6a6299495a443e17378ce4` inside the APK |
| Stream framing / lint | PASS: three JVM tests, including fragmented UTF-8; Android lint passed |
| Native optimization | PASS: actual compiler commands for all 108 x86_64 and ARM64 ggml sources use optimized builds |
| Real chat | PASS: keyboard input reached Send; streamed content appeared in the chat; readable response saved |
| App switching | PASS: generation completed after screen recreation and Home; draft and response survived returning |
| Context / Stop | PASS: follow-up returned the exact earlier code; Stop completed; another Send saved `Hello!` |
| Termux maintenance | PASS: exact GGUF and a book provisioned through the existing transfer protocol; native self-test returned a greeting |
| Legacy data / restart | PASS: Nova 1.6 database fixture retained messages, memory, projects, project items, passages and book metadata; original book bytes compared equal after restart |
| Phone package | PASS: ARM64 release compilation, 16 KiB ELF load alignment and APK ZIP alignment |
| Phone signing | BLOCKED: original Nova key absent in CI; signed-update and publication steps skipped |
| Physical phone | Not connected; not tested |

The Android acceptance test took 189.954 seconds. First streamed content
arrived after 55.37 seconds on the two-vCPU x86_64 SSE emulator. This establishes
working conversation, not acceptable performance on the physical phone.
External Wi-Fi and mobile data were disabled and the temporary maintenance
sender disconnected before the conversation test.

The exported database contains the exact counting answer, `cobalt-7319` on the
follow-up, the retained stopped partial, and `Hello!` after Stop. It also retains
the original legacy answer, both memory sentinels and all three book records.
The complete instrumentation marker is
`REAL_MODEL_STREAM_BACKGROUND_RECREATE_FOLLOWUP_STOP_PASS`.

## Runtime failures fixed

The native Debug build left inference kernels unoptimized and stalled before
first content. Native C/C++ now uses Release settings even in a debuggable test
APK, matching the upstream Android example. CI checks generated compiler
commands rather than assuming requested Gradle flags took effect.

Completion also rebuilt the active composer during key delivery, reordering
`Say hello.` into `Say ohell.`. Runtime updates now refresh conversation views
while retaining the actual EditText and IME composing state. The regression
checks both the same composer instance and exact typed text. The complete
real-model suite passed with that correction.

## Remaining phone acceptance

- Recover the original Nova signing key for an in-place update. The baseline
  certificate SHA-256 is `d3fdf460681daac8cab226a24f5f998692878d911846714651a4183ca6d080d6`.
  Keep the installed Nova app. A different key requires a separately verified
  backup/restore migration; an emulator database fixture does not validate that migration.
- Verify the existing phone database, GGUF and books survive the actual update.
- Measure ARM64 model loading, first-token latency, generation speed, RAM,
  thermal behavior and continued generation under the phone's battery policy.
- Check the Android 16 keyboard/composer, app switching and real Termux transfer
  on the Motorola phone.
