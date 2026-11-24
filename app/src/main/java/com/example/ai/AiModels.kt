package com.example.ai

import com.example.engine.ChunkSource
import com.example.engine.TelemetryTracker
import com.example.schemas.ChunkLayout

enum class LookaheadState {
    IDLE,
    GENERATING,
    READY,
    FALLBACK,
    ERROR
}

data class AiTelemetryPayload(
    val deaths: Int,
    val averageSpeed: Float,
    val airTimeRatio: Float,
    val enemiesDefeated: Int,
    val damageTaken: Int,
    val collectiblesGathered: Int,
    val currentBiomeName: String
) {
    companion object {
        fun fromTracker(tracker: TelemetryTracker, biomeName: String): AiTelemetryPayload =
            AiTelemetryPayload(
                deaths = tracker.deaths,
                averageSpeed = tracker.averageSpeed,
                airTimeRatio = tracker.airTimeRatio,
                enemiesDefeated = tracker.enemiesDefeated,
                damageTaken = tracker.damageTaken,
                collectiblesGathered = tracker.collectiblesGathered,
                currentBiomeName = biomeName
            )
    }
}

data class AiGenerationLogEntry(
    val chunkIndex: Int,
    val biomeName: String,
    val source: ChunkSource,
    val providerLabel: String,
    val state: LookaheadState,
    val latencyMs: Long,
    val repairsApplied: Int,
    val repairNotes: List<String>,
    val realityShiftModifier: String?,
    val errorMessage: String?,
    val rawJson: String
)

data class LookaheadBufferStatus(
    val activeSeedPrompt: String = "",
    val pendingRealityShift: String? = null,
    val state: LookaheadState = LookaheadState.IDLE,
    val inFlightChunkIndex: Int? = null,
    val bufferedAiChunkIndex: Int? = null,
    val lastError: String? = null,
    val history: List<AiGenerationLogEntry> = emptyList()
)

sealed interface AiChunkResult {
    data class Success(
        val layout: ChunkLayout,
        val rawJson: String,
        val providerLabel: String,
        val latencyMs: Long
    ) : AiChunkResult

    data class Failure(
        val errorMessage: String,
        val rawJson: String?,
        val latencyMs: Long
    ) : AiChunkResult
}
