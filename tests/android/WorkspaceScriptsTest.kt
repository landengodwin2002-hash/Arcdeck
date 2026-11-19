package com.playfieldportal.launcher.workspace

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Real Bash process groups, not mocks of generated shell text. */
class WorkspaceScriptsTest {
    private fun substitute(script: String, root: Path): String = script.replace("\$HOME/.arcdeck/runs", root.toString())
    private fun execute(script: String): Process = ProcessBuilder("/bin/bash", "-c", script).redirectErrorStream(true).start()
    private fun until(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(15)
        assertTrue(message, condition())
    }
    private fun state(pid: String) = Files.readString(Path.of("/proc/$pid/stat")).substringAfterLast(") ").substringBefore(' ')
    @Test fun shortCommandsAndShellMetacharactersArePreservedExactly() {
        val root = Files.createTempDirectory("arcdeck-script-test-"); val id = UUID.randomUUID().toString()
        val value = "literal \$HOME `echo unexpected` ; ' §"
        val p = execute(substitute(WorkspaceScripts.wrap(id, "printf '%s\\n' ${WorkspaceScripts.quote(value)}"), root))
        assertTrue(p.waitFor(5, TimeUnit.SECONDS)); val output = p.inputStream.bufferedReader().readText()
        assertEquals(output, 0, p.exitValue()); assertTrue(output, output.contains(value))
        assertFalse(Files.exists(root.resolve(id))); assertFalse(Files.exists(root.resolve("$id.start")))
    }
    @Test fun pauseResumeAndStopAffectTheManagedGroupAndCleanUpItsIdentity() {
        val root = Files.createTempDirectory("arcdeck-script-test-"); val id = UUID.randomUUID().toString()
        val process = execute(substitute(WorkspaceScripts.wrap(id, "sleep 25"), root))
        val file = root.resolve(id)
        try {
            until("Managed identity was not created") { Files.exists(file) && Files.size(file) > 0 }
            val pid = Files.readString(file).trim().substringBefore(' ')
            fun control(action: String): Int { val p = execute(substitute(WorkspaceScripts.control(id, action), root)); assertTrue(p.waitFor(4, TimeUnit.SECONDS)); return p.exitValue() }
            assertEquals(0, control("pause")); until("Process group was not paused") { state(pid) == "T" }
            assertTrue(process.isAlive)
            assertEquals(0, control("resume")); until("Process group was not resumed") { state(pid) != "T" }
            assertEquals(0, control("stop")); assertTrue(process.waitFor(4, TimeUnit.SECONDS))
            assertFalse(Files.exists(file))
        } finally { if (process.isAlive) { execute(substitute(WorkspaceScripts.control(id, "stop"), root)).waitFor(3, TimeUnit.SECONDS); process.destroyForcibly() } }
    }
    @Test fun aStalePidStampCannotControlAReusedProcess() {
        val root = Files.createTempDirectory("arcdeck-script-test-"); val id = UUID.randomUUID().toString()
        val process = execute(substitute(WorkspaceScripts.wrap(id, "sleep 25"), root)); val file = root.resolve(id)
        try {
            until("Managed identity was not created") { Files.exists(file) && Files.size(file) > 0 }
            val original = Files.readString(file); Files.writeString(file, original.trim().substringBefore(' ') + " 0\n")
            val control = execute(substitute(WorkspaceScripts.control(id, "stop"), root)); assertTrue(control.waitFor(3, TimeUnit.SECONDS))
            assertNotEquals(0, control.exitValue()); assertTrue(process.isAlive)
            Files.writeString(file, original)
        } finally { execute(substitute(WorkspaceScripts.control(id, "stop"), root)).waitFor(3, TimeUnit.SECONDS); if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly() }
    }
    @Test fun invalidRunIdsAndUnknownControlsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { WorkspaceScripts.control("../../other", "stop") }
        assertThrows(IllegalStateException::class.java) { WorkspaceScripts.control(UUID.randomUUID().toString(), "arbitrary") }
    }
}
