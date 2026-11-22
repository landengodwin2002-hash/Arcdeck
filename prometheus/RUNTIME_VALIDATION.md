# Runtime validation

Status: implementation and Android validation in progress; not a phone-ready release.

| Check | Evidence / status |
| --- | --- |
| Existing app identity | Package `ai.prometheus.offline.nova`, label/icon unchanged |
| Exact 4B GGUF | 2,707,514,688 bytes; SHA-256 `423f10b6ec2d99c3378143d7cd3b80eb4887b3ed92103103ac59173b404f4f7c` |
| Maintained runtime | Unmodified llama.cpp `afe77e18fadc2e673c6a6299495a443e17378ce4` |
| Real host inference | PASS: "Say hello in one short sentence." returned "Hello!" in two content chunks; 31 prompt tokens; 1.27 seconds including template/token endpoints |
| Android compilation / stream tests | PASS in runs 44963126746 and 44964974454; three stream-framing tests and lint |
| Android real-model probe | PASS in both runs: the exact 4B model returned "Hello!" through the app runtime |
| Android composer / lifecycle test | Run 44987362656 streamed a reply, completed after Home/recreation, recalled the code on follow-up and stopped generation. Final send exposed keyboard text reordering; complete suite not passed yet |
| Native optimization correction | PASS in run 44987362656: all 54 ggml compile commands optimized. First reply: 111 prompt tokens at 2.93 tokens/s; 89 output tokens at 2.20 tokens/s on two-vCPU SSE emulator |
| Phone update certificate | BLOCKED: matching original private key unavailable |
| Physical Motorola phone | Not connected; not tested |

The host inference result establishes actual model/template compatibility, not
Android performance. An emulator result also cannot establish the phone's
ARM64 instructions, real available RAM, thermal behavior, battery restrictions,
or signing/update state.

The failed composer run used a native Debug configuration. The replacement
configuration optimizes both C and C++ kernels even inside the debuggable test
APK. The workflow checks the effective optimization flags in generated compiler
commands before starting runtime acceptance. Streaming also explicitly requests
uncompressed SSE to avoid transparent HTTP compression buffering. This removed
the previous first-response stall in the same emulator test. It does not
establish physical phone performance.

The remaining keyboard failure occurred when completion rebuilt the active
composer during incoming key events ("Say hello." became "Say ohell.").
Runtime updates now refresh the conversation and recovery views while retaining
the same EditText and IME composing state. The regression test requires both
exact keyboard input and the same composer instance. A full rerun with the old
database fixture and an ARM64 packaging check is pending.
