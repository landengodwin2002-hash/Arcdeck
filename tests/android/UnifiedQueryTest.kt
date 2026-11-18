package com.playfieldportal.launcher.workspace

import org.junit.Assert.*
import org.junit.Test

class UnifiedQueryTest {
    @Test fun portraitIsTheDefaultAndExplicitLandscapeIsPreserved() {
        assertEquals(com.playfieldportal.core.domain.model.ScreenOrientationMode.FOLLOW_DEVICE,
            com.playfieldportal.core.domain.model.ScreenOrientationMode.fromName(null))
        assertEquals(com.playfieldportal.core.domain.model.ScreenOrientationMode.LANDSCAPE,
            com.playfieldportal.core.domain.model.ScreenOrientationMode.fromName("LANDSCAPE"))
    }
    @Test fun quotedFilterValuesAndUnicodeArePreserved() {
        val q = UnifiedQuery.parse("in:\"My Games\" tag:\"Action RPG\" type:game 最终 幻想")
        assertEquals("My Games", q.collection)
        assertEquals(setOf("action rpg"), q.tags)
        assertEquals(setOf("GAME"), q.kinds)
        assertEquals("最终* AND 幻想*", UnifiedQuery.toFts(q.words))
    }
    @Test fun invalidFiltersDoNotBecomeBroadSearches() {
        assertEquals(2, UnifiedQuery.parse("type:unknown sort:unknown").invalid.size)
        assertTrue(UnifiedQuery.parse("tag:").invalid.isNotEmpty())
    }
    @Test fun queryOperatorsCannotEnterFtsSyntax() {
        assertEquals("foo* AND or* AND 1* AND 1* AND bob*", UnifiedQuery.toFts(listOf("foo' OR 1=1 --", "BOB")))
    }
    @Test fun savedAndCurrentFiltersUseAndSemantics() {
        val merged = UnifiedQuery.parse("type:game tag:rpg").intersect(UnifiedQuery.parse("type:game is:favorite tag:retro"))
        assertEquals(setOf("rpg", "retro"), merged.tags)
        assertEquals(true, merged.favorite)
        assertTrue(merged.invalid.isEmpty())
        assertTrue(UnifiedQuery.parse("type:game").intersect(UnifiedQuery.parse("type:app")).invalid.isNotEmpty())
    }
    @Test fun punctuationAndOversizedTextRemainBounded() {
        assertEquals("", UnifiedQuery.toFts(listOf("--- ' \"")))
        assertTrue(UnifiedQuery.parse("a".repeat(10000)).words.sumOf { it.length } <= 512)
        assertEquals("action rpg", UnifiedQuery.normalizeTag("  Action   RPG  "))
    }
}
