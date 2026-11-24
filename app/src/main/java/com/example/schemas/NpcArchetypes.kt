package com.example.schemas

/**
 * High-level NPC archetypes covering Guides, Merchants, Combat Companions, and Rivals.
 */
enum class NpcArchetypeKind(val label: String) {
    GUIDE("Guide"),
    MERCHANT("Merchant"),
    COMBAT_COMPANION("Combat Companion"),
    RIVAL("Rival");

    fun toNpcRole(): NpcRole = when (this) {
        GUIDE -> NpcRole.LOREKEEPER
        MERCHANT -> NpcRole.GUIDE_MERCHANT
        COMBAT_COMPANION -> NpcRole.COMPANION
        RIVAL -> NpcRole.RIVAL
    }
}

/**
 * World-generation parameter affinities used by the GameLoop NPC spawning state machine
 * to score and select which archetype should appear in the current sector.
 */
data class NpcSpawnRules(
    val minChunkIndex: Int = 0,
    val maxNpcsInChunk: Int = 3,
    val preferredWeathers: Set<WeatherType> = emptySet(),
    val prefersLowHp: Boolean = false,
    val minPlayerScore: Int = 0,
    val requiresNoCompanion: Boolean = false,
    val minEnemiesInChunk: Int = 0,
    val baseWeight: Float = 1.0f
)

/**
 * Typed behavioral configuration per NPC archetype.
 */
sealed interface NpcArchetypeSpec {
    data class GuideSpec(
        val loreFocus: String,
        val defaultQuestObjective: QuestObjectiveType,
        val questTargetCount: Int,
        val questRewardScore: Int,
        val waypointHints: Int
    ) : NpcArchetypeSpec

    data class MerchantSpec(
        val primaryBuff: BuffType,
        val buffDurationSec: Float,
        val tradeScoreCost: Int,
        val healAmount: Int,
        val discountOnLowHp: Boolean
    ) : NpcArchetypeSpec

    data class CombatCompanionSpec(
        val boltDamage: Int,
        val attackCooldownMs: Int,
        val engageRangePx: Float,
        val followOffsetPx: Float,
        val passiveBuff: BuffType?
    ) : NpcArchetypeSpec

    data class RivalSpec(
        val duelHp: Int,
        val duelSpeed: Float,
        val projectileSpeed: Float,
        val duelBountyScore: Int,
        val truceDispositionThreshold: Int
    ) : NpcArchetypeSpec
}

/**
 * Complete data class definition for a spawnable NPC archetype.
 */
data class NpcArchetype(
    val id: String,
    val name: String,
    val kind: NpcArchetypeKind,
    val personality: String,
    val openingLine: String,
    val baseDisposition: Int,
    val robeHex: String,
    val visorHex: String,
    val spawnRules: NpcSpawnRules,
    val spec: NpcArchetypeSpec
) {
    fun toNpcSpawn(
        chunkIndex: Int,
        slot: Int,
        x: Float,
        y: Float,
        edgeHex: String
    ): NpcSpawn {
        val frame0 = listOf(
            "....BBBBBBBB....",
            "...BCCCCCCCCB...",
            "..BCAAAAAAAAAB..",
            "..BCADDDDDDDAB..",
            "..BCADEEEEEDAB..",
            "..BCAAAAAAAAAB..",
            "...BCCCCCCCCB...",
            "....BCCCCCCB....",
            "..BBCCCCCCCCBB..",
            ".BCCCCCCCCCCCCB.",
            ".BC.CCCCCCCC.CB.",
            "....CCCCCCCC....",
            "....CCC..CCC....",
            "...BBB....BBB...",
            "..AAA......AAA..",
            "................"
        )
        val frame1 = listOf(
            "................",
            "....BBBBBBBB....",
            "...BCCCCCCCCB...",
            "..BCAAAAAAAAAB..",
            "..BCADDDDDDDAB..",
            "..BCADEEEEEDAB..",
            "..BCAAAAAAAAAB..",
            "...BCCCCCCCCB...",
            "..BBCCCCCCCCBB..",
            ".BCCCCCCCCCCCCB.",
            ".BC.CCCCCCCC.CB.",
            "....CCCCCCCC....",
            "....CCC..CCC....",
            "....BBB..BBB....",
            "...AAA....AAA...",
            "................"
        )

        return NpcSpawn(
            id = "arch_${id}_${chunkIndex}_$slot",
            name = name,
            role = kind.toNpcRole(),
            personality = personality,
            openingLine = openingLine,
            x = x,
            y = y,
            disposition = baseDisposition,
            spriteMatrix = SpriteMatrix(
                width = 16,
                height = 16,
                paletteMap = mapOf(
                    "A" to "#0F172A",
                    "B" to edgeHex,
                    "C" to robeHex,
                    "D" to "#F8FAFC",
                    "E" to visorHex
                ),
                frames = listOf(frame0, frame1)
            )
        )
    }
}

/**
 * Curated archetype definitions across Guides, Merchants, Combat Companions, and Rivals.
 */
object NpcArchetypeCatalog {

    val guides: List<NpcArchetype> = listOf(
        NpcArchetype(
            id = "guide_orin",
            name = "Archivist Orin",
            kind = NpcArchetypeKind.GUIDE,
            personality = "An ancient memory-scribe weaving prophecies that physically reshape upcoming sectors.",
            openingLine = "Every step you take rewrites the archive. Tell me what fate awaits beyond this bridge.",
            baseDisposition = 30,
            robeHex = "#8B5CF6",
            visorHex = "#E879F9",
            spawnRules = NpcSpawnRules(
                minChunkIndex = 0,
                preferredWeathers = setOf(WeatherType.SNOW, WeatherType.DATA_SHARDS, WeatherType.FIREFLIES),
                baseWeight = 1.2f
            ),
            spec = NpcArchetypeSpec.GuideSpec(
                loreFocus = "Sector anomalies & biome prophecies",
                defaultQuestObjective = QuestObjectiveType.REACH_CHUNK,
                questTargetCount = 2,
                questRewardScore = 150,
                waypointHints = 2
            )
        ),
        NpcArchetype(
            id = "guide_lyra",
            name = "Pathfinder Lyra",
            kind = NpcArchetypeKind.GUIDE,
            personality = "A storm-mapping scout who tracks shifting gravity currents and hidden shard caches.",
            openingLine = "The horizon is fracturing ahead—want me to chart a safer route or trigger a surge?",
            baseDisposition = 25,
            robeHex = "#6366F1",
            visorHex = "#38BDF8",
            spawnRules = NpcSpawnRules(
                minChunkIndex = 0,
                preferredWeathers = setOf(WeatherType.ASH, WeatherType.EMBER, WeatherType.RAIN),
                baseWeight = 1.1f
            ),
            spec = NpcArchetypeSpec.GuideSpec(
                loreFocus = "Gravity currents & traversal routes",
                defaultQuestObjective = QuestObjectiveType.COLLECT_SHARDS,
                questTargetCount = 4,
                questRewardScore = 140,
                waypointHints = 3
            )
        )
    )

    val merchants: List<NpcArchetype> = listOf(
        NpcArchetype(
            id = "merchant_vesper",
            name = "Vesper the Quartermaster",
            kind = NpcArchetypeKind.MERCHANT,
            personality = "A shrewd clockwork tinkerer trading arcane buffs and repairs for resonance score.",
            openingLine = "Hold, runner! My satchel hums with spare boosters—or perhaps you need a field patch?",
            baseDisposition = 25,
            robeHex = "#0EA5E9",
            visorHex = "#00F5D4",
            spawnRules = NpcSpawnRules(
                minChunkIndex = 0,
                prefersLowHp = true,
                minPlayerScore = 20,
                baseWeight = 1.15f
            ),
            spec = NpcArchetypeSpec.MerchantSpec(
                primaryBuff = BuffType.JUMP_BOOST,
                buffDurationSec = 35f,
                tradeScoreCost = 30,
                healAmount = 1,
                discountOnLowHp = true
            )
        ),
        NpcArchetype(
            id = "merchant_solis",
            name = "Tinkerer Solis",
            kind = NpcArchetypeKind.MERCHANT,
            personality = "An eccentric relic broker specializing in magnetic rigs and score multipliers.",
            openingLine = "Shards slipping past you? Let's calibrate your magnet coils for a modest fee.",
            baseDisposition = 20,
            robeHex = "#0284C7",
            visorHex = "#FACC15",
            spawnRules = NpcSpawnRules(
                minChunkIndex = 1,
                prefersLowHp = true,
                minPlayerScore = 30,
                preferredWeathers = setOf(WeatherType.SPORES, WeatherType.FIREFLIES),
                baseWeight = 1.0f
            ),
            spec = NpcArchetypeSpec.MerchantSpec(
                primaryBuff = BuffType.MAGNET,
                buffDurationSec = 30f,
                tradeScoreCost = 30,
                healAmount = 2,
                discountOnLowHp = true
            )
        )
    )

    val companions: List<NpcArchetype> = listOf(
        NpcArchetype(
            id = "companion_pip09",
            name = "Pip-09 Drone Sprite",
            kind = NpcArchetypeKind.COMBAT_COMPANION,
            personality = "A loyal hovering sentinel eager to escort travelers and blast hostile anomalies.",
            openingLine = "Chirp-chirp! Link my tether to yours and I'll cover your flank across the ruins!",
            baseDisposition = 40,
            robeHex = "#10B981",
            visorHex = "#4ADE80",
            spawnRules = NpcSpawnRules(
                minChunkIndex = 0,
                requiresNoCompanion = true,
                minEnemiesInChunk = 1,
                baseWeight = 1.25f
            ),
            spec = NpcArchetypeSpec.CombatCompanionSpec(
                boltDamage = 1,
                attackCooldownMs = 1350,
                engageRangePx = 340f,
                followOffsetPx = 52f,
                passiveBuff = BuffType.SHIELD
            )
        ),
        NpcArchetype(
            id = "companion_kora",
            name = "Aegis Sentinel Kora",
            kind = NpcArchetypeKind.COMBAT_COMPANION,
            personality = "A battle-scarred ward unit built to shield runners in high-threat sectors.",
            openingLine = "Hostile signatures spiking ahead. Authorize escort link and I'll suppress them.",
            baseDisposition = 35,
            robeHex = "#059669",
            visorHex = "#34D399",
            spawnRules = NpcSpawnRules(
                minChunkIndex = 1,
                requiresNoCompanion = true,
                minEnemiesInChunk = 2,
                preferredWeathers = setOf(WeatherType.EMBER, WeatherType.ASH, WeatherType.DATA_SHARDS),
                baseWeight = 1.15f
            ),
            spec = NpcArchetypeSpec.CombatCompanionSpec(
                boltDamage = 2,
                attackCooldownMs = 1150,
                engageRangePx = 380f,
                followOffsetPx = 56f,
                passiveBuff = BuffType.SPEED_SURGE
            )
        )
    )

    val rivals: List<NpcArchetype> = listOf(
        NpcArchetype(
            id = "rival_kaelen",
            name = "Kaelen the Ash-Runner",
            kind = NpcArchetypeKind.RIVAL,
            personality = "A proud rival wanderer testing travelers—can be bargained with, won over, or dueled.",
            openingLine = "You dare race my horizon? Convince me you're worthy, or draw sparks right here.",
            baseDisposition = -20,
            robeHex = "#EA580C",
            visorHex = "#FF4D6D",
            spawnRules = NpcSpawnRules(
                minChunkIndex = 1,
                minPlayerScore = 50,
                preferredWeathers = setOf(WeatherType.EMBER, WeatherType.ASH),
                baseWeight = 1.05f
            ),
            spec = NpcArchetypeSpec.RivalSpec(
                duelHp = 4,
                duelSpeed = 165f,
                projectileSpeed = 260f,
                duelBountyScore = 180,
                truceDispositionThreshold = 15
            )
        ),
        NpcArchetype(
            id = "rival_vex",
            name = "Vex of the Void Track",
            kind = NpcArchetypeKind.RIVAL,
            personality = "A razor-tongued speedrunner who respects only bold wagers and clean aerial combos.",
            openingLine = "Too slow for this sector, runner. Match my pace in a trial or step aside.",
            baseDisposition = -15,
            robeHex = "#DC2626",
            visorHex = "#FB923C",
            spawnRules = NpcSpawnRules(
                minChunkIndex = 2,
                minPlayerScore = 90,
                preferredWeathers = setOf(WeatherType.DATA_SHARDS, WeatherType.RAIN),
                baseWeight = 1.0f
            ),
            spec = NpcArchetypeSpec.RivalSpec(
                duelHp = 5,
                duelSpeed = 185f,
                projectileSpeed = 290f,
                duelBountyScore = 220,
                truceDispositionThreshold = 20
            )
        )
    )

    val all: List<NpcArchetype> = guides + merchants + companions + rivals

    fun byKind(kind: NpcArchetypeKind): List<NpcArchetype> =
        all.filter { it.kind == kind }

    fun byId(id: String): NpcArchetype? =
        all.firstOrNull { it.id == id }
}
