package com.playfieldportal.launcher.workspace

import java.text.Normalizer
import java.util.Locale

/** Bounded query grammar shared by search, filter buttons and saved collections. */
data class UnifiedQuery(
    val words: List<String>,
    val kinds: Set<String>,
    val tags: Set<String>,
    val sources: Set<String>,
    val collection: String?,
    val showMissing: Boolean?,
    val sort: SortOrder,
    val invalid: List<String> = emptyList(),
    val favorite: Boolean? = null,
    val pinned: Boolean? = null,
) {
    enum class SortOrder { RELEVANCE, NAME, RECENT, MANUAL }

    companion object {
        internal val validKinds = setOf("GAME", "APP", "SHORTCUT", "FILE", "FOLDER", "COMMAND", "LINK", "TOOL")

        private fun tokens(raw: String): List<String> {
            val out = mutableListOf<String>()
            val token = StringBuilder()
            var quoted = false
            var escaped = false
            for (ch in raw.take(512)) {
                if (escaped) { token.append(ch); escaped = false }
                else if (ch == '\\' && quoted) escaped = true
                else if (ch == '"') quoted = !quoted
                else if (ch.isWhitespace() && !quoted) {
                    if (token.isNotEmpty()) { out.add(token.toString()); token.clear() }
                } else token.append(ch)
            }
            if (escaped) token.append('\\')
            if (token.isNotEmpty()) out.add(token.toString())
            return out
        }

        fun parse(raw: String): UnifiedQuery {
            val words = mutableListOf<String>()
            val kinds = linkedSetOf<String>()
            val tags = linkedSetOf<String>()
            val sources = linkedSetOf<String>()
            val invalid = mutableListOf<String>()
            var collection: String? = null
            var missing: Boolean? = null
            var favorite: Boolean? = null
            var pinned: Boolean? = null
            var order = SortOrder.RELEVANCE
            for (token in tokens(raw)) {
                val colon = token.indexOf(':')
                if (colon <= 0) { words.add(token); continue }
                val key = token.substring(0, colon).lowercase(Locale.ROOT)
                val value = token.substring(colon + 1).trim()
                if (value.isEmpty()) { invalid.add(token); continue }
                when (key) {
                    "type", "kind" -> value.uppercase(Locale.ROOT).let {
                        if (it in validKinds) kinds.add(it) else invalid.add(token)
                    }
                    "tag" -> tags.add(normalizeTag(value))
                    "source" -> sources.add(value.lowercase(Locale.ROOT))
                    "in" -> collection = value
                    "is" -> when (value.lowercase(Locale.ROOT)) {
                        "missing" -> missing = true
                        "available" -> missing = false
                        "favorite" -> favorite = true
                        "pinned" -> pinned = true
                        else -> invalid.add(token)
                    }
                    "sort" -> order = when (value.lowercase(Locale.ROOT)) {
                        "name" -> SortOrder.NAME
                        "recent" -> SortOrder.RECENT
                        "manual" -> SortOrder.MANUAL
                        "relevance" -> SortOrder.RELEVANCE
                        else -> { invalid.add(token); order }
                    }
                    else -> words.add(token)
                }
            }
            return UnifiedQuery(words, kinds, tags, sources, collection, missing, order, invalid, favorite, pinned)
        }

        fun normalizeTag(raw: String): String = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").take(48)

        fun toFts(terms: List<String>): String = terms.flatMap { word ->
            Normalizer.normalize(word, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.isNotEmpty() }
        }.take(12).joinToString(" AND ") { "${it.take(48)}*" }
    }

    /** AND a saved query with the user's other filters; contradictory filters stay empty. */
    internal fun intersect(other: UnifiedQuery): UnifiedQuery {
        val errors = (invalid + other.invalid).toMutableList()
        fun overlap(a: Set<String>, b: Set<String>): Set<String> {
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            return a.intersect(b).also { if (it.isEmpty()) errors.add("Conflicting filters") }
        }
        if (showMissing != null && other.showMissing != null && showMissing != other.showMissing)
            errors.add("Conflicting availability filters")
        return copy(words = words + other.words, kinds = overlap(kinds, other.kinds),
            tags = tags + other.tags, sources = overlap(sources, other.sources),
            collection = other.collection, showMissing = showMissing ?: other.showMissing,
            favorite = favorite ?: other.favorite, pinned = pinned ?: other.pinned,
            sort = if (sort == SortOrder.RELEVANCE) other.sort else sort, invalid = errors)
    }
}
