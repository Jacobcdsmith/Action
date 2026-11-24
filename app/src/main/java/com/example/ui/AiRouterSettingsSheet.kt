package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.AiProviderType
import com.example.ai.AiRouterSettings
import com.example.ai.AiRouterStatus
import com.example.ai.ProviderCredentialConfig
import com.example.ai.RouteTestResult
import com.example.ai.RoutingMode
import kotlinx.coroutines.launch

@Composable
fun AiRouterSettingsSheet(
    visible: Boolean,
    settings: AiRouterSettings,
    routerStatus: AiRouterStatus,
    onSaveSettings: (AiRouterSettings) -> Unit,
    onClearAllKeys: () -> Unit,
    onClearCooldowns: () -> Unit,
    onTestRoute: suspend (AiProviderType?) -> RouteTestResult,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = visible) {
        onClose()
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val coroutineScope = rememberCoroutineScope()
        var draft by remember(settings, visible) { mutableStateOf(settings) }
        var expandedProvider by remember { mutableStateOf<AiProviderType?>(AiProviderType.OPENROUTER) }
        var isTesting by remember { mutableStateOf(false) }
        var testResult by remember { mutableStateOf<RouteTestResult?>(null) }

        fun updateProvider(type: AiProviderType, transform: (ProviderCredentialConfig) -> ProviderCredentialConfig) {
            val updatedList = draft.providers.map { cfg ->
                if (cfg.provider == type) transform(cfg) else cfg
            }
            draft = draft.copy(providers = updatedList)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xBB060814))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = Color(0xF50D1124),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .border(1.dp, Color(0x5500F5D4), RoundedCornerShape(22.dp))
                    .testTag("ai_router_settings_sheet")
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Hub,
                                contentDescription = null,
                                tint = Color(0xFF00F5D4),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "AI ROUTER & BYOK PROVIDERS",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    letterSpacing = 0.6.sp
                                )
                                Text(
                                    text = draft.activeSummary(),
                                    color = Color(0xFF7DD3FC),
                                    fontSize = 11.sp
                                )
                            }
                        }
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.testTag("close_ai_router_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close AI Router settings",
                                tint = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Scrollable Body
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Router Health & Live Status
                        Surface(
                            color = Color(0xFF151C36),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "ROUTER TELEMETRY",
                                        color = Color(0xFF7DD3FC),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${routerStatus.totalCalls} calls • ${routerStatus.failoverCount} failovers",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 10.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Last Route: ${routerStatus.lastRouteUsed ?: "Not called yet"}${routerStatus.lastRouteLatencyMs?.let { " (${it}ms)" } ?: ""}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (routerStatus.cooldownsRemainingSec.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Cooldowns: " + routerStatus.cooldownsRemainingSec.entries
                                                .joinToString(", ") { "${it.key} (${it.value}s)" },
                                            color = Color(0xFFFACC15),
                                            fontSize = 10.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedButton(
                                            onClick = onClearCooldowns,
                                            modifier = Modifier
                                                .height(48.dp)
                                                .testTag("clear_cooldowns_button")
                                        ) {
                                            Text("Reset", fontSize = 11.sp)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            if (isTesting) return@OutlinedButton
                                            onSaveSettings(draft)
                                            isTesting = true
                                            testResult = null
                                            coroutineScope.launch {
                                                testResult = onTestRoute(null)
                                                isTesting = false
                                            }
                                        },
                                        modifier = Modifier
                                            .height(48.dp)
                                            .testTag("test_auto_router_button")
                                    ) {
                                        if (isTesting) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = Color(0xFF00F5D4)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                        }
                                        Text("Test Active Router", fontSize = 12.sp, color = Color(0xFF00F5D4))
                                    }
                                }
                                testResult?.let { res ->
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = res.message,
                                        color = if (res.success) Color(0xFF4ADE80) else Color(0xFFFF8A8A),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        // Routing Mode Selection
                        Text(
                            text = "ROUTING STRATEGY",
                            color = Color(0xFF7DD3FC),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RoutingMode.entries.forEach { mode ->
                                val selected = draft.routingMode == mode
                                Surface(
                                    color = if (selected) Color(0x2A00F5D4) else Color(0xFF151C36),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(
                                            1.dp,
                                            if (selected) Color(0xFF00F5D4) else Color(0xFF283252),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .clickable { draft = draft.copy(routingMode = mode) }
                                        .testTag("routing_mode_${mode.name}")
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = mode.label,
                                            color = if (selected) Color(0xFF00F5D4) else Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = mode.description,
                                            color = Color(0xFF94A3B8),
                                            fontSize = 10.sp,
                                            lineHeight = 13.sp
                                        )
                                    }
                                }
                            }
                        }

                        if (draft.routingMode == RoutingMode.LOCKED_PROVIDER) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AiProviderType.entries.forEach { type ->
                                    val isLocked = draft.lockedProvider == type
                                    Surface(
                                        color = if (isLocked) Color(0xFF7E22CE) else Color(0xFF1A223D),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .height(48.dp)
                                            .clickable {
                                                draft = draft.copy(lockedProvider = type)
                                                updateProvider(type) { it.copy(enabled = true) }
                                                expandedProvider = type
                                            }
                                            .testTag("lock_provider_${type.name}")
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(horizontal = 12.dp)
                                        ) {
                                            Text(
                                                text = type.shortLabel,
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Built-in Firebase AI Logic Card
                        val firebaseCfg = draft.configFor(AiProviderType.FIREBASE_AI)
                        Surface(
                            color = Color(0xFF151C36),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    if (firebaseCfg.enabled) Color(0x4400F5D4) else Color(0xFF283252),
                                    RoundedCornerShape(14.dp)
                                )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Firebase AI Logic (No Key Needed)",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "App Check attested • Free tier has 5 RPM on 3.8 Flash",
                                            color = Color(0xFF94A3B8),
                                            fontSize = 10.sp
                                        )
                                    }
                                    Switch(
                                        checked = firebaseCfg.enabled,
                                        onCheckedChange = { checked ->
                                            updateProvider(AiProviderType.FIREBASE_AI) { it.copy(enabled = checked) }
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00F5D4)),
                                        modifier = Modifier.testTag("toggle_firebase_ai")
                                    )
                                }
                                if (firebaseCfg.enabled) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Auto-rotate 3.8 Flash -> 3.5 Flash-Lite on 429",
                                            color = Color(0xFFCBD5E1),
                                            fontSize = 11.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Switch(
                                            checked = draft.firebaseRotateLiteOn429,
                                            onCheckedChange = { checked ->
                                                draft = draft.copy(firebaseRotateLiteOn429 = checked)
                                            },
                                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFE879F9)),
                                            modifier = Modifier.testTag("toggle_firebase_lite_failover")
                                        )
                                    }
                                }
                            }
                        }

                        // BYOK Providers
                        Text(
                            text = "BRING YOUR OWN KEY (BYOK) PROVIDERS",
                            color = Color(0xFFE879F9),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )

                        draft.providers
                            .filter { it.provider != AiProviderType.FIREBASE_AI }
                            .forEach { cfg ->
                                ByokProviderCard(
                                    config = cfg,
                                    expanded = expandedProvider == cfg.provider,
                                    isTesting = isTesting,
                                    onToggleExpand = {
                                        expandedProvider = if (expandedProvider == cfg.provider) null else cfg.provider
                                    },
                                    onConfigChange = { updated ->
                                        updateProvider(cfg.provider) { updated }
                                    },
                                    onTestProvider = {
                                        if (isTesting) return@ByokProviderCard
                                        onSaveSettings(draft)
                                        isTesting = true
                                        testResult = null
                                        coroutineScope.launch {
                                            testResult = onTestRoute(cfg.provider)
                                            isTesting = false
                                        }
                                    }
                                )
                            }

                        Text(
                            text = "Keys are stored locally on-device in private app preferences and transmitted only to the selected provider API.",
                            color = Color(0xFF64748B),
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Bottom Action Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onClearAllKeys()
                                draft = AiRouterSettings()
                                testResult = null
                            },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("clear_byok_keys_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = "Clear all API keys",
                                tint = Color(0xFFFF8A8A),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Clear Keys", color = Color(0xFFFF8A8A), fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                onSaveSettings(draft)
                                onClose()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00F5D4),
                                contentColor = Color(0xFF090B1A)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("save_ai_router_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save & Apply", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ByokProviderCard(
    config: ProviderCredentialConfig,
    expanded: Boolean,
    isTesting: Boolean,
    onToggleExpand: () -> Unit,
    onConfigChange: (ProviderCredentialConfig) -> Unit,
    onTestProvider: () -> Unit
) {
    var keyVisible by remember { mutableStateOf(false) }
    val ready = config.isReady()
    val borderColor = when {
        ready -> Color(0x664ADE80)
        config.enabled -> Color(0x66FACC15)
        else -> Color(0xFF283252)
    }

    Surface(
        color = Color(0xFF151C36),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Key,
                        contentDescription = null,
                        tint = if (ready) Color(0xFF4ADE80) else Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = config.provider.displayName,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        val statusText = when {
                            ready -> "Active • ${config.modelId}"
                            config.enabled && config.provider.requiresKey -> "Needs API Key"
                            else -> "Disabled • ${config.modelId}"
                        }
                        Text(
                            text = statusText,
                            color = when {
                                ready -> Color(0xFF4ADE80)
                                config.enabled && config.provider.requiresKey -> Color(0xFFFACC15)
                                else -> Color(0xFF94A3B8)
                            },
                            fontSize = 10.sp
                        )
                    }
                }
                Switch(
                    checked = config.enabled,
                    onCheckedChange = { checked ->
                        onConfigChange(config.copy(enabled = checked))
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF4ADE80)),
                    modifier = Modifier.testTag("toggle_provider_${config.provider.name}")
                )
                IconButton(
                    onClick = onToggleExpand,
                    modifier = Modifier.testTag("expand_provider_${config.provider.name}")
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = "Expand ${config.provider.displayName}",
                        tint = Color(0xFFCBD5E1)
                    )
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = config.apiKey,
                    onValueChange = { rawKey ->
                        val autoEnable = if (rawKey.isNotBlank()) true else config.enabled
                        onConfigChange(config.copy(apiKey = rawKey, enabled = autoEnable))
                    },
                    label = {
                        Text(
                            if (config.provider.requiresKey) "API Key" else "API Key (optional for local)",
                            fontSize = 11.sp
                        )
                    },
                    placeholder = { Text("Paste ${config.provider.shortLabel} API key...", fontSize = 11.sp) },
                    singleLine = true,
                    visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (keyVisible) "Hide API key" else "Show API key",
                                tint = Color(0xFF94A3B8)
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00F5D4),
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("apikey_input_${config.provider.name}")
                )

                if (config.provider == AiProviderType.CUSTOM_OPENAI) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = config.baseUrl,
                        onValueChange = { onConfigChange(config.copy(baseUrl = it)) },
                        label = { Text("OpenAI-Compatible Base URL", fontSize = 11.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00F5D4),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("baseurl_input_${config.provider.name}")
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val customPresets = listOf(
                        "Groq" to "https://api.groq.com/openai/v1",
                        "Together" to "https://api.together.xyz/v1",
                        "DeepSeek" to "https://api.deepseek.com/v1",
                        "Local Ollama" to "http://10.0.2.2:11434/v1"
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        customPresets.forEach { (label, url) ->
                            Surface(
                                color = Color(0xFF1F2746),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .clickable { onConfigChange(config.copy(baseUrl = url, enabled = true)) }
                            ) {
                                Text(
                                    text = label,
                                    color = Color(0xFF7DD3FC),
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = config.modelId,
                    onValueChange = { onConfigChange(config.copy(modelId = it)) },
                    label = { Text("Model ID", fontSize = 11.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFE879F9),
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("model_input_${config.provider.name}")
                )

                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    config.provider.suggestedModels.forEach { model ->
                        val selected = config.modelId == model
                        Surface(
                            color = if (selected) Color(0x33E879F9) else Color(0xFF1F2746),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.clickable {
                                onConfigChange(config.copy(modelId = model))
                            }
                        ) {
                            Text(
                                text = model,
                                color = if (selected) Color(0xFFE879F9) else Color(0xFFCBD5E1),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onTestProvider,
                    enabled = !isTesting,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("test_provider_${config.provider.name}")
                ) {
                    Text(
                        text = "Test ${config.provider.shortLabel}",
                        color = Color(0xFF00F5D4),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
