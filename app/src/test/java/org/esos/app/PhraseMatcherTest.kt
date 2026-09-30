package org.esos.app

import org.junit.Assert.*
import org.junit.Test

class PhraseMatcherTest {
    @Test fun defaultWakePhrasesTriggerOnCompleteText() {
        val matcher = PhraseMatcher()
        assertFalse(matcher.matches("help", 100))
        assertFalse(matcher.matches("please help help now", 100))
        assertTrue(matcher.matches("Help mij!", 1))
        assertFalse(matcher.matches("help mij", 2))
        assertTrue(matcher.matches("ik heb hulp nodig", 30_100))
        assertTrue(matcher.matches("HELP HELP", 60_100))
        assertTrue(matcher.matches("Help me", 90_100))
    }

    @Test fun customWakePhrasesAreNormalizedAndMatchedExactly() {
        val matcher = PhraseMatcher(phrases = listOf("Neem contact op"))
        assertTrue(matcher.matches("NEEM CONTACT OP!", 1))
        assertFalse(matcher.matches("neem contact", 30_002))
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