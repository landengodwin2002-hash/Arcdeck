# Runtime validation

Current target: independent **Prometheus 1.0.0**, package `ai.prometheus.offline.chat`.
The user authorized a separate installation reusing the existing Termux model
and data. Nova is left installed; its lost signing key no longer blocks this app.
The new permanent signing key is privately backed up and its public certificate
is pinned in `scripts/release-certificate.sha256`.

The inherited runtime passed full real-model Android acceptance and ARM64
packaging in run [44988603632](https://github.com/landengodwin2002-hash/Arcdeck/actions/runs/44988603632).
The new package is being rebuilt and retested, including import and preservation
of old Termux chat, memory, project and knowledge database schemas. A signed APK
will be delivered only after this run and permanent-certificate verification.

Model: existing 2,707,514,688-byte Huihui Qwen3.5 4B abliterated GGUF, SHA-256
`423f10b6ec2d99c3378143d7cd3b80eb4887b3ed92103103ac59173b404f4f7c`.
Runtime: unmodified llama.cpp `afe77e18fadc2e673c6a6299495a443e17378ce4`.

Physical phone still needs installation, ARM64 inference speed/RAM/thermal and
battery-policy checks, Android 16 keyboard/app-switch checks, and transfers from
the user's real Termux data. Prior emulator first-content latency was 55.37
seconds on two x86_64 SSE vCPUs; this is not a phone performance measurement.
