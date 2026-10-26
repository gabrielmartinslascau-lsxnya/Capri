package com.dot.capri.llm

data class LlmRequest(
    val prompt: String,
    val systemPrompt: String,
    val history: List<Pair<String, String>> = emptyList(),
    val imageBytes: ByteArray? = null
)

data class LlmResponse(
    val text: String,
    val tokensIn: Int,
    val tokensOut: Int,
    val latencyMs: Long,
    val provider: String,
    val model: String
)

data class ProviderHealth(
    val isConfigured: Boolean,
    val isReachable: Boolean,
    val lastError: String? = null
)
