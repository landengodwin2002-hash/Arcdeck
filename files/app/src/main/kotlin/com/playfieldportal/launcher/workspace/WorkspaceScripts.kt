package com.playfieldportal.launcher.workspace

/** Shell code controls only process groups created by this launcher's managed command wrapper. */
internal object WorkspaceScripts {
    fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
    private fun valid(id: String) = require(id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid run identity" }
    private val readIdentity = """
        arcdeck_stat=§(cat "/proc/§arcdeck_pid/stat" 2>/dev/null) || exit 4
        arcdeck_rest=§{arcdeck_stat##*) }
        read -ra arcdeck_fields <<< "§arcdeck_rest"
    """.trimIndent().replace('§', '$')
    fun wrap(id: String, command: String): String {
        valid(id)
        return """
            set -m
            mkdir -p "§HOME/.arcdeck/runs" || exit 3
            mkfifo "§HOME/.arcdeck/runs/$id.start" || exit 3
            bash -lc __ARCDECK_COMMAND__ &
            arcdeck_pid=§!
            trap 'kill -KILL -- -"§arcdeck_pid" 2>/dev/null; rm -f "§HOME/.arcdeck/runs/$id" "§HOME/.arcdeck/runs/$id.start"' EXIT
            $readIdentity
            [[ §{arcdeck_fields[2]} == "§arcdeck_pid" ]] || { kill "§arcdeck_pid"; exit 5; }
            printf 'start\n' > "§HOME/.arcdeck/runs/$id.start"
            rm -f "§HOME/.arcdeck/runs/$id.start"
            printf '%s %s\n' "§arcdeck_pid" "§{arcdeck_fields[19]}" > "§HOME/.arcdeck/runs/$id"
            wait -f "§arcdeck_pid"
            arcdeck_exit=§?
            rm -f "§HOME/.arcdeck/runs/$id"
            trap - EXIT
            exit "§arcdeck_exit"
        """.trimIndent().replace('§', '$').replace("__ARCDECK_COMMAND__", quote("read -r arcdeck_ready < \"\$HOME/.arcdeck/runs/$id.start\"; exec bash -lc ${quote(command)}"))
    }
    fun control(id: String, action: String): String {
        valid(id); val signal = when (action) { "pause" -> "STOP"; "resume" -> "CONT"; "stop" -> "KILL"; else -> error("Unknown script action") }
        return """
            read -r arcdeck_pid arcdeck_start < "§HOME/.arcdeck/runs/$id" || exit 3
            [[ §arcdeck_pid =~ ^[0-9]+§ && §arcdeck_start =~ ^[0-9]+§ ]] || exit 4
            $readIdentity
            [[ §{arcdeck_fields[19]} == "§arcdeck_start" && §{arcdeck_fields[2]} == "§arcdeck_pid" ]] || exit 5
            kill -$signal -- -"§arcdeck_pid"
        """.trimIndent().replace('§', '$')
    }
}
