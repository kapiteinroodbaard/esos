package org.esos.app

import org.junit.Assert.*
import org.junit.Test

class PhraseMatcherTest {
    @Test fun onlyDutchHelpPhrasesTriggerOnCompleteText() {
        val matcher = PhraseMatcher()
        assertFalse(matcher.matches("help", 100))
        assertFalse(matcher.matches("please help help now", 100))
        assertFalse(matcher.matches("HELP HELP", 100))
        assertTrue(matcher.matches("Help mij!", 1))
        assertFalse(matcher.matches("help mij", 2))
        assertTrue(matcher.matches("ik heb hulp nodig", 30_100))
    }

    @Test fun exactPhrasesOnlyAndDebounced() {
        val matcher = PhraseMatcher()
        assertFalse(matcher.matches("the tv said help me yesterday", 0))
        assertTrue(matcher.matches("ik heb hulp nodig", 1))
        assertFalse(matcher.matches("ik heb hulp nodig", 2))
        assertFalse(matcher.matches("I NEED HELP", 30_002))
        assertFalse(matcher.matches("[unk]", 60_000))
    }
}