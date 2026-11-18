package com.playfieldportal.launcher.workspace

fun main() {
    fun assertTrue(value: Boolean, message: String) { if (!value) throw AssertionError(message) }
    val q = UnifiedQuery.parse("type:game tag:RPG sort:recent Final Fantasy")
    assertTrue(q.kinds == setOf("GAME"), "type filter")
    assertTrue(q.tags == setOf("rpg"), "normalized tag")
    assertTrue(q.sort == UnifiedQuery.SortOrder.RECENT, "sort")
    assertTrue(q.words == listOf("Final", "Fantasy"), "terms")
    val collection = UnifiedQuery.parse("in:12 sort:manual is:missing")
    assertTrue(collection.collection == "12", "collection")
    assertTrue(collection.showMissing == true, "missing flag")
    assertTrue(collection.sort == UnifiedQuery.SortOrder.MANUAL, "manual ordering")
    val injection = UnifiedQuery.toFts(listOf("foo' OR 1=1 --", "BOB"))
    assertTrue(injection == "foo* AND or* AND 1* AND 1* AND bob*", "sanitized FTS: $injection")
    val names = UnifiedQuery.parse("\"Final Fantasy VII\" tag:favorite")
    assertTrue(names.words == listOf("Final Fantasy VII"), "phrase")
    assertTrue(UnifiedQuery.toFts(names.words) == "final* AND fantasy* AND vii*", "prefix tokenization")
    val unknown = UnifiedQuery.parse("type:rock sort:whenever")
    assertTrue(unknown.invalid.size == 2, "invalid value diagnostics")
    val empty = UnifiedQuery.parse("")
    assertTrue(empty.words.isEmpty() && empty.kinds.isEmpty(), "empty query")
    assertTrue(UnifiedQuery.toFts(listOf("游戏 模拟")) == "游戏* AND 模拟*", "unicode terms")
    println("PASS: 9 parser / query / injection safety checks")
}
