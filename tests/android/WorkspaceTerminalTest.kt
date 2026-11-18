package com.playfieldportal.launcher.workspace

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkspaceTerminalTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("arcdeck_terminal", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("arcdeck_terminal", Context.MODE_PRIVATE).edit().putString("history",
            """[{"id":"known-nonce","command":"echo hello","status":"Running","output":"","exit":null}]""").commit()
    }
    private fun result(id: String, output: String) = Intent().setData(Uri.parse("arcdeck://termux/$id"))
        .putExtra("result", Bundle().apply { putString("stdout", output); putInt("err", -1); putInt("exitCode", 0) })
    @Test fun resultsAreAssociatedWithTheExactPendingRun() {
        WorkspaceTermuxReceiver().onReceive(context, result("known-nonce", "hello"))
        val run = TermuxBridge.history(context).single()
        assertEquals("Completed", run.status); assertEquals("hello", run.output); assertEquals(0, run.exitCode)
    }
    @Test fun unsolicitedAndReplayedCallbacksAreIgnored() {
        WorkspaceTermuxReceiver().onReceive(context, result("unknown", "not accepted"))
        assertEquals("Running", TermuxBridge.history(context).single().status)
        WorkspaceTermuxReceiver().onReceive(context, result("known-nonce", "first"))
        WorkspaceTermuxReceiver().onReceive(context, result("known-nonce", "replacement"))
        assertEquals("first", TermuxBridge.history(context).single().output)
    }
}
