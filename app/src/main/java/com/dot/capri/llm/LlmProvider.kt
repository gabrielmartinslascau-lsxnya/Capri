package com.dot.capri.llm

interface LlmProvider {
    val name: String
    val currentModel: String
    suspend fun complete(req: LlmRequest): Result<LlmResponse>
    fun health(): ProviderHealth
}
