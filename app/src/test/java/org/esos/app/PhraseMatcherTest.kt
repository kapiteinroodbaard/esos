package org.esos.app

import org.junit.Assert.*
import org.junit.Test

class PhraseMatcherTest {
    @Test fun helpHelpTriggersOnlyAsCompleteExactText() {
        val matcher = PhraseMatcher()
        assertFalse(matcher.matches("help", 100))
        assertFalse(matcher.matches("please help help now", 100))
        assertTrue(matcher.matches("HELP HELP", 100))
        assertFalse(matcher.matches("help help", 1_000))
        assertTrue(matcher.matches("help help", 30_100))
    }

    @Test fun exactPhrasesOnlyAndDebounced() {
        val matcher = PhraseMatcher()
        assertFalse(matcher.matches("the tv said help me yesterday", 0))
        assertTrue(matcher.matches("Help mij!", 1))
        assertFalse(matcher.matches("help mij", 2))
        assertTrue(matcher.matches("I NEED HELP", 30_002))
        assertFalse(matcher.matches("[unk]", 60_000))
    }
}