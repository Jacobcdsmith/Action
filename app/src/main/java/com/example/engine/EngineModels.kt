package com.example.engine

import com.example.schemas.BiomeConfig
import com.example.schemas.ChunkLayout
import com.example.schemas.CollectibleKind
import com.example.schemas.EntityArchetype
import com.example.schemas.HazardKind
import com.example.schemas.NpcRole
import com.example.schemas.PlatformKind
import com.example.schemas.SpriteMatrix
import com.example.schemas.WeatherType

data class WorldGenSpawnParams(
    val chunkIndex: Int,
    val biomeName: String,
    val weatherType: WeatherType,
    val gravityScale: Float,
    val enemyCountInChunk: Int,
    val npcCountInChunk: Int,
    val playerHp: Int,
    val playerMaxHp: Int,
    val playerScore: Int,
    val hasActiveCompanion: Boolean,
    val playerWorldX: Float,
    val elapsedTimeSec: Float
)

data class InputSnapshot(
    val moveX: Float = 0f,
    val jumpHeld: Boolean = false,
    val jumpJustPressed: Boolean = false,
    val jumpJustReleased: Boolean = false,
    val dashJustPressed: Boolean = false
)

data class PlayerState(
    var x: Float = 96f,
    var y: Float = PhysicsConstants.GROUND_Y_PX - PhysicsConstants.PLAYER_HEIGHT_PX - 120f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var facing: Int = 1,
    var onGround: Boolean = false,
    var coyoteTimerMs: Float = 0f,
    var jumpBufferTimerMs: Float = 0f,
    var scaleX: Float = 1f,
    var scaleY: Float = 1f,
    var hp: Int = 5,
    var maxHp: Int = 5,
    var score: Int = 0,
    var invulnerableTimerMs: Float = 0f,
    var animTimeSec: Float = 0f,
    var dashCooldownMs: Float = 0f,
    var dashTimerMs: Float = 0f
)

data class RuntimePlatform(
    val id: String,
    val worldX: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val kind: PlatformKind,
    var crumbleTimerMs: Float = -1f,
    var respawningTimerMs: Float = 0f,
    var solid: Boolean = true
)

data class RuntimeHazard(
    val id: String,
    val worldX: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val kind: HazardKind,
    val damage: Int
)

data class RuntimeCollectible(
    val id: String,
    var worldX: Float,
    var y: Float,
    val kind: CollectibleKind,
    val value: Int,
    var collected: Boolean = false,
    val phaseOffset: Float = 0f
)

data class RuntimeEnemy(
    val id: String,
    val archetype: EntityArchetype,
    var worldX: Float,
    var y: Float,
    val originX: Float,
    val originY: Float,
    val patrolRangePx: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var facing: Int = -1,
    var hp: Int = archetype.hp,
    var onGround: Boolean = false,
    var attackCooldownTimerMs: Float = 0f,
    var stateTimerSec: Float = 0f,
    var hitFlashMs: Float = 0f,
    var alive: Boolean = true,
    val isRivalBoss: Boolean = false
)

data class RuntimeNpc(
    val id: String,
    val name: String,
    val role: NpcRole,
    val personality: String,
    val openingLine: String,
    var worldX: Float,
    var y: Float,
    var disposition: Int,
    val spriteMatrix: SpriteMatrix,
    var talkedTo: Boolean = false,
    var recruited: Boolean = false,
    var turnedHostile: Boolean = false,
    var bobPhase: Float = 0f
)

data class ActiveCompanionState(
    val id: String,
    val name: String,
    val spriteMatrix: SpriteMatrix,
    var worldX: Float,
    var y: Float,
    var facing: Int = 1,
    var attackCooldownMs: Float = 0f,
    var animTimeSec: Float = 0f
)

data class RuntimeProjectile(
    val id: String,
    var worldX: Float,
    var y: Float,
    val vx: Float,
    val vy: Float,
    val radius: Float = 7f,
    val damage: Int = 1,
    val colorHex: String,
    var lifeMs: Float = 2800f,
    var active: Boolean = true,
    val fromPlayerTeam: Boolean = false
)

data class ActiveChunk(
    val chunkIndex: Int,
    val startWorldX: Float,
    val layout: ChunkLayout,
    val source: ChunkSource,
    val autoRepairCount: Int,
    val platforms: MutableList<RuntimePlatform>,
    val hazards: MutableList<RuntimeHazard>,
    val collectibles: MutableList<RuntimeCollectible>,
    val enemies: MutableList<RuntimeEnemy>,
    val npcs: MutableList<RuntimeNpc>
)

enum class ChunkSource {
    AI_GENERATED,
    FALLBACK_PROCEDURAL
}

data class TelemetryTracker(
    var deaths: Int = 0,
    var enemiesDefeated: Int = 0,
    var damageTaken: Int = 0,
    var collectiblesGathered: Int = 0,
    var totalElapsedSec: Float = 0f,
    var totalAirTimeSec: Float = 0f,
    var maxDistancePx: Float = 0f,
    var speedSampleSum: Float = 0f,
    var speedSampleCount: Int = 0
) {
    val averageSpeed: Float
        get() = if (speedSampleCount == 0) 0f else speedSampleSum / speedSampleCount

    val airTimeRatio: Float
        get() = if (totalElapsedSec <= 0.01f) 0f else (totalAirTimeSec / totalElapsedSec).coerceIn(0f, 1f)
}

enum class EngineEvent {
    JUMP,
    LAND,
    COLLECT,
    STOMP_ENEMY,
    PLAYER_DAMAGE,
    PLAYER_DEATH,
    DASH,
    BIOME_ENTERED,
    COMPANION_BOLT,
    QUEST_COMPLETE
}

data class HudSnapshot(
    val isRunning: Boolean = false,
    val isGameOver: Boolean = false,
    val isPausedForDialogue: Boolean = false,
    val hp: Int = 5,
    val maxHp: Int = 5,
    val score: Int = 0,
    val currentChunkIndex: Int = 0,
    val currentBiomeName: String = "Initializing...",
    val loreWhisper: String = "",
    val storyText: String = "",
    val autoRepairsApplied: Int = 0,
    val activeChunkCount: Int = 0,
    val currentChunkSource: ChunkSource = ChunkSource.FALLBACK_PROCEDURAL,
    val averageSpeed: Float = 0f,
    val airTimeRatio: Float = 0f,
    val enemiesDefeated: Int = 0,
    val deaths: Int = 0,
    val currentBiome: BiomeConfig? = null,
    val nearbyNpc: RuntimeNpc? = null,
    val activeCompanionName: String? = null
)
