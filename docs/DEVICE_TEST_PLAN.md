# Device test gate before anyone sets this as their default HOME app

1. **Build gate**: `./gradlew :app:assembleLiteDebug` succeeds in clean official PFP source checkout after the patch; debug APK installs alongside official version. Confirm the manifest HOME intent and optional Termux permission.
2. **Recovery gate**: before choosing default, open System → Change default home app, verify Moto App Launcher appears; make PFP test launcher default only after confirming the escape. Verify regular Android Settings from Quick Settings independently.
3. **Baseline XMB**: launch existing PFP interface; waves, sound effects, artwork, native controller actions, GameBoot, Papaya/GameHub library and emulator launches unchanged.
4. **Unified games**: scan game library; search title and `type:game`; open a game detail through PFP's original route; confirm favorite change persists in both PFP and ArcDeck; verify recently-played sort.
5. **Unified apps**: all installed launchable apps indexed; tap launches Android activity and Home returns to the XMB; uninstall/reinstall marks previous object missing without losing tags.
6. **Files**: choose 2 allowed storage roots; index; verify search, folder and file results; open file with a supporting app. Revoke one grant, rescan, confirm no mass deletion; re-grant and recover entries. Test 12,000-item cap.
7. **Relations**: two collections referencing the same game; rename alias; apply tags; move ordering; smart query update after adding tags; game ROM linked via exact SAF URI should not duplicate default search.
8. **Commands**: safe command shortcut previews in terminal, requires explicit confirmation and Termux's two permission gates. Denied permission or missing Termux produces a clear error, not a crash.
9. **Backup**: export JSON; create a new collection; restore; verify original game database and local files unchanged; regrant file trees if needed. Test corrupt and oversized JSON.
10. **Input/appearance**: touch, D-pad, shoulder triggers, controller B/Back, window drag, resize, multiple panels, rotation/portrait prompt, sound volume and system navigation bars.
11. **Performance**: 10,000-document root, large game library, indexed searches, repeated activity recreation, Android low-memory process death, thermal/battery usage.
12. **Security/license**: no ROM exfiltration, no unsolicited network transfer, no use of shared signing keys. Upstream license/redistribution authority must be resolved before publicly publishing a combined modified APK.

Keep official Play Field Portal installed until every gate passes. If HOME controls fail, change Android Settings → Apps → Default apps → Home app to Moto App Launcher.
