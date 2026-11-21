# Runtime validation

Status: implementation and Android validation in progress; not a phone-ready release.

| Check | Evidence / status |
| --- | --- |
| Existing app identity | Package `ai.prometheus.offline.nova`, label/icon unchanged |
| Exact 4B GGUF | 2,707,514,688 bytes; SHA-256 `423f10b6ec2d99c3378143d7cd3b80eb4887b3ed92103103ac59173b404f4f7c` |
| Maintained runtime | Unmodified llama.cpp `afe77e18fadc2e673c6a6299495a443e17378ce4` |
| Real host inference | PASS: "Say hello in one short sentence." returned "Hello!" in two content chunks; 31 prompt tokens; 1.27 seconds including template/token endpoints |
| Android compilation / stream tests | Running |
| Android real-model / lifecycle test | Pending |
| Phone update certificate | BLOCKED: matching original private key unavailable |
| Physical Motorola phone | Not connected; not tested |

The host inference result establishes actual model/template compatibility, not
Android performance. An emulator result also cannot establish the phone's
ARM64 instructions, real available RAM, thermal behavior, battery restrictions,
or signing/update state.
