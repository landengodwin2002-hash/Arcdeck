package com.playfieldportal.launcher.workspace

import android.app.Application
import android.os.Binder
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkspaceNativeTest {
    @Test fun nativeLaunchTargetsCannotEscapeTheirPackageOrUseShellSyntax() {
        assertEquals("com.discord.Main", NativeProtocol.component("com.discord/.Main", "com.discord").className)
        assertThrows(IllegalArgumentException::class.java) { NativeProtocol.component("com.other/.Main", "com.discord") }
        for (pkg in listOf("com.discord; id", "com.discord\n", "a/b", "", "$(id)"))
            assertThrows(IllegalArgumentException::class.java) { NativeProtocol.packageName(pkg) }
        assertThrows(IllegalArgumentException::class.java) { NativeProtocol.user(-1) }
        assertThrows(IllegalArgumentException::class.java) { NativeProtocol.user(10000) }
    }
    @Test fun nativeRenderingHasARealPixelBudget() {
        NativeProtocol.dimensions(1600, 900, 240)
        for (triple in listOf(Triple(0, 720, 240), Triple(2560, 2560, 240), Triple(1280, 720, 900)))
            assertThrows(IllegalArgumentException::class.java) { NativeProtocol.dimensions(triple.first, triple.second, triple.third) }
    }
    @Test fun binderErrorsAreReportedInsteadOfPretendingAnAppOpened() {
        val service = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(NativeProtocol.CALL, code); data.enforceInterface(NativeProtocol.DESCRIPTOR)
                assertEquals("create", JSONObject(data.readString()!!).getString("op"))
                reply!!.writeNoException(); reply.writeString(JSONObject().put("error", "App rejected secondary display").toString()); return true
            }
        }
        val error = assertThrows(IllegalStateException::class.java) { NativeProtocol.call(service, JSONObject().put("op", "create")) }
        assertTrue(error.message!!.contains("secondary display"))
    }
    @Test fun nativeCapabilityAndSerializedGameIntentsAreNotRestoredFromBackup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("arcdeck_unified_v1.db")
        val prefs = context.getSharedPreferences("arcdeck_workspace", 0)
        prefs.edit().clear().putBoolean("native_ready", true).putString("panels", """[{"id":1,"tool":"NATIVE","app":"com.discord","user":10,"title":"Work Discord"}]""").commit()
        UnifiedCatalog(context).use { catalog ->
            val backup = WorkspaceBackup.export(context, catalog)
            assertFalse(backup.contains("native_ready")); assertFalse(backup.contains("window_token"))
            WorkspaceBackup.restore(context, catalog, backup)
            WorkspaceBackup.validatePanels(prefs.getString("panels", "[]")!!)
        }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceBackup.validatePanels("""[{"id":1,"tool":"NATIVE","app":"bad;package"}]""") }
    }
}
