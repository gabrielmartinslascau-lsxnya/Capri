# Add Groq API Provider for Fast Free Vision

Add Groq API as a new cloud LLM provider option in Capri. Groq offers a high-speed OpenAI-compatible API with generous free tiers and multimodal vision support (`llama-3.2-11b-vision-preview` / `llama-3.2-90b-vision-preview`).

## User Review Required

> [!IMPORTANT]
> Groq API provides ultra-fast inference and multimodal vision capabilities for free, expanding Capri's cloud provider options alongside Gemini, OpenRouter, and Anthropic.

## Proposed Changes

### LLM Provider Layer

#### [NEW] [GroqProvider.kt](file:///C:/Users/sorin/AndroidStudioProjects/Capriagain/app/src/main/java/com/dot/capri/llm/GroqProvider.kt)
- Implement `GroqProvider` implementing `LlmProvider` using Groq's endpoint (`https://api.groq.com/openai/v1/chat/completions`) supporting text and base64 images for multimodal vision tasks.

#### [MODIFY] [LlmRouter.kt](file:///C:/Users/sorin/AndroidStudioProjects/Capriagain/app/src/main/java/com/dot/capri/llm/LlmRouter.kt)
- Add API key preference constant for Groq (`KEY_GROQ_API_KEY`).
- Update `hasAnyApiKey`, `getActiveProviderName`, and `getActiveProviderInstance` to support Groq.

## Verification Plan

### Automated Tests
- Run `verify.py`.
- Run Gradle build `app:assembleDebug`.

### Manual Verification
- Test entering a Groq API key in settings and verifying successful cloud generation responses.
