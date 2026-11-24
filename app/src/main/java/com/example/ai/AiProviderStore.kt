package com.example.ai

import android.content.Context
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AiProviderStore"
private const val PREFS_NAME = "worldshift_ai_router"
private const val KEY_SETTINGS_JSON = "router_settings_v1"

/**
 * Persists user-configured BYOK provider keys, model selections, and Auto Router preferences
 * strictly in app-private SharedPreferences without embedding any credentials in the APK.
 */
class AiProviderStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    private val adapter by lazy {
        moshi.adapter(AiRouterSettings::class.java)
    }

    private val _settings = MutableStateFlow(loadInitial())
    val settings: StateFlow<AiRouterSettings> = _settings.asStateFlow()

    private fun loadInitial(): AiRouterSettings {
        val raw = prefs.getString(KEY_SETTINGS_JSON, null) ?: return AiRouterSettings()
        return try {
            val parsed = adapter.fromJson(raw) ?: return AiRouterSettings()
            // Ensure any newly added provider types appear in the saved list
            val existingByType = parsed.providers.associateBy { it.provider }
            val merged = AiRouterSettings.defaultProviders().map { def ->
                existingByType[def.provider] ?: def
            }
            parsed.copy(providers = merged)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse stored AI router settings, resetting to defaults", e)
            AiRouterSettings()
        }
    }

    fun updateSettings(newSettings: AiRouterSettings) {
        val normalized = newSettings.copy(
            providers = newSettings.providers.map { cfg ->
                cfg.copy(
                    apiKey = cfg.apiKey.trim(),
                    modelId = cfg.modelId.trim().ifBlank { cfg.provider.defaultModel },
                    baseUrl = cfg.baseUrl.trim().ifBlank { cfg.provider.defaultBaseUrl }
                )
            }
        )
        _settings.value = normalized
        try {
            prefs.edit().putString(KEY_SETTINGS_JSON, adapter.toJson(normalized)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist AI router settings", e)
        }
    }

    fun clearAllApiKeys() {
        val current = _settings.value
        val cleared = current.copy(
            providers = current.providers.map { cfg ->
                if (cfg.provider == AiProviderType.FIREBASE_AI) {
                    cfg.copy(enabled = true)
                } else {
                    cfg.copy(enabled = false, apiKey = "")
                }
            }
        )
        updateSettings(cleared)
    }
}
