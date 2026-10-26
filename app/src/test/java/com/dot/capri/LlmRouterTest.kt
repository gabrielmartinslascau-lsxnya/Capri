package com.dot.capri

import com.dot.capri.llm.LlmRequest
import com.dot.capri.llm.LlmResponse
import com.dot.capri.llm.ProviderHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmRouterTest {

    @Test
    fun testLlmRequestDataClass() {
        val req = LlmRequest(prompt = "hello", systemPrompt = "sys")
        assertEquals("hello", req.prompt)
        assertEquals("sys", req.systemPrompt)
        assertTrue(req.history.isEmpty())
    }

    @Test
    fun testLlmResponseDataClass() {
        val resp = LlmResponse(
            text = "hi",
            tokensIn = 10,
            tokensOut = 5,
            latencyMs = 120L,
            provider = "Gemini",
            model = "gemini-3.5-flash-lite"
        )
        assertEquals("hi", resp.text)
        assertEquals(10, resp.tokensIn)
        assertEquals(5, resp.tokensOut)
        assertEquals("Gemini", resp.provider)
    }

    @Test
    fun testProviderHealthDataClass() {
        val health = ProviderHealth(isConfigured = true, isReachable = true)
        assertTrue(health.isConfigured)
        assertTrue(health.isReachable)
    }
}
