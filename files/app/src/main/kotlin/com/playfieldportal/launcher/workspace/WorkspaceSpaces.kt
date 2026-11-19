package com.playfieldportal.launcher.workspace

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class AppSpace(val id: String, val name: String, val app: String, val collection: String, val notes: String = "", val profile: String = "")

/** Spaces refer to the shared catalog, so tagging a file in one space updates it everywhere. */
internal class WorkspaceSpaces private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("arcdeck_workspace", Context.MODE_PRIVATE)
    private val updates = MutableStateFlow(0L)
    private val drafts = mutableMapOf<String, String>()
    val revision = updates.asStateFlow()
    @Synchronized fun all(): List<AppSpace> = decode(prefs.getString("spaces", "[]") ?: "[]")
    fun active(): AppSpace? = all().let { spaces -> spaces.find { it.id == prefs.getString("active_space", "") } ?: spaces.firstOrNull() }
    @Synchronized fun select(id: String) {
        require(all().any { it.id == id }) { "Space no longer exists" }
        prefs.edit().putString("active_space", id).apply(); updates.value++
    }
    @Synchronized fun create(name: String, app: String, catalog: UnifiedCatalog, item: String? = null, profile: String = ""): AppSpace {
        val spaces = all(); require(spaces.size < 200) { "Up to 200 spaces are supported" }
        require(name.trim().length in 1..60) { "Space name needs 1–60 characters" }
        require(app.isEmpty() || app.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))) { "Invalid app package" }
        val id = UUID.randomUUID().toString()
        require(profile.length <= 60)
        val space = AppSpace(id, name.trim(), app, "${name.trim().take(50)} · ${id.take(8)}", profile = profile)
        val collection = catalog.createCollection(space.collection)
        item?.let { catalog.addToCollection(collection, it) }
        save(spaces + space); select(id)
        return space
    }
    @Synchronized fun edit(id: String, name: String, app: String, profile: String = "") {
        require(name.trim().length in 1..60)
        require(app.isEmpty() || app.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")))
        require(profile.length <= 60)
        save(all().map { if (it.id == id) it.copy(name = name.trim(), app = app, profile = profile) else it })
    }
    @Synchronized fun notes(id: String, value: String) {
        require(value.length <= 50_000) { "Space notes are limited to 50,000 characters" }
        save(all().map { if (it.id == id) it.copy(notes = value) else it })
        drafts.remove(id)
    }
    @Synchronized fun draft(id: String, value: String) { require(value.length <= 50_000); drafts[id] = value }
    @Synchronized fun noteText(space: AppSpace): String = drafts[space.id] ?: all().find { it.id == space.id }?.notes ?: space.notes
    @Synchronized fun flush() { if (drafts.isNotEmpty()) { save(all().map { it.copy(notes = drafts[it.id] ?: it.notes) }); drafts.clear() } }
    @Synchronized fun remove(id: String) {
        val remaining = all().filterNot { it.id == id }; save(remaining)
        if (prefs.getString("active_space", "") == id) prefs.edit().putString("active_space", remaining.firstOrNull()?.id ?: "").apply()
    }
    @Synchronized fun reload() { drafts.clear(); updates.value++ }
    fun collectionId(space: AppSpace, catalog: UnifiedCatalog): Long =
        catalog.collections().find { it.name.equals(space.collection, true) }?.id ?: catalog.createCollection(space.collection)
    fun items(space: AppSpace, catalog: UnifiedCatalog, query: String = ""): List<CatalogEntry> {
        val collection = collectionId(space, catalog)
        return catalog.search(query, 1000, scopeCollection = collection, scopeSources = catalog.collectionSources(collection))
    }
    fun attach(space: AppSpace, key: String, catalog: UnifiedCatalog) = catalog.addToCollection(collectionId(space, catalog), key)
    private fun save(spaces: List<AppSpace>) {
        val array = JSONArray()
        spaces.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("app", it.app)
            .put("collection", it.collection).put("notes", it.notes).put("profile", it.profile)) }
        check(prefs.edit().putString("spaces", array.toString()).commit()) { "Could not save spaces" }
        updates.value++
    }
    companion object {
        @Volatile private var instance: WorkspaceSpaces? = null
        fun shared(context: Context): WorkspaceSpaces = instance?.takeIf { it.appContext === context.applicationContext } ?: synchronized(this) {
            instance?.takeIf { it.appContext === context.applicationContext } ?: WorkspaceSpaces(context).also { instance = it }
        }
        fun decode(raw: String): List<AppSpace> {
            require(raw.length <= 12_000_000) { "Spaces are too large" }
            val array = JSONArray(raw); require(array.length() <= 200) { "Too many spaces" }
            val ids = mutableSetOf<String>()
            return (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val id = o.getString("id"); require(id.matches(Regex("[a-zA-Z0-9_-]{1,64}")) && ids.add(id)) { "Invalid space identity" }
                val name = o.getString("name"); require(name.isNotBlank() && name.length <= 60)
                val app = o.optString("app"); require(app.isEmpty() || app.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")))
                val collection = o.getString("collection"); require(collection.isNotBlank() && collection.length <= 80)
                val notes = o.optString("notes"); require(notes.length <= 50_000)
                val profile = o.optString("profile"); require(profile.length <= 60)
                AppSpace(id, name, app, collection, notes, profile)
            }
        }
    }
}

internal object WorkspaceApps {
    fun launch(context: Context, space: AppSpace, adjacent: Boolean = false) {
        require(space.app.isNotBlank()) { "Choose a connected app first" }
        val intent = context.packageManager.getLaunchIntentForPackage(space.app) ?: error("Connected app is not installed")
        WorkspaceSpaces.shared(context).select(space.id)
        WorkspaceProfiles.apply(context, space.profile)
        if (adjacent) context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK))
        else { WorkspaceNativeRequests.open(context, space.app); WorkspaceRequests.open(context, space.id, "NATIVE") }
    }
    fun copy(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ArcDeck space", text))
    }
    fun shareText(context: Context, text: String, app: String = "") = share(context,
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), app)
    fun shareFiles(context: Context, documents: List<WorkspaceDocument>, app: String = "") {
        require(documents.isNotEmpty() && documents.none { it.directory }) { "Select files to share" }
        val intent = if (documents.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, documents[0].uri)
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(documents.map { it.uri }))
        intent.type = documents.map { it.mime }.distinct().singleOrNull() ?: "*/*"
        intent.clipData = ClipData.newUri(context.contentResolver, documents[0].name, documents[0].uri).also { clip ->
            documents.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        share(context, intent, app)
    }
    private fun share(context: Context, intent: Intent, app: String) {
        // A share opens the app's composer/Android chooser. It never submits a message.
        if (app.isNotBlank() && context.packageManager.queryIntentActivities(Intent(intent).setPackage(app), 0).isNotEmpty()) {
            intent.setPackage(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(intent)
        } else context.startActivity(Intent.createChooser(intent, "Share from ArcDeck").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

internal data class WorkspaceRequest(val serial: Long = 0, val tool: String = "SPACES")
internal object WorkspaceRequests {
    private val updates = MutableStateFlow(WorkspaceRequest())
    val requests = updates.asStateFlow()
    fun publish(tool: String) { updates.value = WorkspaceRequest(updates.value.serial + 1, tool.take(30)) }
    fun open(context: Context, spaceId: String? = null, tool: String = "SPACES") {
        spaceId?.let { WorkspaceSpaces.shared(context).select(it) }
        context.startActivity(Intent(context, com.playfieldportal.launcher.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("arcdeck_open_workspace", true).putExtra("arcdeck_tool", tool))
    }
}
