package com.example.ai

import com.example.engine.ChunkSource
import com.example.engine.FallbackGenerator
import com.example.engine.PlatformerEngine
import com.example.engine.ReachabilityValidator
import com.example.engine.TelemetryTracker
import com.example.schemas.BiomeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Manages non-blocking async lookahead chunk generation (N+1), Reality Shift
 * overrides, ReachabilityValidator auto-repair accounting, and deterministic fallback
 * stitching so the 60 FPS loop never stalls.
 */
class ChunkLookaheadBuffer(
    private val aiDirector: AiDirector,
    private val scope: CoroutineScope
) {
    private val _status = MutableStateFlow(LookaheadBufferStatus())
    val status: StateFlow<LookaheadBufferStatus> = _status.asStateFlow()

    private var inFlightJob: Job? = null
    private var activeSeedPrompt: String = "Cyberpunk fungal swamp ruled by clockwork owls"
    private var queuedRealityShift: String? = null

    fun initializeRun(seedPrompt: String, engine: PlatformerEngine) {
        inFlightJob?.cancel()
        activeSeedPrompt = seedPrompt.ifBlank { "Cyberpunk fungal swamp ruled by clockwork owls" }
        queuedRealityShift = null

        _status.value = LookaheadBufferStatus(
            activeSeedPrompt = activeSeedPrompt,
            state = LookaheadState.GENERATING,
            inFlightChunkIndex = 0
        )

        // Start engine immediately with deterministic biome seed so 60 FPS never blocks
        engine.startNewRun(activeSeedPrompt)

        // Concurrently request AI synthesis for Chunk 1 (and Chunk 0 upgrade if still in early sector)
        requestChunkAsync(
            targetChunkIndex = 1,
            previousBiome = engine.currentBiome(),
            telemetry = engine.telemetry,
            engine = engine
        )
    }

    fun requestLookahead(
        nextChunkIndex: Int,
        currentBiome: BiomeConfig,
        telemetry: TelemetryTracker,
        engine: PlatformerEngine
    ) {
        requestChunkAsync(
            targetChunkIndex = nextChunkIndex,
            previousBiome = currentBiome,
            telemetry = telemetry,
            engine = engine
        )
    }

    fun queueRealityShift(
        modifierPrompt: String,
        currentChunkIndex: Int,
        currentBiome: BiomeConfig,
        telemetry: TelemetryTracker,
        engine: PlatformerEngine
    ) {
        val cleaned = modifierPrompt.trim()
        if (cleaned.isEmpty()) return
        queuedRealityShift = cleaned
        _status.update { it.copy(pendingRealityShift = cleaned) }

        // Immediately trigger/replace N+1 generation with the Reality Shift directive
        inFlightJob?.cancel()
        requestChunkAsync(
            targetChunkIndex = currentChunkIndex + 1,
            previousBiome = currentBiome,
            telemetry = telemetry,
            engine = engine
        )
    }

    fun cancelAll() {
        inFlightJob?.cancel()
        inFlightJob = null
    }

    private fun requestChunkAsync(
        targetChunkIndex: Int,
        previousBiome: BiomeConfig,
        telemetry: TelemetryTracker,
        engine: PlatformerEngine
    ) {
        inFlightJob?.cancel()
        val shiftToApply = queuedRealityShift
        queuedRealityShift = null

        _status.update {
            it.copy(
                state = LookaheadState.GENERATING,
                inFlightChunkIndex = targetChunkIndex,
                pendingRealityShift = shiftToApply,
                lastError = null
            )
        }

        inFlightJob = scope.launch {
            try {
                when (
                    val result = aiDirector.generateChunk(
                        chunkIndex = targetChunkIndex,
                        seedPrompt = activeSeedPrompt,
                        previousBiome = previousBiome,
                        telemetry = telemetry,
                        storySummary = engine.storyArcTracker.buildPromptSummary(),
                        realityShiftOverride = shiftToApply
                    )
                ) {
                    is AiChunkResult.Success -> {
                        val report = ReachabilityValidator.validateAndRepair(result.layout)
                        engine.submitGeneratedChunk(report.layout, ChunkSource.AI_GENERATED)

                        val logEntry = AiGenerationLogEntry(
                            chunkIndex = targetChunkIndex,
                            biomeName = report.layout.biome.name,
                            source = ChunkSource.AI_GENERATED,
                            providerLabel = result.providerLabel,
                            state = LookaheadState.READY,
                            latencyMs = result.latencyMs,
                            repairsApplied = report.repairsApplied,
                            repairNotes = report.notes,
                            realityShiftModifier = shiftToApply,
                            errorMessage = null,
                            rawJson = result.rawJson
                        )
                        _status.update { curr ->
                            curr.copy(
                                state = LookaheadState.READY,
                                inFlightChunkIndex = null,
                                bufferedAiChunkIndex = targetChunkIndex,
                                pendingRealityShift = null,
                                lastError = null,
                                history = (listOf(logEntry) + curr.history).take(10)
                            )
                        }
                    }

                    is AiChunkResult.Failure -> {
                        val fallbackLayout = FallbackGenerator.generateChunk(
                            chunkIndex = targetChunkIndex,
                            biomeTemplate = previousBiome
                        )
                        val report = ReachabilityValidator.validateAndRepair(fallbackLayout)
                        engine.submitGeneratedChunk(report.layout, ChunkSource.FALLBACK_PROCEDURAL)

                        val logEntry = AiGenerationLogEntry(
                            chunkIndex = targetChunkIndex,
                            biomeName = report.layout.biome.name,
                            source = ChunkSource.FALLBACK_PROCEDURAL,
                            providerLabel = "Procedural Fallback",
                            state = LookaheadState.FALLBACK,
                            latencyMs = result.latencyMs,
                            repairsApplied = report.repairsApplied,
                            repairNotes = report.notes,
                            realityShiftModifier = shiftToApply,
                            errorMessage = result.errorMessage,
                            rawJson = result.rawJson ?: "{}"
                        )
                        _status.update { curr ->
                            curr.copy(
                                state = LookaheadState.ERROR,
                                inFlightChunkIndex = null,
                                pendingRealityShift = null,
                                lastError = result.errorMessage,
                                history = (listOf(logEntry) + curr.history).take(10)
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }
}
