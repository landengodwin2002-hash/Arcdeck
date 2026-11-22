# Runtime validation

Prometheus 1.0.0 passed its original emulator tests, but the physical-phone user
reported stalled generation, interruption when switching pages and no visible
books. That report takes precedence over the earlier emulator acceptance.

Version 1.0.1 (same package/key, update code 2) is being tested against Android 16
with page switches before first content and while streaming. Imported book
titles and indexed source titles must be visible. The engine remains unmodified
llama.cpp at the same pinned revision and uses the exact existing 4B GGUF.

Changes under test: live worker status retained during page redraws, real native
prompt progress, shorter default system/profile overhead, bounded recent prompt
history, smaller CPU batches/context, serialized foreground-service holds and a
load wake lock. The Library lists originals/indexed sources and import outcomes.
The existing diagnostic copy/export now includes native logs, memory, service
state and Android process-exit reasons. Database/model/book paths are unchanged.

The phone's native stall cause is not yet confirmed. A copied phone diagnostic
is still required; these changes must not be declared a proven physical-phone
fix merely because compilation or another emulator run succeeds.

A further confirmed lifecycle defect was the Activity-owned queued-prompt handler.
Messages sent before the model was ready could wait for a resumed Chat screen.
Queue persistence and the load/generation handoff now live in the application
worker; it retains the foreground-service hold while starting the queued reply.
The Android 16 regression queues actual keyboard input before loading the GGUF,
presses Home and requires native progress without returning to the Chat screen.
