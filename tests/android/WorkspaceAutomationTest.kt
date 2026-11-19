package com.playfieldportal.launcher.workspace

import android.app.Application
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkspaceAutomationTest {
    @Test fun savedEnabledStateCannotPretendADeadServiceIsConnected() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkspaceAutomation.prefs(context).edit().putBoolean("enabled", true).commit()
        WorkspaceAutomationService.running = false
        assertFalse(WorkspaceAutomation.connected(context))
        assertThrows(IllegalStateException::class.java) { WorkspaceAutomation.dispatch(context, org.json.JSONObject("{\"op\":\"clipboard\",\"text\":\"unused\"}")) }
        WorkspaceAutomation.prefs(context).edit().clear().commit()
    }
    private val token = "abc123"
    private fun request(body: String, extra: String = "", authorization: String = "Bearer $token"): java.io.InputStream =
        ("POST /action HTTP/1.1\r\nAuthorization: $authorization\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n$extra\r\n$body").byteInputStream()
    @Test fun validUtf8RequestsRemainBoundedAndAuthenticated() {
        val result = AutomationHttp.read(request("{\"op\":\"clipboard\",\"text\":\"hello 世界\"}"), token)
        assertEquals("hello 世界", result.json!!.getString("text")); assertEquals("/action", result.path)
        val status = AutomationHttp.read("GET /status HTTP/1.1\r\nAuthorization: Bearer $token\r\n\r\n".byteInputStream(), token)
        assertNull(status.json)
    }
    @Test fun browsersMissingAuthDuplicateHeadersAndChunkingAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { AutomationHttp.read(request("{}", authorization = "Bearer wrong"), token) }
        assertThrows(IllegalArgumentException::class.java) { AutomationHttp.read(request("{}", "Origin: https://evil.example\r\n"), token) }
        assertThrows(IllegalArgumentException::class.java) { AutomationHttp.read(request("{}", "Content-Length: 2\r\n"), token) }
        assertThrows(IllegalArgumentException::class.java) { AutomationHttp.read(request("{}", "Transfer-Encoding: chunked\r\n"), token) }
        assertThrows(IllegalArgumentException::class.java) { AutomationHttp.read(request(" ".repeat(32769)), token) }
    }
    @Test fun incompleteBodiesAndUnrecognizedRoutesAreNotExecuted() {
        assertThrows(IllegalArgumentException::class.java) { AutomationHttp.read("POST /action HTTP/1.1\r\nAuthorization: Bearer $token\r\nContent-Type: application/json\r\nContent-Length: 10\r\n\r\n{}".byteInputStream(), token) }
        assertThrows(IllegalArgumentException::class.java) { AutomationHttp.read("GET /action?token=abc123 HTTP/1.1\r\n\r\n".byteInputStream(), token) }
    }
    @Test fun scriptWindowActionsAreQueuedInOrderAndAcknowledgedOnce() {
        WorkspaceControls.acknowledge(WorkspaceControls.requests.value.map { it.serial }.toSet())
        WorkspaceControls.post("open", "CHATGPT"); WorkspaceControls.post("open", "DISCORD"); WorkspaceControls.post("tile", "side")
        val pending = WorkspaceControls.requests.value
        assertEquals(listOf("CHATGPT", "DISCORD", "side"), pending.map { it.target })
        WorkspaceControls.post("open", "CLIPBOARD")
        WorkspaceControls.acknowledge(pending.map { it.serial }.toSet())
        assertEquals("CLIPBOARD", WorkspaceControls.requests.value.single().target)
        WorkspaceControls.acknowledge(WorkspaceControls.requests.value.map { it.serial }.toSet())
        assertThrows(IllegalArgumentException::class.java) { WorkspaceControls.post("execute", "rm something") }
    }
    @Test fun webWindowsRejectLocalFilesInsecureUrlsAndCredentials() {
        assertTrue(WorkspaceWebPolicy.allowed("https://chatgpt.com/"))
        assertTrue(WorkspaceWebPolicy.allowed("https://discord.com/channels/@me"))
        listOf("http://example.com", "file:///data/private", "content://private", "javascript:alert(1)", "https://user:pass@example.com", "https://example.com:8080").forEach { assertFalse(it, WorkspaceWebPolicy.allowed(it)) }
    }
    @Test fun webHistoryStaysPerWindowAndCanBeReleasedWithoutLosingOtherSessions() {
        val history = WorkspaceWebHistory(); val one = Bundle().apply { putString("url", "one") }; val two = Bundle().apply { putString("url", "two") }
        history.save("CHATGPT", one); history.save("DISCORD", two); history.invalidate("CHATGPT")
        assertEquals(1, history.version("CHATGPT").value); assertEquals(0, history.version("DISCORD").value)
        assertEquals("one", history.take("CHATGPT")!!.getString("url")); assertNull(history.take("CHATGPT"))
        assertEquals("two", history.take("DISCORD")!!.getString("url"))
    }
    @Test fun directLibraryViewsStayDistinctAndLandscapeTilesRespectEveryControlBarPosition() {
        listOf("SEARCH", "COLLECTIONS", "LIBRARY").forEach { assertEquals("LIBRARY", WorkspaceWindowGeometry.canonical(it)) }
        assertEquals("APPS", WorkspaceWindowGeometry.canonical("APPS"))
        assertEquals("GAMES", WorkspaceWindowGeometry.canonical("GAMES"))
        for (position in listOf("Top", "Bottom", "Left", "Right")) {
            val inset = WorkspaceDesktopGeometry.insets(position, true, 840f)
            val tiles = (0..2).map { WorkspaceDesktopGeometry.tile(it, 3, 840f, 400f, "side", inset) }
            tiles.forEach { assertTrue(it.x >= inset.left && it.y >= inset.top && it.x + it.width <= 840 - inset.right && it.y + it.height <= 400 - inset.bottom) }
            for (i in tiles.indices) for (j in i + 1 until tiles.size) {
                val a = tiles[i]; val b = tiles[j]
                assertTrue(a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y)
            }
        }
    }
}
