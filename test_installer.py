"""Local patcher checks; intentionally uses synthetic source anchors, not upstream code."""
from pathlib import Path
import subprocess
import tempfile
import sys

SCRIPT = Path(__file__).with_name("install_workspace.py")


def test():
    with tempfile.TemporaryDirectory() as d:
        base = Path(d)
        act = base / "app/src/main/kotlin/com/playfieldportal/launcher/MainActivity.kt"
        manifest = base / "app/src/main/AndroidManifest.xml"
        gradle = base / "app/build.gradle.kts"
        act.parent.mkdir(parents=True)
        manifest.parent.mkdir(parents=True, exist_ok=True)
        act.write_text('''package test
import com.playfieldportal.launcher.discord.DiscordBootstrap
    private var rotatePromptShowing = false
                //Left blank so that it can be ignored, preventing users from exiting the launcher.
                //Back is already handled by the gamepad input handler.
                            AppXmbHost()
        // Let the gamepad handler process it first; fall back to normal dispatch
        if (gamepadInputHandler.onMotionEvent(event)) return true
''')
        manifest.write_text('<manifest>\n    <application\n    />\n</manifest>\n')
        gradle.write_text('    implementation(libs.androidx.core.ktx)\n')
        first = subprocess.run([sys.executable,str(SCRIPT),str(base)],capture_output=True,text=True)
        assert first.returncode == 0, first.stdout + first.stderr
        assert 'XmbWorkspaceOverlay(' in act.read_text()
        assert 'com.termux.permission.RUN_COMMAND' in manifest.read_text()
        assert 'implementation(libs.androidx.documentfile)' in gradle.read_text()
        for filename in ('XmbWorkspaceOverlay.kt', 'UnifiedQuery.kt', 'UnifiedCatalog.kt', 'UnifiedLibraryPane.kt'):
            assert (act.parent / 'workspace' / filename).exists(), filename
        assert 'onOpenPfpGame' in act.read_text()
        assert 'onSetPfpFavorite' in act.read_text()
        second = subprocess.run([sys.executable,str(SCRIPT),str(base)],capture_output=True,text=True)
        assert second.returncode == 0, second.stdout + second.stderr
        assert act.read_text().count('XmbWorkspaceOverlay(') == 1
        assert manifest.read_text().count('com.termux.permission.RUN_COMMAND') == 1
        assert gradle.read_text().count('implementation(libs.androidx.documentfile)') == 1
    print('PASS: atomic anchor matching, patch installation, idempotence and file copying')

if __name__ == '__main__': test()
