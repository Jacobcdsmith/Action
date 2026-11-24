package com.example.ai

import com.squareup.moshi.JsonClass

enum class AiProviderType(
    val displayName: String,
    val shortLabel: String,
    val requiresKey: Boolean,
    val defaultModel: String,
    val defaultBaseUrl: String,
    val suggestedModels: List<String>
) {
    FIREBASE_AI(
        displayName = "Firebase AI Logic (Built-in)",
        shortLabel = "Firebase",
        requiresKey = false,
        defaultModel = "gemini-3.8-flash",
        defaultBaseUrl = "",
        suggestedModels = listOf("gemini-3.8-flash", "gemini-3.5-flash-lite")
    ),
    GEMINI_BYOK(
        displayName = "Google Gemini (BYOK)",
        shortLabel = "Gemini Key",
        requiresKey = true,
        defaultModel = "gemini-2.5-flash",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
        suggestedModels = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-3.8-flash")
    ),
    OPENROUTER(
        displayName = "OpenRouter (Multi-Model)",
        shortLabel = "OpenRouter",
        requiresKey = true,
        defaultModel = "openrouter/auto",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        suggestedModels = listOf(
            "openrouter/auto",
            "google/gemini-2.5-flash",
            "anthropic/claude-3.5-haiku",
            "openai/gpt-4o-mini"
        )
    ),
    OPENAI(
        displayName = "OpenAI (BYOK)",
        shortLabel = "OpenAI",
        requiresKey = true,
        defaultModel = "gpt-4o-mini",
        defaultBaseUrl = "https://api.openai.com/v1",
        suggestedModels = listOf("gpt-4o-mini", "gpt-4.1-mini", "gpt-4o")
    ),
    ANTHROPIC(
        displayName = "Anthropic Claude (BYOK)",
        shortLabel = "Anthropic",
        requiresKey = true,
        defaultModel = "claude-3-5-haiku-latest",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        suggestedModels = listOf("claude-3-5-haiku-latest", "claude-sonnet-4-20250514")
    ),
    CUSTOM_OPENAI(
        displayName = "Custom OpenAI-Compatible",
        shortLabel = "Custom",
        requiresKey = false,
        defaultModel = "llama-3.3-70b-versatile",
        defaultBaseUrl = "https://api.groq.com/openai/v1",
        suggestedModels = listOf("llama-3.3-70b-versatile", "deepseek-chat", "qwen2.5-32b-instruct")
    )
}

enum class RoutingMode(val label: String, val description: String) {
    AUTO_ROUTER(
        label = "Auto Router",
        description = "Prioritizes enabled BYOK keys, rotates Firebase 3.8 Flash -> 3.5 Flash-Lite, and skips cooling-down 429 routes."
    ),
    LOCKED_PROVIDER(
        label = "Single Provider",
        description = "Locks inference to your chosen provider (with optional Firebase Flash-Lite failover if Firebase is selected)."
    )
}

@JsonClass(generateAdapter = true)
data class ProviderCredentialConfig(
    val provider: AiProviderType,
    val enabled: Boolean = provider == AiProviderType.FIREBASE_AI,
    val apiKey: String = "",
    val modelId: String = provider.defaultModel,
    val baseUrl: String = provider.defaultBaseUrl
) {
    fun isReady(): Boolean {
        if (!enabled) return false
        if (provider.requiresKey && apiKey.isBlank()) return false
        if (provider == AiProviderType.CUSTOM_OPENAI && baseUrl.isBlank()) return false
        return modelId.isNotBlank()
    }
}

@JsonClass(generateAdapter = true)
data class AiRouterSettings(
    val routingMode: RoutingMode = RoutingMode.AUTO_ROUTER,
    val lockedProvider: AiProviderType = AiProviderType.FIREBASE_AI,
    val firebaseRotateLiteOn429: Boolean = true,
    val providers: List<ProviderCredentialConfig> = defaultProviders()
) {
    fun configFor(type: AiProviderType): ProviderCredentialConfig =
        providers.firstOrNull { it.provider == type }
            ?: ProviderCredentialConfig(provider = type)

    fun enabledByokCount(): Int =
        providers.count { it.provider != AiProviderType.FIREBASE_AI && it.isReady() }

    fun activeSummary(): String {
        val byokCount = enabledByokCount()
        val firebaseEnabled = configFor(AiProviderType.FIREBASE_AI).enabled
        return when (routingMode) {
            RoutingMode.AUTO_ROUTER -> {
                val parts = buildList {
                    if (byokCount > 0) add("$byokCount BYOK")
                    if (firebaseEnabled) {
                        add(if (firebaseRotateLiteOn429) "Firebase 3.8/3.5-Lite" else "Firebase 3.8")
                    }
                }
                if (parts.isEmpty()) "Auto Router • No routes enabled"
                else "Auto Router • ${parts.joinToString(" + ")}"
            }
            RoutingMode.LOCKED_PROVIDER -> {
                val cfg = configFor(lockedProvider)
                "Locked • ${lockedProvider.shortLabel} (${cfg.modelId})"
            }
        }
    }

    companion object {
        fun defaultProviders(): List<ProviderCredentialConfig> = listOf(
            ProviderCredentialConfig(
                provider = AiProviderType.GEMINI_BYOK,
                enabled = false
            ),
            ProviderCredentialConfig(
                provider = AiProviderType.OPENROUTER,
                enabled = false
            ),
            ProviderCredentialConfig(
                provider = AiProviderType.OPENAI,
                enabled = false
            ),
            ProviderCredentialConfig(
                provider = AiProviderType.ANTHROPIC,
                enabled = false
            ),
            ProviderCredentialConfig(
                provider = AiProviderType.CUSTOM_OPENAI,
                enabled = false
            ),
            ProviderCredentialConfig(
                provider = AiProviderType.FIREBASE_AI,
                enabled = true
            )
        )
    }
}

data class AiRouterStatus(
    val lastRouteUsed: String? = null,
    val lastRouteLatencyMs: Long? = null,
    val lastRouteError: String? = null,
    val totalCalls: Int = 0,
    val failoverCount: Int = 0,
    val cooldownsRemainingSec: Map<String, Int> = emptyMap()
)

data class RouteTestResult(
    val success: Boolean,
    val routeLabel: String,
    val latencyMs: Long,
    val message: String
)
