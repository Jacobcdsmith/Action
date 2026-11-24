package com.example.ai

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "AiModelRouter"

enum class AiTaskKind {
    CHUNK_LAYOUT,
    NPC_DIALOGUE,
    PING_TEST
}

sealed interface RoutedAiResult {
    data class Success(
        val text: String,
        val routeLabel: String,
        val latencyMs: Long
    ) : RoutedAiResult

    data class Failure(
        val errorMessage: String,
        val latencyMs: Long
    ) : RoutedAiResult
}

private data class CandidateRoute(
    val routeId: String,
    val provider: AiProviderType,
    val modelId: String,
    val apiKey: String,
    val baseUrl: String,
    val displayLabel: String
)

private class ProviderHttpException(
    val statusCode: Int,
    override val message: String
) : Exception(message)

/**
 * Multi-provider AI Router supporting:
 * - Automatic failover across user BYOK keys (Gemini, OpenRouter, OpenAI, Anthropic, Custom OpenAI-compatible)
 * - Built-in Firebase AI Logic with automatic 429 cooldown rotation (`gemini-3.8-flash` -> `gemini-3.5-flash-lite`)
 */
class AiModelRouter(
    private val providerStore: AiProviderStore
) {
    private val _status = MutableStateFlow(AiRouterStatus())
    val status: StateFlow<AiRouterStatus> = _status.asStateFlow()

    private val cooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val firebaseModels = ConcurrentHashMap<String, GenerativeModel>()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(48, TimeUnit.SECONDS)
            .build()
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val retrySecondsRegex = Regex("""retry in ([0-9]+(?:\.[0-9]+)?)s""", RegexOption.IGNORE_CASE)

    suspend fun generateStructured(
        taskKind: AiTaskKind,
        systemInstruction: String,
        userPrompt: String,
        byokJsonContract: String
    ): RoutedAiResult = withContext(Dispatchers.IO) {
        val totalStartMs = System.currentTimeMillis()
        val settings = providerStore.settings.value
        val candidates = buildCandidateChain(settings)

        if (candidates.isEmpty()) {
            val msg = "No AI providers enabled or configured. Open AI Router & Keys to enable Firebase or enter a BYOK key."
            publishStatus(lastError = msg)
            return@withContext RoutedAiResult.Failure(msg, System.currentTimeMillis() - totalStartMs)
        }

        val now = System.currentTimeMillis()
        val readyRoutes = candidates.filter { (cooldownUntilMs[it.routeId] ?: 0L) <= now }
        val orderedAttempts = if (readyRoutes.isNotEmpty()) {
            readyRoutes
        } else {
            // All routes cooling down; try the one closest to expiry as a last shot
            candidates.sortedBy { cooldownUntilMs[it.routeId] ?: 0L }.take(1)
        }

        val attemptErrors = mutableListOf<String>()
        var failoversThisCall = 0

        for ((idx, route) in orderedAttempts.withIndex()) {
            val routeStartMs = System.currentTimeMillis()
            try {
                val rawText = withTimeout(45_000L) {
                    executeRoute(
                        route = route,
                        taskKind = taskKind,
                        systemInstruction = systemInstruction,
                        userPrompt = userPrompt,
                        byokJsonContract = byokJsonContract
                    )
                }
                val latency = System.currentTimeMillis() - routeStartMs
                cooldownUntilMs.remove(route.routeId)
                publishStatus(
                    lastRouteUsed = route.displayLabel,
                    lastLatencyMs = latency,
                    lastError = null,
                    incrementCalls = true,
                    addFailovers = failoversThisCall
                )
                return@withContext RoutedAiResult.Success(
                    text = rawText,
                    routeLabel = route.displayLabel,
                    latencyMs = latency
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: TimeoutCancellationException) {
                val err = "${route.displayLabel}: timed out after 45s"
                Log.w(TAG, err, e)
                attemptErrors.add(err)
                cooldownUntilMs[route.routeId] = System.currentTimeMillis() + 20_000L
                if (idx < orderedAttempts.lastIndex) failoversThisCall++
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: e.javaClass.simpleName
                val statusCode = (e as? ProviderHttpException)?.statusCode
                if (isRateLimitOrQuotaError(statusCode, msg)) {
                    val cooldownSec = extractRetryDelaySec(msg)
                    cooldownUntilMs[route.routeId] = System.currentTimeMillis() + cooldownSec * 1000L
                    attemptErrors.add("${route.displayLabel}: quota/429 (cooldown ${cooldownSec}s)")
                    Log.w(TAG, "Rate limit on ${route.displayLabel}; cooling down ${cooldownSec}s: $msg")
                } else {
                    attemptErrors.add("${route.displayLabel}: $msg")
                    Log.e(TAG, "Route ${route.displayLabel} failed", e)
                }
                if (idx < orderedAttempts.lastIndex) failoversThisCall++
            }
        }

        val skippedCooldowns = candidates
            .filter { (cooldownUntilMs[it.routeId] ?: 0L) > System.currentTimeMillis() && it !in orderedAttempts }
            .map { route ->
                val rem = (((cooldownUntilMs[route.routeId] ?: 0L) - System.currentTimeMillis()) / 1000L).coerceAtLeast(1L)
                "${route.displayLabel} (cooldown ${rem}s)"
            }
        val combined = buildString {
            append(attemptErrors.joinToString(" | "))
            if (skippedCooldowns.isNotEmpty()) {
                if (isNotEmpty()) append(" | Skipped: ")
                append(skippedCooldowns.joinToString(", "))
            }
        }
        publishStatus(
            lastError = combined,
            incrementCalls = true,
            addFailovers = failoversThisCall
        )
        RoutedAiResult.Failure(
            errorMessage = combined.ifBlank { "All AI routes failed." },
            latencyMs = System.currentTimeMillis() - totalStartMs
        )
    }

    suspend fun testProvider(targetProvider: AiProviderType? = null): RouteTestResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val settings = providerStore.settings.value
        val result = if (targetProvider == null) {
            generateStructured(
                taskKind = AiTaskKind.PING_TEST,
                systemInstruction = "Respond strictly with JSON: {\"ok\": true, \"status\": \"ready\"}",
                userPrompt = "Ping check for WorldShift Auto Router.",
                byokJsonContract = "Return {\"ok\": true, \"status\": \"ready\"}"
            )
        } else {
            val cfg = settings.configFor(targetProvider)
            if (targetProvider.requiresKey && cfg.apiKey.isBlank()) {
                return@withContext RouteTestResult(
                    success = false,
                    routeLabel = targetProvider.displayName,
                    latencyMs = 0L,
                    message = "Enter an API key for ${targetProvider.shortLabel} first."
                )
            }
            val route = CandidateRoute(
                routeId = "${targetProvider.name}:${cfg.modelId}",
                provider = targetProvider,
                modelId = cfg.modelId.ifBlank { targetProvider.defaultModel },
                apiKey = cfg.apiKey,
                baseUrl = cfg.baseUrl.ifBlank { targetProvider.defaultBaseUrl },
                displayLabel = "${targetProvider.shortLabel} (${cfg.modelId.ifBlank { targetProvider.defaultModel }})"
            )
            try {
                val text = withTimeout(30_000L) {
                    executeRoute(
                        route = route,
                        taskKind = AiTaskKind.PING_TEST,
                        systemInstruction = "Respond strictly with JSON: {\"ok\": true, \"status\": \"ready\"}",
                        userPrompt = "Ping check.",
                        byokJsonContract = "Return {\"ok\": true, \"status\": \"ready\"}"
                    )
                }
                cooldownUntilMs.remove(route.routeId)
                publishStatus(lastError = null)
                RoutedAiResult.Success(text, route.displayLabel, System.currentTimeMillis() - startMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RoutedAiResult.Failure(
                    errorMessage = e.localizedMessage ?: "Connection failed",
                    latencyMs = System.currentTimeMillis() - startMs
                )
            }
        }

        when (result) {
            is RoutedAiResult.Success -> RouteTestResult(
                success = true,
                routeLabel = result.routeLabel,
                latencyMs = result.latencyMs,
                message = "Connected via ${result.routeLabel} in ${result.latencyMs}ms"
            )
            is RoutedAiResult.Failure -> RouteTestResult(
                success = false,
                routeLabel = targetProvider?.displayName ?: "Auto Router",
                latencyMs = result.latencyMs,
                message = result.errorMessage
            )
        }
    }

    fun clearCooldowns() {
        cooldownUntilMs.clear()
        publishStatus()
    }

    private fun buildCandidateChain(settings: AiRouterSettings): List<CandidateRoute> {
        fun ProviderCredentialConfig.toCandidate(overrideModel: String = modelId): CandidateRoute {
            val effectiveModel = overrideModel.ifBlank { provider.defaultModel }
            return CandidateRoute(
                routeId = "${provider.name}:$effectiveModel",
                provider = provider,
                modelId = effectiveModel,
                apiKey = apiKey,
                baseUrl = baseUrl.ifBlank { provider.defaultBaseUrl },
                displayLabel = "${provider.shortLabel} ($effectiveModel)"
            )
        }

        return when (settings.routingMode) {
            RoutingMode.AUTO_ROUTER -> {
                val byokRoutes = settings.providers
                    .filter { it.provider != AiProviderType.FIREBASE_AI && it.isReady() }
                    .map { it.toCandidate() }

                val firebaseCfg = settings.configFor(AiProviderType.FIREBASE_AI)
                val firebaseRoutes = if (firebaseCfg.enabled) {
                    buildList {
                        val primaryModel = firebaseCfg.modelId.ifBlank { "gemini-3.8-flash" }
                        add(firebaseCfg.toCandidate(primaryModel))
                        if (settings.firebaseRotateLiteOn429 && primaryModel != "gemini-3.5-flash-lite") {
                            add(firebaseCfg.toCandidate("gemini-3.5-flash-lite"))
                        }
                    }
                } else {
                    emptyList()
                }
                byokRoutes + firebaseRoutes
            }

            RoutingMode.LOCKED_PROVIDER -> {
                val cfg = settings.configFor(settings.lockedProvider)
                if (settings.lockedProvider == AiProviderType.FIREBASE_AI) {
                    if (!cfg.enabled) return emptyList()
                    val primary = cfg.modelId.ifBlank { "gemini-3.8-flash" }
                    buildList {
                        add(cfg.toCandidate(primary))
                        if (settings.firebaseRotateLiteOn429 && primary != "gemini-3.5-flash-lite") {
                            add(cfg.toCandidate("gemini-3.5-flash-lite"))
                        }
                    }
                } else {
                    if (cfg.provider.requiresKey && cfg.apiKey.isBlank()) return emptyList()
                    if (cfg.provider == AiProviderType.CUSTOM_OPENAI && cfg.baseUrl.isBlank()) return emptyList()
                    listOf(cfg.toCandidate())
                }
            }
        }
    }

    private suspend fun executeRoute(
        route: CandidateRoute,
        taskKind: AiTaskKind,
        systemInstruction: String,
        userPrompt: String,
        byokJsonContract: String
    ): String {
        return when (route.provider) {
            AiProviderType.FIREBASE_AI -> executeFirebaseAi(route.modelId, taskKind, systemInstruction, userPrompt)
            AiProviderType.GEMINI_BYOK -> executeGeminiRest(route, systemInstruction, userPrompt, byokJsonContract)
            AiProviderType.OPENROUTER,
            AiProviderType.OPENAI,
            AiProviderType.CUSTOM_OPENAI -> executeOpenAiCompatible(route, systemInstruction, userPrompt, byokJsonContract)
            AiProviderType.ANTHROPIC -> executeAnthropic(route, systemInstruction, userPrompt, byokJsonContract)
        }
    }

    private suspend fun executeFirebaseAi(
        modelId: String,
        taskKind: AiTaskKind,
        systemInstruction: String,
        userPrompt: String
    ): String {
        val cacheKey = "$modelId:${taskKind.name}"
        val model = firebaseModels.getOrPut(cacheKey) {
            val config = generationConfig {
                responseMimeType = "application/json"
                when (taskKind) {
                    AiTaskKind.CHUNK_LAYOUT -> responseSchema = GeminiWorldSchema.chunkLayoutSchema
                    AiTaskKind.NPC_DIALOGUE -> responseSchema = GeminiWorldSchema.npcDialogueResponseSchema
                    AiTaskKind.PING_TEST -> Unit
                }
                temperature = if (taskKind == AiTaskKind.PING_TEST) 0.1f else 0.85f
            }
            Firebase.ai.generativeModel(
                modelName = modelId,
                generationConfig = config,
                systemInstruction = content { text(systemInstruction) }
            )
        }
        val response = model.generateContent(userPrompt)
        val text = response.text
        if (text.isNullOrBlank()) {
            throw IllegalStateException("Firebase AI returned an empty response.")
        }
        return text
    }

    private fun executeGeminiRest(
        route: CandidateRoute,
        systemInstruction: String,
        userPrompt: String,
        byokJsonContract: String
    ): String {
        val base = route.baseUrl.trimEnd('/')
        val url = "$base/models/${route.modelId}:generateContent?key=${route.apiKey}"

        val fullSystem = "$systemInstruction\n\nSTRICT JSON SCHEMA CONTRACT:\n$byokJsonContract"
        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", fullSystem)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
            }))
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.85)
            })
        }

        val bodyString = postJson(
            url = url,
            payload = payload.toString(),
            headers = emptyMap()
        )
        val root = JSONObject(bodyString)
        val candidates = root.optJSONArray("candidates")
            ?: throw IllegalStateException("Gemini REST returned no candidates")
        val first = candidates.optJSONObject(0)
            ?: throw IllegalStateException("Gemini REST returned empty candidates")
        val parts = first.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException("Gemini REST missing content parts")
        val rawText = buildString {
            for (i in 0 until parts.length()) {
                append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }
        }
        return extractJsonObject(rawText)
    }

    private fun executeOpenAiCompatible(
        route: CandidateRoute,
        systemInstruction: String,
        userPrompt: String,
        byokJsonContract: String
    ): String {
        val base = route.baseUrl.trimEnd('/')
        val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val fullSystem = "$systemInstruction\n\nRespond ONLY with a valid JSON object matching this exact contract:\n$byokJsonContract"

        val headers = buildMap {
            if (route.apiKey.isNotBlank()) {
                put("Authorization", "Bearer ${route.apiKey}")
            }
            if (route.provider == AiProviderType.OPENROUTER) {
                put("HTTP-Referer", "https://worldshift.app")
                put("X-Title", "WorldShift")
            }
        }

        fun buildPayload(includeResponseFormat: Boolean): String = JSONObject().apply {
            put("model", route.modelId)
            put("temperature", 0.85)
            if (includeResponseFormat) {
                put("response_format", JSONObject().put("type", "json_object"))
            }
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", fullSystem))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
        }.toString()

        val bodyString = try {
            postJson(url = url, payload = buildPayload(includeResponseFormat = true), headers = headers)
        } catch (e: ProviderHttpException) {
            // Some OpenAI-compatible servers reject `response_format: json_object` with 400
            if (e.statusCode == 400 && e.message.contains("response_format", ignoreCase = true)) {
                postJson(url = url, payload = buildPayload(includeResponseFormat = false), headers = headers)
            } else {
                throw e
            }
        }

        val root = JSONObject(bodyString)
        val choices = root.optJSONArray("choices")
            ?: throw IllegalStateException("${route.provider.shortLabel} returned no choices")
        val content = choices.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()
        return extractJsonObject(content)
    }

    private fun executeAnthropic(
        route: CandidateRoute,
        systemInstruction: String,
        userPrompt: String,
        byokJsonContract: String
    ): String {
        val base = route.baseUrl.trimEnd('/')
        val url = if (base.endsWith("/messages")) base else "$base/messages"
        val fullSystem = "$systemInstruction\n\nRespond ONLY with raw JSON (no markdown fences) matching:\n$byokJsonContract"

        val payload = JSONObject().apply {
            put("model", route.modelId)
            put("max_tokens", 4096)
            put("temperature", 0.85)
            put("system", fullSystem)
            put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", userPrompt)
            ))
        }.toString()

        val bodyString = postJson(
            url = url,
            payload = payload,
            headers = mapOf(
                "x-api-key" to route.apiKey,
                "anthropic-version" to "2023-06-01"
            )
        )
        val root = JSONObject(bodyString)
        val contentArr = root.optJSONArray("content")
            ?: throw IllegalStateException("Anthropic returned no content blocks")
        val rawText = buildString {
            for (i in 0 until contentArr.length()) {
                val block = contentArr.optJSONObject(i)
                if (block?.optString("type") == "text") {
                    append(block.optString("text"))
                }
            }
        }
        return extractJsonObject(rawText)
    }

    private fun postJson(
        url: String,
        payload: String,
        headers: Map<String, String>
    ): String {
        val reqBuilder = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val extractedMsg = extractApiErrorMessage(bodyStr).ifBlank { "HTTP ${resp.code}" }
                throw ProviderHttpException(resp.code, extractedMsg)
            }
            return bodyStr
        }
    }

    private fun extractApiErrorMessage(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val obj = JSONObject(body)
            val errObj = obj.optJSONObject("error")
            errObj?.optString("message")?.takeIf { it.isNotBlank() }
                ?: obj.optString("message").takeIf { it.isNotBlank() }
                ?: body.take(240)
        } catch (_: Exception) {
            body.take(240)
        }
    }

    private fun extractJsonObject(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1)
        }
        throw IllegalStateException("Provider did not return a JSON object")
    }

    private fun isRateLimitOrQuotaError(statusCode: Int?, message: String): Boolean {
        if (statusCode == 429) return true
        val lower = message.lowercase()
        return lower.contains("quota") ||
            lower.contains("rate limit") ||
            lower.contains("rate_limit") ||
            lower.contains("resource_exhausted") ||
            lower.contains("too many requests") ||
            lower.contains("429")
    }

    private fun extractRetryDelaySec(message: String): Long {
        val match = retrySecondsRegex.find(message)
        val parsed = match?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        return parsed?.toLong()?.plus(1L)?.coerceIn(15L, 180L) ?: 60L
    }

    private fun publishStatus(
        lastRouteUsed: String? = _status.value.lastRouteUsed,
        lastLatencyMs: Long? = _status.value.lastRouteLatencyMs,
        lastError: String? = _status.value.lastRouteError,
        incrementCalls: Boolean = false,
        addFailovers: Int = 0
    ) {
        val now = System.currentTimeMillis()
        val activeCooldowns = cooldownUntilMs.entries
            .mapNotNull { (key, until) ->
                val rem = ((until - now) / 1000L).toInt()
                if (rem > 0) key to rem else null
            }
            .toMap()

        _status.update { curr ->
            curr.copy(
                lastRouteUsed = lastRouteUsed,
                lastRouteLatencyMs = lastLatencyMs,
                lastRouteError = lastError,
                totalCalls = if (incrementCalls) curr.totalCalls + 1 else curr.totalCalls,
                failoverCount = curr.failoverCount + addFailovers,
                cooldownsRemainingSec = activeCooldowns
            )
        }
    }
}
