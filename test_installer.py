"""Installer regression checks: idempotent upgrades and all-or-nothing preflight."""
from pathlib import Path
import subprocess
import tempfile
import sys

SCRIPT=Path(__file__).with_name('install_workspace.py')
ACT='''package test
import com.playfieldportal.launcher.discord.DiscordBootstrap
    private var rotatePromptShowing = false
                //Left blank so that it can be ignored, preventing users from exiting the launcher.
                //Back is already handled by the gamepad input handler.
                            AppXmbHost()
        // Let the gamepad handler process it first; fall back to normal dispatch
        if (gamepadInputHandler.onMotionEvent(event)) return true
                val rotatePrompt = showRotatePrompt(orientationMode, LocalConfiguration.current.orientation)
        setContent {
    override fun onResume() {
        super.onResume()
            hide(WindowInsetsCompat.Type.systemBars())
'''
MANIFEST='<manifest>\n    <application\n    >\n    </application>\n</manifest>\n'
GRADLE='''android {
    buildFeatures {
    }
}
dependencies {
    implementation(libs.androidx.core.ktx)
}
'''


def fixture(root):
    act=root/'app/src/main/kotlin/com/playfieldportal/launcher/MainActivity.kt'
    manifest=root/'app/src/main/AndroidManifest.xml'
    gradle=root/'app/build.gradle.kts'
    for path,data in [(act,ACT),(manifest,MANIFEST),(gradle,GRADLE)]:
        path.parent.mkdir(parents=True,exist_ok=True);path.write_text(data)
    paths=root/'app/src/main/res/xml/file_paths.xml'
    paths.parent.mkdir(parents=True,exist_ok=True);paths.write_text('<paths>\n</paths>\n')
    return act,manifest,gradle


def invoke(root,*args):
    return subprocess.run([sys.executable,str(SCRIPT),str(root),*args],capture_output=True,text=True)


def main():
    with tempfile.TemporaryDirectory() as d:
        base=Path(d); act,manifest,gradle=fixture(base)
        check=invoke(base,'--check'); assert check.returncode==0,check.stdout+check.stderr
        assert act.read_text()==ACT
        first=invoke(base);assert first.returncode==0,first.stdout+first.stderr
        assert 'workspace.WorkspaceTermuxReceiver' in manifest.read_text()
        assert 'workspace.WorkspaceOverlayService' in manifest.read_text()
        assert 'workspace.WorkspaceShareActivity' in manifest.read_text()
        assert 'FOREGROUND_SERVICE_SPECIAL_USE' in manifest.read_text()
        assert 'pfpSections = arcDeckPfpState.categories' in act.read_text()
        assert 'handleArcDeckIntent' in act.read_text()
        assert 'android:exported="false"' in manifest.read_text()
        assert 'workspace_key' in act.read_text()
        assert 'landscape_prompt' in act.read_text()
        assert 'testImplementation(libs.robolectric)' in gradle.read_text()
        copied=act.parent/'workspace/UnifiedCatalog.kt'
        assert copied.exists()
        copied.write_text('// previous ArcDeck version')
        second=invoke(base);assert second.returncode==0,second.stdout+second.stderr
        assert copied.read_text().startswith('package ')
        assert act.read_text().count('XmbWorkspaceOverlay(')==1
        assert act.read_text().count('override fun onNewIntent')==1
        assert manifest.read_text().count('workspace.WorkspaceTermuxReceiver')==1
        assert gradle.read_text().count('implementation(libs.coil.compose)')==1
        stable={p:p.read_bytes() for p in base.rglob('*') if p.is_file()}
        third=invoke(base);assert third.returncode==0,third.stdout+third.stderr
        assert all(p.read_bytes()==data for p,data in stable.items())
    with tempfile.TemporaryDirectory() as d:
        base=Path(d);act,manifest,gradle=fixture(base)
        gradle.write_text('unrecognized upstream file')
        failed=invoke(base);assert failed.returncode!=0
        assert act.read_text()==ACT and manifest.read_text()==MANIFEST
        assert not list(base.rglob('*.arcdeck.orig'))
        assert not (act.parent/'workspace').exists()
    print('PASS: atomic preflight, dry run, installation, upgrade, idempotence and receiver integration')


if __name__=='__main__':main()
