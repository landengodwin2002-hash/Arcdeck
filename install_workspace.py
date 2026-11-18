#!/usr/bin/env python3
"""Apply additive ArcDeck Workspace edits to a PlayFieldPortal Git checkout.

This does NOT copy upstream PlayFieldPortal sources into the patch archive.
A clean checkout of JohnnyCollado/PlayFieldPortal at v1.3.3 is required.
Every edit is checked for a known upstream anchor and backed up first.
"""
from pathlib import Path
import argparse
import shutil
import sys


def rewrite(path: Path, edits: list[tuple[str, str]]):
    source = path.read_text(encoding="utf-8-sig")
    amended = source
    if "XmbWorkspaceOverlay" in source and path.name == "MainActivity.kt":
        print(f"[skip] Already installed in {path}")
        return
    for old, new in edits:
        count = amended.count(old)
        if count != 1:
            raise RuntimeError(f"Expected one source anchor in {path}, found {count}: {old[:110]!r}")
        amended = amended.replace(old, new, 1)
    original = path.with_name(path.name + ".arcdeck.orig")
    if not original.exists():
        shutil.copy2(path, original)
    path.write_text(amended, encoding="utf-8")
    print(f"[updated] {path}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("project", help="Path to checked-out JohnnyCollado/PlayFieldPortal project")
    args = parser.parse_args()
    repo = Path(args.project).expanduser().resolve()
    main_activity = repo / "app/src/main/kotlin/com/playfieldportal/launcher/MainActivity.kt"
    manifest = repo / "app/src/main/AndroidManifest.xml"
    gradle = repo / "app/build.gradle.kts"
    source = Path(__file__).resolve().parent / "files/app/src/main/kotlin/com/playfieldportal/launcher/workspace/XmbWorkspaceOverlay.kt"
    extras = ["UnifiedQuery.kt", "UnifiedCatalog.kt", "UnifiedLibraryPane.kt"]
    output = repo / "app/src/main/kotlin/com/playfieldportal/launcher/workspace/XmbWorkspaceOverlay.kt"
    for p in (main_activity, manifest, gradle, source):
        if not p.is_file():
            raise SystemExit(f"Missing expected file: {p}")

    rewrite(main_activity, [
        (
            "import com.playfieldportal.launcher.discord.DiscordBootstrap\n",
            "import com.playfieldportal.launcher.discord.DiscordBootstrap\n"
            "import com.playfieldportal.launcher.workspace.XmbWorkspaceOverlay\n"
            "import com.playfieldportal.core.ui.sound.MenuSound\n"
            "import com.playfieldportal.core.domain.repository.GameRepository\n"
        ),
        (
            "    private var rotatePromptShowing = false\n",
            "    private var rotatePromptShowing = false\n"
            "    // ArcDeck additive XMB workspace overlay.\n"
            "    private var workspaceShowing by mutableStateOf(false)\n"
            "    @Inject lateinit var arcDeckGameRepository: GameRepository\n"
        ),
        (
            "                //Left blank so that it can be ignored, preventing users from exiting the launcher.\n"
            "                //Back is already handled by the gamepad input handler.\n",
            "                if (workspaceShowing) {\n"
            "                    workspaceShowing = false\n"
            "                    menuSoundPlayer.play(MenuSound.BACK)\n"
            "                }\n"
            "                // Otherwise preserve PFP's existing HOME/back behavior.\n"
        ),
        (
            "                            AppXmbHost()\n",
            "                            AppXmbHost()\n"
            "                            val arcDeckGames by remember(arcDeckGameRepository) { arcDeckGameRepository.observeAll() }.collectAsState(initial = null)\n"
            "                            XmbWorkspaceOverlay(\n"
            "                                expanded = workspaceShowing,\n"
            "                                onToggle = { workspaceShowing = !workspaceShowing },\n"
            "                                onClose = { workspaceShowing = false },\n"
            "                                onSound = { menuSoundPlayer.play(it) },\n"
            "                                games = arcDeckGames,\n"
            "                                onSetPfpFavorite = { id, favorite ->\n"
            "                                    lifecycleScope.launch { arcDeckGameRepository.setFavorite(id, favorite) }\n"
            "                                },\n"
            "                                onOpenPfpGame = { id ->\n"
            "                                    workspaceShowing = false\n"
            "                                    xmbViewModel.openGameDetailFromSettings(id)\n"
            "                                },\n"
            "                            )\n"
        ),
        (
            "        // Let the gamepad handler process it first; fall back to normal dispatch\n",
            "        // F10 toggles the workspace for physical keyboard users.\n"
            "        if (event.keyCode == KeyEvent.KEYCODE_F10 && event.action == KeyEvent.ACTION_UP) {\n"
            "            workspaceShowing = !workspaceShowing\n"
            "            menuSoundPlayer.play(MenuSound.SELECT)\n"
            "            return true\n"
            "        }\n"
            "        if (workspaceShowing) {\n"
            "            if (event.action == KeyEvent.ACTION_UP &&\n"
            "                (event.keyCode == KeyEvent.KEYCODE_BACK ||\n"
            "                 event.keyCode == KeyEvent.KEYCODE_ESCAPE ||\n"
            "                 event.keyCode == KeyEvent.KEYCODE_BUTTON_B)) {\n"
            "                workspaceShowing = false\n"
            "                menuSoundPlayer.play(MenuSound.BACK)\n"
            "                return true\n"
            "            }\n"
            "            // Route keyboard/controller focus to Compose while the workspace is open.\n"
            "            return super.dispatchKeyEvent(event)\n"
            "        }\n"
            "        // Let the gamepad handler process it first; fall back to normal dispatch\n"
        ),
        (
            "        if (gamepadInputHandler.onMotionEvent(event)) return true\n",
            "        if (workspaceShowing) return super.onGenericMotionEvent(event)\n"
            "        if (gamepadInputHandler.onMotionEvent(event)) return true\n"
        ),
    ])

    # Special permission: user must explicitly authorize in Android AND Termux.
    if 'com.termux.permission.RUN_COMMAND' not in manifest.read_text():
        rewrite(manifest, [
            ('    <application\n',
             '    <!-- Optional Termux external command execution; explicit user grant required. -->\n'
             '    <uses-permission android:name="com.termux.permission.RUN_COMMAND" />\n\n'
             '    <application\n')
        ])
    else:
        print("[skip] Termux permission already present")
    if "implementation(libs.androidx.documentfile)" not in gradle.read_text():
        rewrite(gradle, [
            ('    implementation(libs.androidx.core.ktx)\n',
             '    implementation(libs.androidx.core.ktx)\n'
             '    implementation(libs.androidx.documentfile) // ArcDeck Workspace SAF browser\n')
        ])
    else:
        print("[skip] DocumentFile dependency already present")

    if output.exists():
        print(f"[skip] Existing workspace file preserved: {output}")
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, output)
        print(f"[added] {output}")
    for extra in extras:
        src = source.parent / extra
        dst = output.parent / extra
        if not src.is_file():
            raise SystemExit(f"Missing required source: {src}")
        if not dst.exists():
            shutil.copy2(src, dst)
            print(f"[added] {dst}")
        else:
            print(f"[skip] Existing source preserved: {dst}")
    workflow = Path(__file__).resolve().parent / "files/.github/workflows/arcdeck-workspace-debug.yml"
    target = repo / ".github/workflows/arcdeck-workspace-debug.yml"
    if workflow.is_file() and not target.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(workflow, target)
        print(f"[added] {target}")
    print("\nPatch installed. Build with ./gradlew :app:assembleLiteDebug")
    print("Original modified files were backed up beside each file as *.arcdeck.orig")
    print("Installing a modified APK over the official signed APK will NOT work; use a debug build separately.")


if __name__ == "__main__":
    main()
