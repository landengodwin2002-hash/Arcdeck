#!/usr/bin/env python3
"""Validate the full integration before atomically installing/upgrading ArcDeck sources."""
from pathlib import Path
import argparse
import os
import tempfile

MAIN_EDITS = [('import com.playfieldportal.launcher.discord.DiscordBootstrap\n',
  'import com.playfieldportal.launcher.discord.DiscordBootstrap\n'
  'import com.playfieldportal.launcher.workspace.XmbWorkspaceOverlay\n'
  'import com.playfieldportal.core.ui.sound.MenuSound\n'
  'import com.playfieldportal.core.domain.repository.GameRepository\n'),
 ('    private var rotatePromptShowing = false\n',
  '    private var rotatePromptShowing = false\n'
  '    // ArcDeck additive XMB workspace overlay.\n'
  '    private var workspaceShowing by mutableStateOf(false)\n'
  '    @Inject lateinit var arcDeckGameRepository: GameRepository\n'),
 ('                //Left blank so that it can be ignored, preventing users from exiting the launcher.\n'
  '                //Back is already handled by the gamepad input handler.\n',
  '                if (workspaceShowing) {\n'
  '                    workspaceShowing = false\n'
  '                    menuSoundPlayer.play(MenuSound.BACK)\n'
  '                }\n'
  "                // Otherwise preserve PFP's existing HOME/back behavior.\n"),
 ('                            AppXmbHost()\n',
  '                            AppXmbHost()\n'
  '                            val arcDeckGames by remember(arcDeckGameRepository) { '
  'arcDeckGameRepository.observeAll() }.collectAsState(initial = null)\n'
  '                            XmbWorkspaceOverlay(\n'
  '                                expanded = workspaceShowing,\n'
  '                                onToggle = { workspaceShowing = !workspaceShowing },\n'
  '                                onClose = { workspaceShowing = false },\n'
  '                                onSound = { menuSoundPlayer.play(it) },\n'
  '                                games = arcDeckGames,\n'
  '                                onSetPfpFavorite = { id, favorite ->\n'
  '                                    lifecycleScope.launch {\n'
  '                                        try { arcDeckGameRepository.setFavorite(id, favorite) }\n'
  '                                        catch (e: kotlinx.coroutines.CancellationException) { throw e }\n'
  '                                        catch (e: Exception) { '
  'android.widget.Toast.makeText(this@MainActivity, e.message ?: "Could not update favorite", '
  'android.widget.Toast.LENGTH_LONG).show() }\n'
  '                                    }\n'
  '                                },\n'
  '                                onOpenPfpGame = { id ->\n'
  '                                    workspaceShowing = false\n'
  '                                    xmbViewModel.openGameDetailFromSettings(id)\n'
  '                                },\n'
  '                            )\n'),
 ('        // Let the gamepad handler process it first; fall back to normal dispatch\n',
  '        // F10 toggles the workspace for physical keyboard users.\n'
  '        if (event.keyCode == getSharedPreferences("arcdeck_workspace", '
  'MODE_PRIVATE).getInt("workspace_key", KeyEvent.KEYCODE_F10)) {\n'
  '            if (event.action == KeyEvent.ACTION_UP) {\n'
  '            workspaceShowing = !workspaceShowing\n'
  '            menuSoundPlayer.play(MenuSound.SELECT)\n'
  '            }\n'
  '            return true\n'
  '        }\n'
  '        if (workspaceShowing) {\n'
  '            if (event.action == KeyEvent.ACTION_UP &&\n'
  '                (event.keyCode == KeyEvent.KEYCODE_BACK ||\n'
  '                 event.keyCode == KeyEvent.KEYCODE_ESCAPE ||\n'
  '                 event.keyCode == KeyEvent.KEYCODE_BUTTON_B)) {\n'
  '                onBackPressedDispatcher.onBackPressed()\n'
  '                return true\n'
  '            }\n'
  '            // Route keyboard/controller focus to Compose while the workspace is open.\n'
  '            return super.dispatchKeyEvent(event)\n'
  '        }\n'
  '        // Let the gamepad handler process it first; fall back to normal dispatch\n'),
 ('        if (gamepadInputHandler.onMotionEvent(event)) return true\n',
  '        if (workspaceShowing) return super.onGenericMotionEvent(event)\n'
  '        if (gamepadInputHandler.onMotionEvent(event)) return true\n')]

def anchored(source, edits, path):
    for before, after in edits:
        if source.count(before) != 1:
            raise RuntimeError(f"Expected one source anchor in {path}: {before[:100]!r}")
        source = source.replace(before, after, 1)
    return source


def plan_install(repo):
    base = Path(__file__).resolve().parent
    activity = repo / "app/src/main/kotlin/com/playfieldportal/launcher/MainActivity.kt"
    manifest = repo / "app/src/main/AndroidManifest.xml"
    gradle = repo / "app/build.gradle.kts"
    for path in (activity, manifest, gradle):
        if not path.is_file():
            raise RuntimeError(f"Missing expected upstream file: {path}")
    result = {}
    text = activity.read_text(encoding="utf-8-sig")
    if "import com.playfieldportal.launcher.workspace.XmbWorkspaceOverlay" not in text:
        text = anchored(text, MAIN_EDITS, activity)
    else:
        marker = "        // F10 toggles the workspace for physical keyboard users.\n"
        base_comment = MAIN_EDITS[4][0]
        if marker in text:
            first = text.index(marker)
            last = text.index(base_comment, first) + len(base_comment)
            text = text[:first] + MAIN_EDITS[4][1] + text[last:]
    rotate = 'val rotatePrompt = showRotatePrompt(orientationMode, LocalConfiguration.current.orientation)'
    if rotate in text:
        text = anchored(text, [(rotate,
            'val rotatePrompt = !workspaceShowing && getSharedPreferences("arcdeck_workspace", MODE_PRIVATE).getBoolean("landscape_prompt", false) && showRotatePrompt(orientationMode, LocalConfiguration.current.orientation)')], activity)
    result[activity] = text
    text = manifest.read_text(encoding="utf-8-sig")
    if "com.termux.permission.RUN_COMMAND" not in text:
        text = anchored(text, [('    <application\n',
            '    <uses-permission android:name="com.termux.permission.RUN_COMMAND" />\n\n    <application\n')], manifest)
    if "workspace.WorkspaceTermuxReceiver" not in text:
        text = anchored(text, [('    </application>',
            '        <receiver android:name="com.playfieldportal.launcher.workspace.WorkspaceTermuxReceiver" android:exported="false" />\n    </application>')], manifest)
    import re
    text = re.sub(r'android:(icon|roundIcon)="[^"]+"', r'android:\1="@mipmap/arcdeck_launcher"', text)
    result[manifest] = text
    text = gradle.read_text(encoding="utf-8-sig")
    for dep in ('libs.androidx.documentfile', 'libs.coil.compose'):
        if f'implementation({dep})' not in text:
            text = anchored(text, [('    implementation(libs.androidx.core.ktx)\n',
                f'    implementation(libs.androidx.core.ktx)\n    implementation({dep})\n')], gradle)
    if '// ArcDeck Android regression tests' not in text:
        text = anchored(text, [('dependencies {\n', '''dependencies {
    // ArcDeck Android regression tests
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
''')], gradle)
        text = anchored(text, [('    buildFeatures {\n', '''    testOptions {
        unitTests { isIncludeAndroidResources = true }
        targetSdk = 36
    }
    buildFeatures {
''')], gradle)
    result[gradle] = text
    sources = base / 'files/app/src/main/kotlin/com/playfieldportal/launcher/workspace'
    required = {'XmbWorkspaceOverlay.kt', 'UnifiedCatalog.kt', 'UnifiedQuery.kt', 'UnifiedLibraryPane.kt',
                'WorkspaceFiles.kt', 'WorkspaceBackup.kt', 'WorkspaceTerminal.kt', 'WorkspaceNotes.kt'}
    if not required <= {p.name for p in sources.glob('*.kt')}:
        raise RuntimeError('Workspace source package is incomplete')
    for path in sorted(sources.glob('*.kt')):
        result[repo / 'app/src/main/kotlin/com/playfieldportal/launcher/workspace' / path.name] = path.read_text()
    for path in sorted((base/'tests/android').glob('*.kt')):
        result[repo/'app/src/test/kotlin/com/playfieldportal/launcher/workspace'/path.name] = path.read_text()
    for path in sorted((base/'files/app/src/main/res').rglob('*')):
        if path.is_file(): result[repo/'app/src/main/res'/path.relative_to(base/'files/app/src/main/res')] = path.read_text()
    for path in sorted((base/'tests/fixtures').glob('*')):
        if path.is_file(): result[repo/'app/src/test/resources'/path.name] = path.read_text()
    return result, {activity, manifest, gradle}


def atomic_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp = tempfile.mkstemp(prefix='.arcdeck-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as out:
            out.write(data)
            out.flush()
            os.fsync(out.fileno())
        os.replace(temp, path)
    finally:
        if os.path.exists(temp): os.unlink(temp)


def install(repo, check=False):
    plan, upstream = plan_install(repo)
    changed = {p: text.encode('utf-8') for p,text in plan.items() if not p.exists() or p.read_bytes()!=text.encode('utf-8')}
    if check:
        print(f'Validated {len(plan)} files; {len(changed)} updates required')
        return
    originals = {p: p.read_bytes() if p.exists() else None for p in changed}
    backups = []
    try:
        for path in changed:
            backup = path.with_name(path.name+'.arcdeck.orig')
            if path in upstream and not backup.exists():
                atomic_write(backup, originals[path])
                backups.append(backup)
        for path,data in changed.items():
            atomic_write(path,data)
    except Exception:
        for path,data in originals.items():
            if data is None: path.unlink(missing_ok=True)
            else: atomic_write(path,data)
        for path in backups: path.unlink(missing_ok=True)
        raise
    print(f'ArcDeck installed: {len(changed)} files updated; {len(plan)-len(changed)} already current')


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('project', type=Path)
    parser.add_argument('--check', action='store_true')
    args=parser.parse_args()
    try: install(args.project.expanduser().resolve(), args.check)
    except (RuntimeError, OSError) as exc: raise SystemExit(str(exc))
