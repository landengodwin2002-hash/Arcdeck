package com.playfieldportal.launcher.workspace

import java.text.Normalizer
import java.util.Locale

/**
 * Shared, Android-independent parser used by the universal index and smart collections.
 * White space means AND; quoted text is a phrase; filters have exact field semantics.
 * No user input is spliced into SQL. FTS receives only sanitized prefix terms as a bound parameter.
 */
data class UnifiedQuery(
    val words: List<String>,
    val kinds: Set<String>,
    val tags: Set<String>,
    val sources: Set<String>,
    val collection: String?,
    val showMissing: Boolean?,
    val sort: SortOrder,
    val invalid: List<String> = emptyList(),
) {
    enum class SortOrder { RELEVANCE, NAME, RECENT, MANUAL }

    companion object {
        // Quoted phrases are preserved as single terms. Malformed quotes are accepted as text.
        private val lexer = Regex("\\\"([^\\\"]*)\\\"|\\S+")
        private val validKinds = setOf("GAME", "APP", "SHORTCUT", "FILE", "FOLDER", "COMMAND", "LINK", "TOOL")

        fun parse(raw: String): UnifiedQuery {
            val words = mutableListOf<String>()
            val kinds = linkedSetOf<String>()
            val tags = linkedSetOf<String>()
            val sources = linkedSetOf<String>()
            val invalid = mutableListOf<String>()
            var collection: String? = null
            var missing: Boolean? = null
            var order = SortOrder.RELEVANCE
            for (match in lexer.findAll(raw.take(512))) {
                val token = (match.groups[1]?.value ?: match.value).trim()
                if (token.isBlank()) continue
                val colon = token.indexOf(':')
                if (colon <= 0) { words.add(token); continue }
                val key = token.substring(0, colon).lowercase(Locale.ROOT)
                val value = token.substring(colon + 1).trim()
                if (value.isEmpty()) { invalid.add(token); continue }
                when (key) {
                    "type", "kind" -> {
                        val k = value.uppercase(Locale.ROOT)
                        if (k in validKinds) kinds.add(k) else invalid.add(token)
                    }
                    "tag" -> tags.add(normalizeTag(value))
                    "source" -> sources.add(value.lowercase(Locale.ROOT))
                    "in" -> collection = value
                    "is" -> when (value.lowercase(Locale.ROOT)) {
                        "missing" -> missing = true
                        "available" -> missing = false
                        else -> invalid.add(token)
                    }
                    "sort" -> order = when (value.lowercase(Locale.ROOT)) {
                        "name" -> SortOrder.NAME
                        "recent" -> SortOrder.RECENT
                        "manual" -> SortOrder.MANUAL
                        "relevance" -> SortOrder.RELEVANCE
                        else -> { invalid.add(token); order }
                    }
                    else -> words.add(token) // web URLs like https: should still be searchable.
                }
            }
            return UnifiedQuery(words, kinds, tags, sources, collection, missing, order, invalid)
        }

        fun normalizeTag(raw: String): String = raw.trim().lowercase(Locale.ROOT).take(48)

        /** Produces safe FTS4 prefix terms; ignores punctuation/query operators. */
        fun toFts(terms: List<String>): String {
            val safe = terms.flatMap { word ->
                Normalizer.normalize(word, Normalizer.Form.NFKC)
                    .lowercase(Locale.ROOT)
                    .split(Regex("[^\\p{L}\\p{N}]+"))
                    .filter { it.isNotEmpty() }
            }.take(12)
            return safe.joinToString(" AND ") { "${it.take(48)}*" }
        }
    }
}
