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
| Android composer / lifecycle test | NOT PASSED: run 44964974454 reached Send, then exceeded 600 seconds before visible content |
| Native optimization correction | C/C++ inference forced to Release, following upstream Android example; generated compiler-command guard added; rerun pending |
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
uncompressed SSE to avoid transparent HTTP compression buffering. These changes
remain hypotheses until the same conversation test passes.
