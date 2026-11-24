package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.engine.HudSnapshot
import com.example.engine.InputSnapshot
import com.example.engine.PhysicsConstants
import com.example.engine.PlatformerEngine
import com.example.engine.RuntimeNpc
import com.example.engine.WorldGenSpawnParams
import com.example.render.SpriteCompiler
import com.example.render.WorldCanvasRenderer.renderWorld
import com.example.schemas.BiomeConfig
import com.example.schemas.NpcArchetype
import com.example.schemas.NpcArchetypeCatalog
import com.example.schemas.NpcArchetypeKind
import com.example.schemas.ParallaxSilhouetteType
import com.example.schemas.SpriteMatrix
import com.example.schemas.WeatherType
import kotlin.math.abs
import kotlin.math.sin

/**
 * Biome family selected from current world-generation parameters to synthesize
 * depth-layered pixel sprites for parallax background scrolling.
 */
enum class ParallaxBiomeTheme {
    GLACIER_ARCHIVE,
    CINDER_FOUNDRY,
    NEON_MYCELIUM
}

/**
 * Configuration and compiled sprite matrix for a single depth-sorted parallax layer.
 */
data class ParallaxSpriteLayer(
    val layerIndex: Int,
    val depthFactor: Float,
    val silhouetteType: ParallaxSilhouetteType,
    val spriteMatrix: SpriteMatrix,
    val cacheKey: String,
    val baseScale: Float,
    val spacingPx: Float,
    val verticalBandFraction: Float,
    val alpha: Float,
    val bobAmplitudePx: Float
)

/**
 * Screen-space positioned parallax sprite computed each frame for Compose [Canvas] rendering.
 */
data class PositionedParallaxSprite(
    val cacheKey: String,
    val matrix: SpriteMatrix,
    val depthFactor: Float,
    val screenX: Float,
    val screenY: Float,
    val widthPx: Int,
    val heightPx: Int,
    val alpha: Float
)

/**
 * Synthesizes 16x16 biome-specific sprite matrices using the active [BiomeConfig] palette
 * and [WorldGenSpawnParams], without relying on any static image assets.
 */
object ParallaxSpriteCatalog {

    fun resolveTheme(biome: BiomeConfig, params: WorldGenSpawnParams): ParallaxBiomeTheme {
        val lower = "${biome.name} ${params.biomeName}".lowercase()
        return when {
            params.weatherType == WeatherType.SNOW ||
                lower.contains("glacier") ||
                lower.contains("ice") ||
                lower.contains("archive") -> ParallaxBiomeTheme.GLACIER_ARCHIVE

            params.weatherType == WeatherType.EMBER ||
                lower.contains("cinder") ||
                lower.contains("lava") ||
                lower.contains("volcan") ||
                lower.contains("foundry") -> ParallaxBiomeTheme.CINDER_FOUNDRY

            else -> ParallaxBiomeTheme.NEON_MYCELIUM
        }
    }

    fun buildLayers(biome: BiomeConfig, params: WorldGenSpawnParams): List<ParallaxSpriteLayer> {
        val theme = resolveTheme(biome, params)
        val sortedConfigs = biome.parallaxConfig
            .sortedBy { it.depthFactor }
            .ifEmpty {
                listOf(
                    com.example.schemas.ParallaxLayerConfig(0.15f, ParallaxSilhouetteType.CRYSTALLINE_SPIRES, biome.palette.platformFill, 0.55f, 0.7f),
                    com.example.schemas.ParallaxLayerConfig(0.35f, ParallaxSilhouetteType.FLOATING_MONOLITHS, biome.palette.platformEdge, 0.65f, 0.55f),
                    com.example.schemas.ParallaxLayerConfig(0.60f, ParallaxSilhouetteType.RUINED_MEGASTRUCTURES, biome.palette.accentColor, 0.75f, 0.45f)
                )
            }

        return sortedConfigs.mapIndexed { idx, layerCfg ->
            val depth = layerCfg.depthFactor.coerceIn(0.08f, 0.85f)
            val matrix = createLayerSpriteMatrix(
                theme = theme,
                layerIndex = idx,
                tintHex = layerCfg.tintHex,
                accentHex = biome.palette.accentColor,
                edgeHex = biome.palette.platformEdge
            )
            val density = layerCfg.density.coerceIn(0.25f, 1.0f)
            val scale = 2.0f + idx * 0.9f
            val spacing = (360f / density).coerceIn(220f, 540f)
            val verticalBand = (0.24f + idx * 0.17f).coerceIn(0.18f, 0.72f)
            val alpha = (0.40f + idx * 0.18f).coerceIn(0.35f, 0.82f)
            val bobAmp = if (params.gravityScale < 0.9f) 10f else 5f

            ParallaxSpriteLayer(
                layerIndex = idx,
                depthFactor = depth,
                silhouetteType = layerCfg.silhouetteType,
                spriteMatrix = matrix,
                cacheKey = "px_${theme.name}_${layerCfg.tintHex}_${biome.palette.platformEdge}_${biome.palette.accentColor}_$idx",
                baseScale = scale,
                spacingPx = spacing,
                verticalBandFraction = verticalBand,
                alpha = alpha,
                bobAmplitudePx = bobAmp
            )
        }
    }

    private fun createLayerSpriteMatrix(
        theme: ParallaxBiomeTheme,
        layerIndex: Int,
        tintHex: String,
        accentHex: String,
        edgeHex: String
    ): SpriteMatrix {
        val frames = when (theme) {
            ParallaxBiomeTheme.GLACIER_ARCHIVE -> when (layerIndex % 3) {
                0 -> crystalSpireFrames()
                1 -> floatingRuneTomeFrames()
                else -> frostPylonFrames()
            }
            ParallaxBiomeTheme.CINDER_FOUNDRY -> when (layerIndex % 3) {
                0 -> basaltCragFrames()
                1 -> emberLanternFrames()
                else -> forgeSmokestackFrames()
            }
            ParallaxBiomeTheme.NEON_MYCELIUM -> when (layerIndex % 3) {
                0 -> dataMonolithFrames()
                1 -> glowSporeCapFrames()
                else -> cyberRelayFrames()
            }
        }
        return SpriteMatrix(
            width = 16,
            height = 16,
            paletteMap = mapOf(
                "A" to tintHex,
                "B" to edgeHex,
                "C" to accentHex
            ),
            frames = frames
        )
    }

    private fun crystalSpireFrames(): List<List<String>> = listOf(
        listOf(
            ".......AA.......",
            "......ABBA......",
            ".....ABCCBA.....",
            ".....ABCCBA.....",
            "....AABCBBAA....",
            "....AABBCCBA....",
            "...AAABBBCCAA...",
            "...AABBBBBAAA...",
            "..AAABBCBBBAAA..",
            "..AABBBBBBBBAA..",
            ".AAABBBBBBBBAAA.",
            ".AABBBBBBBBBBAA.",
            "AAABBBBBBBBBBAAA",
            "AABBBBBBBBBBBBAA",
            "AAAAAAAAAAAAAAAA",
            "................"
        )
    )

    private fun floatingRuneTomeFrames(): List<List<String>> = listOf(
        listOf(
            "................",
            "....BBBBBBBB....",
            "...BAAAAAAAAB...",
            "...BACCCCCAAB...",
            "...BAAAAAAAAB...",
            "...BAACCCAAAB...",
            "...BAAAAAAAAB...",
            "...BACCCCCAAB...",
            "...BAAAAAAAAB...",
            "....BBBBBBBB....",
            "......CCCC......",
            ".......CC.......",
            "................",
            "................",
            "................",
            "................"
        )
    )

    private fun frostPylonFrames(): List<List<String>> = listOf(
        listOf(
            "......CCCC......",
            ".....CBAAABC....",
            "....BBAAAAABB...",
            "....BAAAAAAAB...",
            "....BAACCCBAB...",
            "....BAAAAAAAB...",
            "...BBAAAAAAABB..",
            "...BAAACCCAAAB..",
            "...BAAAAAAAAAB..",
            "..BBAAAAAAAAABB.",
            "..BAAAAAAAAAAAB.",
            "..BAAACCCCAAAAB.",
            "..BAAAAAAAAAAAB.",
            ".BBBBBBBBBBBBBB.",
            ".AAAAAAAAAAAAAA.",
            "................"
        )
    )

    private fun basaltCragFrames(): List<List<String>> = listOf(
        listOf(
            "......AAAA......",
            ".....AABAAA.....",
            "....AABBBBAA....",
            "....AABCCBAA....",
            "...AAABBBBAAA...",
            "...AABBBBBAAA...",
            "..AAAABBBBAAAA..",
            "..AABBBBBBBBAA..",
            ".AAAAABCCBBAAAA.",
            ".AAABBBBBBBBAAA.",
            "AAAABBBBBBBBAAAA",
            "AABBBBBBBBBBBBAA",
            "AAAABBBBBBBAAAAA",
            "AAAAAAAAAAAAAAAA",
            "AAAAAAAAAAAAAAAA",
            "................"
        )
    )

    private fun emberLanternFrames(): List<List<String>> = listOf(
        listOf(
            ".......BB.......",
            "......BBBB......",
            "....BBBBBBBB....",
            "...BAAAAAAAAB...",
            "...BAACCCCABB...",
            "...BACCCCCCAB...",
            "...BACCCCCCAB...",
            "...BAACCCCABB...",
            "...BAAAAAAAAB...",
            "....BBBBBBBB....",
            "......BBBB......",
            ".......CC.......",
            ".......CC.......",
            "................",
            "................",
            "................"
        )
    )

    private fun forgeSmokestackFrames(): List<List<String>> = listOf(
        listOf(
            ".....CCCCCC.....",
            "......CCCC......",
            "....BBBBBBBB....",
            "....BAAAAAAB....",
            "....BAAAAAAB....",
            "....BACCCCAB....",
            "...BBAAAAAABB...",
            "...BAAAAAAAAB...",
            "...BAAAAAAAAB...",
            "...BACCCCCCAB...",
            "..BBAAAAAAAABB..",
            "..BAAAAAAAAAAB..",
            "..BAAAAAAAAAAB..",
            ".BBBBBBBBBBBBBB.",
            ".AAAAAAAAAAAAAA.",
            "................"
        )
    )

    private fun dataMonolithFrames(): List<List<String>> = listOf(
        listOf(
            ".....BBBBBB.....",
            "....BAAAAAAB....",
            "....BACCCCAB....",
            "....BAAAAAAB....",
            "....BACCCCAB....",
            "....BAAAAAAB....",
            "....BAAAAAAB....",
            "....BACCCCAB....",
            "....BAAAAAAB....",
            "....BAAAAAAB....",
            ".....BBBBBB.....",
            ".......CC.......",
            ".......CC.......",
            "................",
            "................",
            "................"
        )
    )

    private fun glowSporeCapFrames(): List<List<String>> = listOf(
        listOf(
            "....BBBBBBBB....",
            "..BBCCCCCCCCBB..",
            ".BCCCCCCCCCCCCB.",
            "BCCCBBCCCCBBCCCB",
            "BCCCCCCCCCCCCCCB",
            ".BBBBBBBBBBBBBB.",
            "......AAAA......",
            "......AAAA......",
            "......ACCA......",
            "......AAAA......",
            "......AAAA......",
            ".....AAAAAA.....",
            "....AAAAAAAA....",
            "................",
            "................",
            "................"
        )
    )

    private fun cyberRelayFrames(): List<List<String>> = listOf(
        listOf(
            ".......CC.......",
            "......CCCC......",
            ".......BB.......",
            ".....BBBBBB.....",
            "....BAAAAAAB....",
            "....BACCCCAB....",
            "...BBAAAAAABB...",
            "...BAAAAAAAAB...",
            "...BACCCCCCAB...",
            "..BBAAAAAAAABB..",
            "..BAAAAAAAAAAB..",
            "..BACCCCCCCCAB..",
            "..BAAAAAAAAAAB..",
            ".BBBBBBBBBBBBBB.",
            ".AAAAAAAAAAAAAA.",
            "................"
        )
    )
}

/**
 * Depth-based sprite parallax background scroller updated per frame inside [GameLoop].
 * Computes wrapped screen-space coordinates per layer from `cameraX * depthFactor`
 * and ambient drift modulated by biome weather/gravity parameters.
 */
class ParallaxBackgroundScroller {

    private var activeSignature: String = ""
    var layers: List<ParallaxSpriteLayer> = emptyList()
        private set
    var positionedSprites: List<PositionedParallaxSprite> = emptyList()
        private set
    private var ambientTimeSec: Float = 0f

    fun update(
        deltaSec: Float,
        cameraX: Float,
        biome: BiomeConfig,
        params: WorldGenSpawnParams,
        viewportWidth: Float,
        viewportHeight: Float
    ): List<PositionedParallaxSprite> {
        val dt = deltaSec.coerceIn(0f, PhysicsConstants.MAX_FRAME_DELTA_SEC)
        ambientTimeSec += dt

        val layerSig = biome.parallaxConfig.joinToString(",") {
            "${it.depthFactor}:${it.silhouetteType}:${it.tintHex}:${it.density}"
        }
        val signature = "${biome.name}|${params.weatherType}|${params.gravityScale < 0.9f}|${biome.palette.accentColor}|$layerSig"
        if (signature != activeSignature) {
            activeSignature = signature
            layers = ParallaxSpriteCatalog.buildLayers(biome, params)
        }

        if (viewportWidth <= 0f || viewportHeight <= 0f || layers.isEmpty()) {
            positionedSprites = emptyList()
            return positionedSprites
        }

        val windDrift = biome.physicsModifiers.windForceX * 0.12f * ambientTimeSec
        val items = ArrayList<PositionedParallaxSprite>(layers.size * 6)

        for (layer in layers) {
            val spriteSizePx = (16f * layer.baseScale * (viewportHeight / 720f).coerceIn(0.65f, 1.5f))
                .toInt()
                .coerceIn(24, 96)
            val span = layer.spacingPx
            val effectiveScroll = cameraX * layer.depthFactor - windDrift * layer.depthFactor
            val rawOffset = effectiveScroll % span
            val wrappedOffset = if (rawOffset < 0f) rawOffset + span else rawOffset
            val tileCount = (viewportWidth / span).toInt() + 2
            val startTileIndex = kotlin.math.floor((effectiveScroll / span).toDouble()).toInt()

            for (i in -1..tileCount) {
                val tileIdx = startTileIndex + i
                val screenX = i * span - wrappedOffset
                val verticalJitter = sin(tileIdx * 1.7 + layer.layerIndex * 2.3).toFloat() * 28f
                val bob = sin(ambientTimeSec * 1.5f + tileIdx * 0.9f).toFloat() * layer.bobAmplitudePx
                val screenY = (viewportHeight * layer.verticalBandFraction + verticalJitter + bob)
                    .coerceIn(32f, (viewportHeight - spriteSizePx - 24f).coerceAtLeast(32f))

                items += PositionedParallaxSprite(
                    cacheKey = layer.cacheKey,
                    matrix = layer.spriteMatrix,
                    depthFactor = layer.depthFactor,
                    screenX = screenX,
                    screenY = screenY,
                    widthPx = spriteSizePx,
                    heightPx = spriteSizePx,
                    alpha = layer.alpha
                )
            }
        }

        positionedSprites = items
        return items
    }

    fun drawLayers(drawScope: DrawScope) {
        if (positionedSprites.isEmpty()) return
        val compiledCache = HashMap<String, ImageBitmap>(4)
        for (item in positionedSprites) {
            val bitmap = compiledCache.getOrPut(item.cacheKey) {
                SpriteCompiler.getOrCompile(item.cacheKey, item.matrix).frames.first()
            }
            drawScope.drawImage(
                image = bitmap,
                dstOffset = IntOffset(item.screenX.toInt(), item.screenY.toInt()),
                dstSize = IntSize(item.widthPx, item.heightPx),
                alpha = item.alpha,
                filterQuality = FilterQuality.None
            )
        }
    }

    fun reset() {
        activeSignature = ""
        layers = emptyList()
        positionedSprites = emptyList()
        ambientTimeSec = 0f
    }
}

/**
 * Finite state machine phases governing dynamic NPC spawning inside [GameLoop].
 */
enum class NpcSpawnPhase {
    IDLE,
    EVALUATING_WORLD_PARAMS,
    SELECTING_ARCHETYPE,
    SPAWNING,
    SPAWNED,
    COOLDOWN
}

/**
 * Observable snapshot of the [NpcSpawnStateMachine].
 */
data class NpcSpawnerState(
    val phase: NpcSpawnPhase = NpcSpawnPhase.IDLE,
    val lastEvaluatedChunkIndex: Int = -1,
    val selectedArchetype: NpcArchetype? = null,
    val lastSpawnedNpcName: String? = null,
    val lastSpawnReason: String = "Awaiting sector entry",
    val cooldownRemainingSec: Float = 0f,
    val totalDynamicSpawns: Int = 0
)

/**
 * Simple deterministic state machine that evaluates current world-generation parameters
 * (chunk index, biome weather, gravity scale, enemy density, player HP/score, and ally status)
 * and selects/spawns an appropriate [NpcArchetype] (Guide, Merchant, Combat Companion, or Rival).
 */
class NpcSpawnStateMachine(
    private val maxNpcsPerChunk: Int = 3,
    private val spawnCooldownSec: Float = 14f
) {
    var state: NpcSpawnerState = NpcSpawnerState()
        private set

    fun reset() {
        state = NpcSpawnerState()
    }

    fun step(
        deltaSec: Float,
        params: WorldGenSpawnParams,
        onSpawnNpc: (NpcArchetype) -> RuntimeNpc?
    ): NpcSpawnerState {
        val dt = deltaSec.coerceAtLeast(0f)
        state = when (state.phase) {
            NpcSpawnPhase.IDLE -> {
                val enteredNewChunk = params.chunkIndex != state.lastEvaluatedChunkIndex
                val canReplenishSector = params.npcCountInChunk < maxNpcsPerChunk && params.elapsedTimeSec >= 2.5f
                if (enteredNewChunk || canReplenishSector) {
                    state.copy(
                        phase = NpcSpawnPhase.EVALUATING_WORLD_PARAMS,
                        lastEvaluatedChunkIndex = params.chunkIndex,
                        lastSpawnReason = "Evaluating ${params.biomeName} (#${params.chunkIndex + 1})"
                    )
                } else {
                    state
                }
            }

            NpcSpawnPhase.EVALUATING_WORLD_PARAMS -> {
                if (params.npcCountInChunk >= maxNpcsPerChunk) {
                    state.copy(
                        phase = NpcSpawnPhase.COOLDOWN,
                        cooldownRemainingSec = spawnCooldownSec,
                        lastSpawnReason = "Sector #${params.chunkIndex + 1} at NPC capacity (${params.npcCountInChunk}/$maxNpcsPerChunk)"
                    )
                } else {
                    state.copy(phase = NpcSpawnPhase.SELECTING_ARCHETYPE)
                }
            }

            NpcSpawnPhase.SELECTING_ARCHETYPE -> {
                val (archetype, reason) = selectBestArchetype(params)
                if (archetype == null) {
                    state.copy(
                        phase = NpcSpawnPhase.COOLDOWN,
                        cooldownRemainingSec = spawnCooldownSec,
                        lastSpawnReason = "No eligible NPC archetype for current parameters"
                    )
                } else {
                    state.copy(
                        phase = NpcSpawnPhase.SPAWNING,
                        selectedArchetype = archetype,
                        lastSpawnReason = reason
                    )
                }
            }

            NpcSpawnPhase.SPAWNING -> {
                val archetype = state.selectedArchetype
                val spawned = if (archetype != null) onSpawnNpc(archetype) else null
                if (spawned != null) {
                    state.copy(
                        phase = NpcSpawnPhase.SPAWNED,
                        lastSpawnedNpcName = spawned.name,
                        totalDynamicSpawns = state.totalDynamicSpawns + 1
                    )
                } else {
                    state.copy(
                        phase = NpcSpawnPhase.COOLDOWN,
                        cooldownRemainingSec = spawnCooldownSec,
                        lastSpawnReason = "Deferred spawn (no clear platform ahead)"
                    )
                }
            }

            NpcSpawnPhase.SPAWNED -> {
                state.copy(
                    phase = NpcSpawnPhase.COOLDOWN,
                    cooldownRemainingSec = spawnCooldownSec
                )
            }

            NpcSpawnPhase.COOLDOWN -> {
                if (params.chunkIndex > state.lastEvaluatedChunkIndex) {
                    state.copy(
                        phase = NpcSpawnPhase.IDLE,
                        cooldownRemainingSec = 0f
                    )
                } else {
                    val remaining = (state.cooldownRemainingSec - dt).coerceAtLeast(0f)
                    if (remaining <= 0f) {
                        state.copy(
                            phase = NpcSpawnPhase.IDLE,
                            cooldownRemainingSec = 0f
                        )
                    } else {
                        state.copy(cooldownRemainingSec = remaining)
                    }
                }
            }
        }
        return state
    }

    private fun selectBestArchetype(params: WorldGenSpawnParams): Pair<NpcArchetype?, String> {
        val hpRatio = if (params.playerMaxHp > 0) params.playerHp.toFloat() / params.playerMaxHp else 1f
        val eligible = NpcArchetypeCatalog.all.filter { archetype ->
            val rules = archetype.spawnRules
            params.chunkIndex >= rules.minChunkIndex &&
                (!rules.requiresNoCompanion || !params.hasActiveCompanion)
        }
        if (eligible.isEmpty()) return null to "No eligible archetypes"

        var bestArchetype: NpcArchetype? = null
        var bestScore = Float.NEGATIVE_INFINITY
        var bestReason = ""

        for (archetype in eligible) {
            val rules = archetype.spawnRules
            var weight = rules.baseWeight
            val reasons = mutableListOf<String>()

            if (params.weatherType in rules.preferredWeathers) {
                weight += 1.35f
                reasons += "${params.weatherType.name.lowercase()} weather"
            }

            when (archetype.kind) {
                NpcArchetypeKind.MERCHANT -> {
                    if (hpRatio <= 0.6f) {
                        weight += 1.85f
                        reasons += "low HP (${params.playerHp}/${params.playerMaxHp})"
                    } else if (params.playerScore >= rules.minPlayerScore) {
                        weight += 0.85f
                        reasons += "trade score (${params.playerScore})"
                    }
                }
                NpcArchetypeKind.COMBAT_COMPANION -> {
                    if (!params.hasActiveCompanion && params.enemyCountInChunk >= rules.minEnemiesInChunk) {
                        weight += 1.75f
                        reasons += "${params.enemyCountInChunk} enemies & no companion"
                    }
                }
                NpcArchetypeKind.RIVAL -> {
                    if (params.chunkIndex >= 1 && params.playerScore >= rules.minPlayerScore) {
                        weight += 1.3f
                        reasons += "sector #${params.chunkIndex + 1} score ${params.playerScore}"
                    }
                }
                NpcArchetypeKind.GUIDE -> {
                    if (params.chunkIndex == 0 || abs(params.gravityScale - 1.0f) >= 0.12f) {
                        weight += 1.15f
                        reasons += "gravity ${params.gravityScale}x / sector lore"
                    }
                }
            }

            if (state.selectedArchetype?.kind == archetype.kind) {
                weight -= 0.9f
            }

            if (weight > bestScore) {
                bestScore = weight
                bestArchetype = archetype
                bestReason = if (reasons.isNotEmpty()) {
                    "${archetype.kind.label}: ${reasons.joinToString(", ")}"
                } else {
                    "${archetype.kind.label}: sector patrol match"
                }
            }
        }

        return bestArchetype to bestReason
    }
}

/**
 * Core game loop composable backed by Compose [Canvas] and [withFrameNanos].
 * Computes clamped frame time deltas for smooth, frame-rate-independent motion,
 * advances [PlatformerEngine], updates depth-based biome sprite parallax scrolling
 * via [ParallaxBackgroundScroller], runs [NpcSpawnStateMachine], and renders the world.
 */
@Composable
fun GameLoop(
    engine: PlatformerEngine,
    isActive: Boolean,
    nearbyNpcId: String?,
    consumeInputSnapshot: () -> InputSnapshot,
    onHudSnapshot: (HudSnapshot) -> Unit,
    onNpcSpawnerState: (NpcSpawnerState) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val npcSpawner = remember(engine) { NpcSpawnStateMachine() }
    val parallaxScroller = remember(engine) { ParallaxBackgroundScroller() }
    var frameCounter by remember { mutableLongStateOf(0L) }
    var canvasWidth by remember { mutableFloatStateOf(1280f) }
    var canvasHeight by remember { mutableFloatStateOf(720f) }

    LaunchedEffect(isActive) {
        if (!isActive) {
            npcSpawner.reset()
            parallaxScroller.reset()
            onNpcSpawnerState(npcSpawner.state)
            return@LaunchedEffect
        }
        var lastNanos = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                if (lastNanos == 0L) lastNanos = frameNanos
                val rawDeltaSec = ((frameNanos - lastNanos) / 1_000_000_000.0).toFloat()
                val deltaSec = rawDeltaSec.coerceIn(0f, PhysicsConstants.MAX_FRAME_DELTA_SEC)
                lastNanos = frameNanos

                val input = consumeInputSnapshot()
                engine.stepFrame(
                    rawDeltaSec = deltaSec,
                    input = input,
                    viewportWidth = canvasWidth,
                    viewportHeight = canvasHeight
                )

                val worldParams = engine.buildWorldGenSpawnParams()
                val hudSnapshot = engine.buildHudSnapshot()
                if (!hudSnapshot.isPausedForDialogue && !hudSnapshot.isGameOver) {
                    parallaxScroller.update(
                        deltaSec = deltaSec,
                        cameraX = engine.camera.x,
                        biome = engine.currentBiome(),
                        params = worldParams,
                        viewportWidth = canvasWidth,
                        viewportHeight = canvasHeight
                    )

                    val prevPhase = npcSpawner.state.phase
                    val spawnerState = npcSpawner.step(
                        deltaSec = deltaSec,
                        params = worldParams,
                        onSpawnNpc = { archetype -> engine.spawnDynamicNpcFromArchetype(archetype) }
                    )
                    if (spawnerState.phase != prevPhase || frameCounter % 15L == 0L) {
                        onNpcSpawnerState(spawnerState)
                    }
                }

                frameCounter++
                if (frameCounter % 6L == 0L) {
                    onHudSnapshot(engine.buildHudSnapshot())
                }
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("game_world_canvas")
    ) {
        canvasWidth = size.width
        canvasHeight = size.height
        @Suppress("UNUSED_EXPRESSION")
        frameCounter
        if (isActive) {
            renderWorld(
                engine = engine,
                nearbyNpcId = nearbyNpcId,
                onDrawParallaxSprites = { parallaxScroller.drawLayers(this) }
            )
        }
    }
}

/**
 * Rectangular collision box (AABB) for the player character enabling precise
 * interaction with environmental obstacles, platforms, and NPCs.
 */
data class PlayerCollisionBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    val left: Float get() = x
    val right: Float get() = x + width
    val top: Float get() = y
    val bottom: Float get() = y + height

    fun overlaps(other: PlayerCollisionBox): Boolean {
        return left < other.right && right > other.left &&
               top < other.bottom && bottom > other.top
    }

    fun overlapsRect(ox: Float, oy: Float, ow: Float, oh: Float): Boolean {
        return left < (ox + ow) && right > ox &&
               top < (oy + oh) && bottom > oy
    }
}

/**
 * Environmental obstacle rectangle for collision.
 */
data class BasicObstacle(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    val left: Float get() = x
    val right: Float get() = x + width
    val top: Float get() = y
    val bottom: Float get() = y + height
}

/**
 * NPC interaction entity with positional bounds and dialogue prompt.
 */
data class BasicNpcObstacle(
    val id: String,
    val name: String,
    val x: Float,
    val y: Float,
    val width: Float = 36f,
    val height: Float = 48f,
    val openingLine: String
) {
    val left: Float get() = x
    val right: Float get() = x + width
    val top: Float get() = y
    val bottom: Float get() = y + height
}

/**
 * Lightweight state container for basic 2D player gravity physics and smooth delta-time motion.
 */
data class BasicPlayerGravityState(
    var x: Float = 120f,
    var y: Float = 200f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var onGround: Boolean = false,
    val width: Float = PhysicsConstants.PLAYER_WIDTH_PX,
    val height: Float = PhysicsConstants.PLAYER_HEIGHT_PX,
    var activeNpcInteraction: String? = null
) {
    fun collisionBox(offsetX: Float = 4f, offsetY: Float = 4f, insetWidth: Float = 8f, insetHeight: Float = 4f): PlayerCollisionBox {
        return PlayerCollisionBox(
            x = x + offsetX,
            y = y + offsetY,
            width = (width - insetWidth).coerceAtLeast(8f),
            height = (height - insetHeight).coerceAtLeast(8f)
        )
    }
}

/**
 * Advances basic player horizontal motion, vertical gravity physics, obstacle collision resolution,
 * and NPC interaction checks for a frame time [deltaSec].
 */
fun stepBasicPlayerGravityPhysics(
    state: BasicPlayerGravityState,
    deltaSec: Float,
    moveInputX: Float,
    jumpPressed: Boolean,
    groundY: Float,
    worldWidth: Float,
    obstacles: List<BasicObstacle> = emptyList(),
    npcs: List<BasicNpcObstacle> = emptyList()
) {
    val dt = deltaSec.coerceIn(0f, PhysicsConstants.MAX_FRAME_DELTA_SEC)
    if (dt <= 0f) return

    val targetVx = moveInputX.coerceIn(-1f, 1f) * PhysicsConstants.PLAYER_MOVE_SPEED
    val accel = if (state.onGround) PhysicsConstants.GROUND_ACCEL else PhysicsConstants.AIR_ACCEL
    if (moveInputX != 0f) {
        val diff = targetVx - state.vx
        val maxStep = accel * dt
        state.vx += diff.coerceIn(-maxStep, maxStep)
    } else {
        state.vx *= Math.pow(PhysicsConstants.BASE_FRICTION.toDouble(), (dt * 60f).toDouble()).toFloat()
        if (kotlin.math.abs(state.vx) < 2f) state.vx = 0f
    }

    if (jumpPressed && state.onGround) {
        state.vy = PhysicsConstants.JUMP_VELOCITY
        state.onGround = false
    }

    state.vy = (state.vy + PhysicsConstants.GRAVITY * dt)
        .coerceAtMost(PhysicsConstants.MAX_FALL_SPEED)

    // Horizontal movement & obstacle resolution
    state.x = (state.x + state.vx * dt).coerceIn(0f, (worldWidth - state.width).coerceAtLeast(0f))
    var pBox = state.collisionBox()
    for (obs in obstacles) {
        if (pBox.overlapsRect(obs.x, obs.y, obs.width, obs.height)) {
            if (state.vx > 0f) {
                state.x = obs.left - state.width + 4f
                state.vx = 0f
            } else if (state.vx < 0f) {
                state.x = obs.right - 4f
                state.vx = 0f
            }
            pBox = state.collisionBox()
        }
    }

    // Vertical movement & ground/obstacle landing resolution
    state.y += state.vy * dt
    pBox = state.collisionBox()
    var landed = false

    for (obs in obstacles) {
        if (pBox.overlapsRect(obs.x, obs.y, obs.width, obs.height)) {
            if (state.vy > 0f && pBox.bottom - state.vy * dt <= obs.top + 12f) {
                state.y = obs.top - state.height
                state.vy = 0f
                state.onGround = true
                landed = true
            } else if (state.vy < 0f && pBox.top - state.vy * dt >= obs.bottom - 12f) {
                state.y = obs.bottom
                state.vy = 0f
            }
            pBox = state.collisionBox()
        }
    }

    val maxY = (groundY - state.height).coerceAtLeast(0f)
    if (state.y >= maxY) {
        state.y = maxY
        state.vy = 0f
        state.onGround = true
        landed = true
    } else if (!landed) {
        state.onGround = false
    }

    // NPC Proximity Interaction check
    var nearbyNpcMsg: String? = null
    for (npc in npcs) {
        if (pBox.overlapsRect(npc.x - 24f, npc.y - 12f, npc.width + 48f, npc.height + 24f)) {
            nearbyNpcMsg = "${npc.name}: \"${npc.openingLine}\""
            break
        }
    }
    state.activeNpcInteraction = nearbyNpcMsg
}

/**
 * Standalone Compose Canvas game loop driving [stepBasicPlayerGravityPhysics] via [withFrameNanos].
 */
@Composable
fun BasicGravityGameLoop(
    moveInputX: Float,
    jumpJustPressed: Boolean,
    onJumpConsumed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playerState = remember { BasicPlayerGravityState() }
    val obstacles = remember {
        listOf(
            BasicObstacle("obs_1", 320f, 440f, 220f, 24f),
            BasicObstacle("obs_2", 640f, 340f, 240f, 24f)
        )
    }
    val npcs = remember {
        listOf(
            BasicNpcObstacle("npc_1", "Archivist Vex", 360f, 392f, 36f, 48f, "Seek the resonance shards beyond this gate.")
        )
    }
    var frameCounter by remember { mutableLongStateOf(0L) }
    var canvasWidth by remember { mutableFloatStateOf(1280f) }
    var groundY by remember { mutableFloatStateOf(560f) }

    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                if (lastNanos == 0L) lastNanos = frameNanos
                val deltaSec = ((frameNanos - lastNanos) / 1_000_000_000.0).toFloat()
                lastNanos = frameNanos

                stepBasicPlayerGravityPhysics(
                    state = playerState,
                    deltaSec = deltaSec,
                    moveInputX = moveInputX,
                    jumpPressed = jumpJustPressed,
                    groundY = groundY,
                    worldWidth = canvasWidth,
                    obstacles = obstacles,
                    npcs = npcs
                )
                if (jumpJustPressed) {
                    onJumpConsumed()
                }
                frameCounter++
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("basic_gravity_canvas")
    ) {
        @Suppress("UNUSED_EXPRESSION")
        frameCounter
        canvasWidth = size.width
        groundY = size.height * 0.78f

        drawRect(color = Color(0xFF0B1022), size = size)

        // Draw floor
        drawRect(
            color = Color(0xFF1F2A48),
            topLeft = Offset(0f, groundY),
            size = Size(size.width, size.height - groundY)
        )
        drawRect(
            color = Color(0xFF00F5D4),
            topLeft = Offset(0f, groundY),
            size = Size(size.width, 4f)
        )

        // Draw obstacles
        for (obs in obstacles) {
            drawRect(
                color = Color(0xFF334155),
                topLeft = Offset(obs.x, obs.y),
                size = Size(obs.width, obs.height)
            )
            drawRect(
                color = Color(0xFF38BDF8),
                topLeft = Offset(obs.x, obs.y),
                size = Size(obs.width, 3f)
            )
        }

        // Draw NPCs
        for (npc in npcs) {
            drawRect(
                color = Color(0xFF10B981),
                topLeft = Offset(npc.x, npc.y),
                size = Size(npc.width, npc.height)
            )
        }

        // Draw player body
        drawRect(
            color = Color(0xFF38BDF8),
            topLeft = Offset(playerState.x, playerState.y),
            size = Size(playerState.width, playerState.height)
        )

        // Draw player rectangular collision box (debug outline)
        val box = playerState.collisionBox()
        drawRect(
            color = Color(0xFFF43F5E),
            topLeft = Offset(box.x, box.y),
            size = Size(box.width, box.height),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
        )
    }
}
