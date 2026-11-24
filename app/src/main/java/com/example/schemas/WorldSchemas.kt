package com.example.schemas

import com.squareup.moshi.JsonClass

enum class WeatherType {
    NONE,
    ASH,
    RAIN,
    FIREFLIES,
    SNOW,
    SPORES,
    EMBER,
    DATA_SHARDS
}

enum class ParallaxSilhouetteType {
    CRYSTALLINE_SPIRES,
    RUINED_MEGASTRUCTURES,
    ORGANIC_TENDRILS,
    JAGGED_PEAKS,
    FLOATING_MONOLITHS,
    ARCHIVE_SHELVES
}

enum class WaveformTimbre {
    SAWTOOTH,
    SQUARE,
    TRIANGLE,
    SINE
}

enum class BehaviorType {
    PATROL,
    LEAPER,
    FLYING_SINE,
    CHARGER,
    PROJECTILE_SHOOTER
}

enum class PlatformKind {
    SOLID,
    ONE_WAY,
    CRUMBLING,
    BOUNCY
}

enum class HazardKind {
    SPIKES,
    LAVA_POOL,
    ACID_POOL,
    LASER_BEAM,
    VOID_RIFT
}

enum class CollectibleKind {
    SHARD,
    CORE_FRAGMENT,
    HEAL_ORB,
    MUTATION_RELIC
}

enum class NpcRole {
    GUIDE_MERCHANT,
    LOREKEEPER,
    COMPANION,
    RIVAL
}

enum class QuestObjectiveType {
    DEFEAT_ENEMIES,
    COLLECT_SHARDS,
    REACH_CHUNK,
    DEFEAT_RIVAL
}

enum class BuffType {
    JUMP_BOOST,
    SPEED_SURGE,
    SHIELD,
    MAGNET,
    LOW_GRAVITY,
    DOUBLE_SCORE
}

enum class ConsequenceType {
    NONE,
    GRANT_QUEST,
    GRANT_BUFF,
    MERCHANT_TRADE,
    RECRUIT_COMPANION,
    TRIGGER_RIVAL_DUEL,
    MUTATE_NEXT_CHUNK
}

@JsonClass(generateAdapter = true)
data class BiomePalette(
    val skyTop: String,
    val skyBottom: String,
    val fogColor: String,
    val platformFill: String,
    val platformEdge: String,
    val accentColor: String
)

@JsonClass(generateAdapter = true)
data class ParallaxLayerConfig(
    val depthFactor: Float,
    val silhouetteType: ParallaxSilhouetteType,
    val tintHex: String,
    val density: Float,
    val heightScale: Float
)

@JsonClass(generateAdapter = true)
data class PhysicsModifiers(
    val gravityScale: Float = 1.0f,
    val friction: Float = 0.84f,
    val windForceX: Float = 0f
)

@JsonClass(generateAdapter = true)
data class AudioParams(
    val bpm: Int,
    val rootNoteHz: Float,
    val scaleIntervals: List<Int>,
    val bassPattern: List<Int>,
    val leadArpPattern: List<Int>,
    val waveformTimbre: WaveformTimbre
)

@JsonClass(generateAdapter = true)
data class BiomeConfig(
    val name: String,
    val loreWhisper: String,
    val palette: BiomePalette,
    val weatherType: WeatherType,
    val parallaxConfig: List<ParallaxLayerConfig>,
    val physicsModifiers: PhysicsModifiers,
    val audioParams: AudioParams
)

@JsonClass(generateAdapter = true)
data class SpriteMatrix(
    val width: Int,
    val height: Int,
    val paletteMap: Map<String, String>,
    val frames: List<List<String>>
)

@JsonClass(generateAdapter = true)
data class Hitbox(
    val offsetX: Float,
    val offsetY: Float,
    val width: Float,
    val height: Float
)

@JsonClass(generateAdapter = true)
data class AttackPattern(
    val rangePx: Float,
    val cooldownMs: Int,
    val projectileSpeed: Float = 0f,
    val telegraphMs: Int = 250
)

@JsonClass(generateAdapter = true)
data class EntityArchetype(
    val id: String,
    val name: String,
    val behaviorType: BehaviorType,
    val speed: Float,
    val hp: Int,
    val hitbox: Hitbox,
    val attackPattern: AttackPattern,
    val spriteMatrix: SpriteMatrix
)

@JsonClass(generateAdapter = true)
data class PlatformDef(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val kind: PlatformKind = PlatformKind.SOLID
)

@JsonClass(generateAdapter = true)
data class HazardDef(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val kind: HazardKind,
    val damage: Int = 1,
    val spriteMatrix: SpriteMatrix? = null
)

@JsonClass(generateAdapter = true)
data class CollectibleDef(
    val id: String,
    val x: Float,
    val y: Float,
    val kind: CollectibleKind,
    val value: Int = 10,
    val spriteMatrix: SpriteMatrix? = null
)

@JsonClass(generateAdapter = true)
data class EnemySpawn(
    val id: String,
    val x: Float,
    val y: Float,
    val archetypeId: String,
    val patrolRangePx: Float = 160f
)

@JsonClass(generateAdapter = true)
data class NpcSpawn(
    val id: String,
    val name: String,
    val role: NpcRole,
    val personality: String,
    val openingLine: String,
    val x: Float,
    val y: Float,
    val disposition: Int = 0,
    val spriteMatrix: SpriteMatrix
)

@JsonClass(generateAdapter = true)
data class ChunkLayout(
    val chunkIndex: Int,
    val biome: BiomeConfig,
    val archetypes: List<EntityArchetype> = emptyList(),
    val platforms: List<PlatformDef>,
    val hazards: List<HazardDef>,
    val collectibles: List<CollectibleDef>,
    val enemySpawns: List<EnemySpawn>,
    val npcSpawns: List<NpcSpawn> = emptyList(),
    val environmentalStoryText: String
)
