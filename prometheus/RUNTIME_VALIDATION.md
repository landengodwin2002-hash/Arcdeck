# Runtime validation

A signed **Prometheus 1.0.0** ARM64 APK has been produced and saved for delivery.
The new package is `ai.prometheus.offline.chat`; Nova remains installed separately.
Physical-phone acceptance is still required.

Tested application source: `2f6d0310532dcabdf1ae8c2363b5a09248c650e3`.
[Actions run 45053577313](https://github.com/landengodwin2002-hash/Arcdeck/actions/runs/45053577313)
completed successfully. Evidence artifact: `Prometheus-validation`, ID `14766828272`,
SHA-256 `1ac6b7447cfb487d6d692fb3187f1b0455a81b2c85b87ab0009d8bbbcd4ee8d8`.

## Delivered APK

| Property | Value |
| --- | --- |
| File | `Prometheus.apk` |
| Version | 1.0.0, code 1 |
| Package | `ai.prometheus.offline.chat` |
| Build | Non-debuggable release; ARM64 only |
| Size | 24,347,373 bytes |
| APK SHA-256 | `805488a34e0ef5cafcf2ebd5ad09cc78f85773d8ef2f355e412d02fc5f743750` |
| Permanent signing certificate SHA-256 | `00d61707b35ba92f0b022f5e77f6d3679156de5bffb57f3f733cb31d9b36158a` |
| Signature verification | PASS: APK v2 and v3; one RSA-3072 signer |
| Alignment | PASS: 16 KiB ELF load segments and APK ZIP alignment, including after signing |

The signing key and credentials are privately backed up for future updates.
Only the public certificate fingerprint is committed. Routine updates must
retain this package and key, and increment the version code.

## Runtime and data acceptance

| Check | Result |
| --- | --- |
| Existing model | PASS: exact 2,707,514,688-byte 4B abliterated GGUF; SHA-256 `423f10b6ec2d99c3378143d7cd3b80eb4887b3ed92103103ac59173b404f4f7c` |
| Maintained engine | Unmodified llama.cpp `afe77e18fadc2e673c6a6299495a443e17378ce4` |
| Stream framing and lint | PASS: three JVM stream tests, including fragmented UTF-8; Android lint passed |
| Native optimization | PASS: generated compiler commands for all 108 x86_64/ARM64 ggml sources use optimized builds |
| Actual conversation | PASS: real keyboard input reached Send and streamed content appeared in the chat |
| Lifecycle | PASS: screen recreation and Home during generation; draft and final response survived returning |
| Context and cancellation | PASS: follow-up recalled `cobalt-7319`; Stop completed; another Send saved `Hello!` |
| Termux transfer | PASS: model, original book, existing-schema chat/memory/project/correction/passage export, and native model probe |
| Preservation | PASS: legacy database records and original book bytes survived process restart; imported Termux data was retained; all original Termux fixture database hashes stayed unchanged |
| Production package | PASS: ARM64 release compilation, package identity, non-debuggable flag, native/ZIP alignment and permanent certificate |

The exported database contains both legacy and Termux chat records, the exact
1-to-30 counting reply, the exact-code follow-up, a stopped partial and `Hello!`
after Stop. Imported memory, correction, project, note, completed task and
indexed passage were checked individually. All three book records remained.

Instrumentation took 202.665 seconds. First streamed content arrived after
56.976 seconds on a two-vCPU x86_64 SSE emulator. This establishes real inference
and correct lifecycle behavior; it is not a physical-phone speed measurement.
Wi-Fi/mobile data were disabled and the maintenance sender disconnected before
conversation testing. Conversations use the engine inside the APK.

The acceptance harness's initial setup ordering failure was corrected by
creating isolated Termux fixtures before maintenance creates diagnostic folders.
The complete corrected Android run then passed. It still refuses physical
phones, existing installations and existing host Termux data during provisioning.

## Remaining physical-phone checks

- Install this independent APK and transfer the user's existing GGUF through the existing `prometheus-sync` command.
- Measure ARM64 loading, first-token latency, generation speed, RAM, heat and behavior under the phone's battery policy.
- Check the Android 16 keyboard, app switching and screen-off generation on the Motorola phone.
- Verify imports of the user's real Termux books, chats, memories and projects. Existing Nova-private history is not automatically extracted into another app; Nova and its data are retained.
