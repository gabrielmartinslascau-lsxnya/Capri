package com.dot.capri

import com.dot.capri.intents.Cmd
import com.dot.capri.personality.Personality
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalityTest {

    @Test
    fun testPlayResponse() {
        val response = Personality.respond(Cmd.Play("lofi", "spotify"), true)
        assertTrue(response.isNotEmpty())
        assertFalse(response.contains("{q}"))
        assertFalse(response.contains("{app}"))
    }

    @Test
    fun testCallResponse() {
        val response = Personality.respond(Cmd.Call("mom"), true)
        assertTrue(response.isNotEmpty())
        assertFalse(response.contains("{c}"))
        assertTrue(response.contains("mom"))
    }

    @Test
    fun testOpenResponse() {
        val response = Personality.respond(Cmd.Open("tiktok"), true)
        assertTrue(response.isNotEmpty())
        assertFalse(response.contains("{app}"))
    }

    @Test
    fun testSearchResponse() {
        val response = Personality.respond(Cmd.Search("cute capybaras"), true)
        assertTrue(response.isNotEmpty())
        assertFalse(response.contains("{q}"))
        assertTrue(response.contains("cute capybaras"))
    }

    @Test
    fun testFailureResponse() {
        val response = Personality.respond(Cmd.Open("nonexistent_app"), false)
        assertTrue(response.isNotEmpty())
    }

    @Test
    fun testThinkingLabel() {
        val label = Personality.thinking()
        assertTrue(label.isNotEmpty())
    }

    @Test
    fun testNoBrainInstalled() {
        val response = Personality.noBrainInstalled()
        assertTrue(response.isNotEmpty())
        assertTrue(response.contains("choom") || response.contains("netrunner") || response.contains("brain"))
    }

    @Test
    fun testBudgetWarning() {
        val response = Personality.budgetWarning()
        assertTrue(response.isNotEmpty())
        assertTrue(response.contains("choom") || response.contains("netrunner") || response.contains("brain"))
    }

    @Test
    fun testBudgetExhausted() {
        val response = Personality.budgetExhausted()
        assertTrue(response.isNotEmpty())
        assertTrue(response.contains("choom") || response.contains("netrunner") || response.contains("brain"))
    }
}
