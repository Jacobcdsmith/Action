package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.AiGenerationLogEntry
import com.example.ai.AiRouterStatus
import com.example.ai.LookaheadBufferStatus
import com.example.ai.LookaheadState
import com.example.ai.StoryArcSnapshot
import com.example.engine.ChunkSource
import com.example.engine.HudSnapshot

@Composable
fun TelemetryDrawer(
    visible: Boolean,
    hud: HudSnapshot,
    bufferStatus: LookaheadBufferStatus,
    storyArc: StoryArcSnapshot,
    npcSpawnerState: NpcSpawnerState,
    routerSummary: String,
    routerStatus: AiRouterStatus,
    onOpenAiRouterSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expandedChunkIndex by remember { mutableIntStateOf(-1) }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
        modifier = modifier
    ) {
        Surface(
            color = Color(0xF20D1124),
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 380.dp)
                .fillMaxWidth(0.88f)
                .border(1.dp, Color(0x337DD3FC))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .testTag("telemetry_drawer")
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AI TELEMETRY & STORY",
                        color = Color(0xFF00F5D4),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = 1.sp
                    )
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("close_telemetry_drawer_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close AI telemetry drawer",
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // AI Provider Router & BYOK Card
                InspectorSection(title = "AI PROVIDER & AUTO ROUTER") {
                    KeyValueRow("Mode", routerSummary, Color(0xFF00F5D4))
                    KeyValueRow(
                        "Last Route",
                        routerStatus.lastRouteUsed ?: "None yet",
                        Color(0xFF7DD3FC)
                    )
                    KeyValueRow(
                        "Calls / Failovers",
                        "${routerStatus.totalCalls} / ${routerStatus.failoverCount}"
                    )
                    if (routerStatus.cooldownsRemainingSec.isNotEmpty()) {
                        Text(
                            text = "Cooling down: " + routerStatus.cooldownsRemainingSec.entries
                                .joinToString(", ") { "${it.key} (${it.value}s)" },
                            color = Color(0xFFFACC15),
                            fontSize = 10.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onOpenAiRouterSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("open_ai_router_from_telemetry_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Hub,
                            contentDescription = null,
                            tint = Color(0xFF00F5D4)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Configure BYOK / Auto Router",
                            color = Color(0xFF00F5D4),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Story Arc & Quests Card
                InspectorSection(title = "STORY ARC & QUESTS") {
                    KeyValueRow("Chapter", "Chapter ${storyArc.chapterNumber}", Color(0xFFC084FC))
                    KeyValueRow("Companion", storyArc.companionName ?: "None", Color(0xFF4ADE80))
                    KeyValueRow("NPC Spawner", npcSpawnerState.phase.name, Color(0xFF7DD3FC))
                    KeyValueRow(
                        "Last Spawn",
                        npcSpawnerState.lastSpawnedNpcName ?: "None (${npcSpawnerState.totalDynamicSpawns})"
                    )
                    Text(
                        text = npcSpawnerState.lastSpawnReason,
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp
                    )
                    KeyValueRow("Completed Quests", "${storyArc.completedQuestsCount}")
                    storyArc.activeQuests.forEach { q ->
                        val status = if (q.completed) "DONE" else "${q.currentCount}/${q.targetCount}"
                        KeyValueRow(q.title, status, if (q.completed) Color(0xFF4ADE80) else Color(0xFFFACC15))
                    }
                    if (storyArc.narrativeBeats.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        storyArc.narrativeBeats.takeLast(3).forEach { beat ->
                            Text(
                                text = "• $beat",
                                color = Color(0xFFCBD5E1),
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Lookahead Buffer Card
                InspectorSection(title = "LOOKAHEAD BUFFER") {
                    val stateColor = when (bufferStatus.state) {
                        LookaheadState.READY -> Color(0xFF4ADE80)
                        LookaheadState.GENERATING -> Color(0xFFFACC15)
                        LookaheadState.ERROR -> Color(0xFFFF4D6D)
                        LookaheadState.FALLBACK -> Color(0xFFFB923C)
                        LookaheadState.IDLE -> Color(0xFF94A3B8)
                    }
                    KeyValueRow("Buffer State", bufferStatus.state.name, stateColor)
                    KeyValueRow(
                        "In-Flight Chunk",
                        bufferStatus.inFlightChunkIndex?.let { "#${it + 1}" } ?: "None"
                    )
                    KeyValueRow(
                        "Buffered AI Chunk",
                        bufferStatus.bufferedAiChunkIndex?.let { "#${it + 1}" } ?: "None"
                    )
                    KeyValueRow("Active Window", "${hud.activeChunkCount} chunks (N-2..N+1)")
                    bufferStatus.pendingRealityShift?.let { shift ->
                        KeyValueRow("Queued Shift", shift, Color(0xFFC084FC))
                    }
                    bufferStatus.lastError?.let { err ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Last Fallback Reason: $err",
                            color = Color(0xFFFF8A8A),
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Player & Physics Telemetry Card
                InspectorSection(title = "PLAYER TELEMETRY & PHYSICS") {
                    KeyValueRow("Seed", bufferStatus.activeSeedPrompt.take(40))
                    KeyValueRow("Current Biome", hud.currentBiomeName)
                    KeyValueRow("Avg Speed", "${"%.1f".format(hud.averageSpeed)} px/s")
                    KeyValueRow("Air Time Ratio", "${"%.0f".format(hud.airTimeRatio * 100f)}%")
                    KeyValueRow("Defeated / Deaths", "${hud.enemiesDefeated} / ${hud.deaths}")
                    KeyValueRow("Validator Repairs", "${hud.autoRepairsApplied}")
                    hud.currentBiome?.let { b ->
                        KeyValueRow(
                            "Gravity / Friction",
                            "${b.physicsModifiers.gravityScale}x / ${b.physicsModifiers.friction}"
                        )
                        KeyValueRow(
                            "Audio Synth",
                            "${b.audioParams.bpm} BPM • ${b.audioParams.waveformTimbre.name}"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "VALIDATED CHUNK PAYLOADS (${bufferStatus.history.size})",
                    color = Color(0xFF7DD3FC),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (bufferStatus.history.isEmpty()) {
                    Text(
                        text = "Waiting for AI Director chunk responses...",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                } else {
                    bufferStatus.history.forEach { entry ->
                        ChunkPayloadCard(
                            entry = entry,
                            expanded = expandedChunkIndex == entry.chunkIndex,
                            onToggle = {
                                expandedChunkIndex = if (expandedChunkIndex == entry.chunkIndex) {
                                    -1
                                } else {
                                    entry.chunkIndex
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectorSection(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        color = Color(0xFF151C36),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                color = Color(0xFF7DD3FC),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun KeyValueRow(
    label: String,
    value: String,
    valueColor: Color = Color.White
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 11.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ChunkPayloadCard(
    entry: AiGenerationLogEntry,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val badgeColor = if (entry.source == ChunkSource.AI_GENERATED) {
        Color(0xFF4ADE80)
    } else {
        Color(0xFFFB923C)
    }
    Surface(
        color = Color(0xFF151C36),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .testTag("chunk_log_${entry.chunkIndex}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Chunk #${entry.chunkIndex + 1} • ${entry.biomeName}",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Text(
                    text = "${entry.source.name.take(8)} • ${entry.latencyMs}ms",
                    color = badgeColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "Route: ${entry.providerLabel} • Repairs: ${entry.repairsApplied}${entry.realityShiftModifier?.let { " • Shift: $it" } ?: ""}",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp
            )
            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                if (entry.repairNotes.isNotEmpty()) {
                    Text(
                        text = entry.repairNotes.joinToString("\n• ", prefix = "• "),
                        color = Color(0xFFFACC15),
                        fontSize = 10.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF080C1A), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = entry.rawJson.take(2800),
                        color = Color(0xFFBAE6FD),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}
