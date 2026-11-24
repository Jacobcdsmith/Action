package com.example.ai

import com.example.schemas.AttackPattern
import com.example.schemas.AudioParams
import com.example.schemas.BehaviorType
import com.example.schemas.BiomeConfig
import com.example.schemas.BuffType
import com.example.schemas.ChunkLayout
import com.example.schemas.CollectibleDef
import com.example.schemas.ConsequenceType
import com.example.schemas.EnemySpawn
import com.example.schemas.EntityArchetype
import com.example.schemas.HazardDef
import com.example.schemas.Hitbox
import com.example.schemas.NpcRole
import com.example.schemas.NpcSpawn
import com.example.schemas.PlatformDef
import com.example.schemas.PlatformKind
import com.example.schemas.QuestObjectiveType
import com.example.schemas.SpriteMatrix
import com.example.schemas.WaveformTimbre
import com.example.schemas.WeatherType
import com.example.schemas.HazardKind
import com.example.schemas.CollectibleKind
import com.google.firebase.ai.type.Schema
import com.squareup.moshi.JsonClass

/**
 * Gemini structured JSON Schemas (Schema.obj) for Firebase AI Logic (`gemini-3.8-flash`):
 * 1. `chunkLayoutSchema`: biome, platforms, hazards, collectibles, enemy archetypes, AND npcSpawns.
 * 2. `npcDialogueResponseSchema`: branching conversation reply, suggested choices, and gameplay consequences.
 */
object GeminiWorldSchema {

    private val hexDesc = "6-digit hex color string like #1A2B3C"

    private val spriteMatrixSchema: Schema = Schema.obj(
        properties = mapOf(
            "width" to Schema.integer("Sprite pixel width, either 16 or 24"),
            "height" to Schema.integer("Sprite pixel height, either 16 or 24"),
            "paletteEntries" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "symbol" to Schema.string("Single non-dot ASCII character token, e.g. A, B, C"),
                        "hex" to Schema.string(hexDesc)
                    )
                ),
                description = "1 to 6 indexed palette colors"
            ),
            "frames" to Schema.array(
                items = Schema.array(
                    items = Schema.string("Row of pixel tokens of length width; '.' is transparent")
                ),
                description = "1 to 2 animation frames"
            )
        )
    )

    private val biomeConfigSchema: Schema = Schema.obj(
        properties = mapOf(
            "name" to Schema.string("Short evocative biome name"),
            "loreWhisper" to Schema.string("Diegetic environmental whisper under 100 characters"),
            "palette" to Schema.obj(
                properties = mapOf(
                    "skyTop" to Schema.string(hexDesc),
                    "skyBottom" to Schema.string(hexDesc),
                    "fogColor" to Schema.string(hexDesc),
                    "platformFill" to Schema.string(hexDesc),
                    "platformEdge" to Schema.string(hexDesc),
                    "accentColor" to Schema.string(hexDesc)
                )
            ),
            "weatherType" to Schema.enumeration(WeatherType.entries.map { it.name }),
            "parallaxConfig" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "depthFactor" to Schema.double("Scroll multiplier between 0.1 and 0.85"),
                        "silhouetteType" to Schema.enumeration(
                            com.example.schemas.ParallaxSilhouetteType.entries.map { it.name }
                        ),
                        "tintHex" to Schema.string(hexDesc),
                        "density" to Schema.double("Feature density between 0.2 and 1.0"),
                        "heightScale" to Schema.double("Relative layer height between 0.25 and 0.95")
                    )
                ),
                description = "Exactly 3 parallax layers ordered far-to-near"
            ),
            "physicsModifiers" to Schema.obj(
                properties = mapOf(
                    "gravityScale" to Schema.double("Gravity multiplier between 0.5 and 1.6"),
                    "friction" to Schema.double("Surface friction between 0.65 and 0.96"),
                    "windForceX" to Schema.double("Horizontal wind acceleration between -150 and 150")
                )
            ),
            "audioParams" to Schema.obj(
                properties = mapOf(
                    "bpm" to Schema.integer("Tempo between 75 and 165 BPM"),
                    "rootNoteHz" to Schema.double("Root note frequency in Hz between 55 and 220"),
                    "scaleIntervals" to Schema.array(
                        items = Schema.integer("Semitone offset from root, e.g. 0, 2, 3, 5, 7, 10")
                    ),
                    "bassPattern" to Schema.array(
                        items = Schema.integer("Scale index for 8-step bassline, -1 for rest")
                    ),
                    "leadArpPattern" to Schema.array(
                        items = Schema.integer("Scale index for 8-step lead arpeggio, -1 for rest")
                    ),
                    "waveformTimbre" to Schema.enumeration(WaveformTimbre.entries.map { it.name })
                )
            )
        )
    )

    private val entityArchetypeSchema: Schema = Schema.obj(
        properties = mapOf(
            "id" to Schema.string("Unique archetype identifier"),
            "name" to Schema.string("Creature name"),
            "behaviorType" to Schema.enumeration(BehaviorType.entries.map { it.name }),
            "speed" to Schema.double("Movement speed in world px/sec between 40 and 260"),
            "hp" to Schema.integer("Hit points between 1 and 6"),
            "hitbox" to Schema.obj(
                properties = mapOf(
                    "offsetX" to Schema.double("Horizontal hitbox inset"),
                    "offsetY" to Schema.double("Vertical hitbox inset"),
                    "width" to Schema.double("Hitbox width in px"),
                    "height" to Schema.double("Hitbox height in px")
                )
            ),
            "attackPattern" to Schema.obj(
                properties = mapOf(
                    "rangePx" to Schema.double("Engagement range in px"),
                    "cooldownMs" to Schema.integer("Attack cooldown in ms >= 400"),
                    "projectileSpeed" to Schema.double("Projectile speed in px/sec, 0 if melee"),
                    "telegraphMs" to Schema.integer("Wind-up telegraph duration in ms")
                )
            ),
            "spriteMatrix" to spriteMatrixSchema
        )
    )

    private val npcSpawnSchema: Schema = Schema.obj(
        properties = mapOf(
            "id" to Schema.string("Unique NPC id"),
            "name" to Schema.string("Evocative NPC name and title"),
            "role" to Schema.enumeration(NpcRole.entries.map { it.name }),
            "personality" to Schema.string("Short personality & motivation description"),
            "openingLine" to Schema.string("First greeting line spoken when player approaches"),
            "x" to Schema.double("Local chunk X coordinate placed cleanly on top of a SOLID platform"),
            "y" to Schema.double("World Y coordinate resting just above the platform (platform.y - 44)"),
            "disposition" to Schema.integer("Initial disposition from -50 (hostile) to +50 (friendly)"),
            "spriteMatrix" to spriteMatrixSchema
        )
    )

    val chunkLayoutSchema: Schema = Schema.obj(
        properties = mapOf(
            "chunkIndex" to Schema.integer("Sequential chunk index >= 0"),
            "biome" to biomeConfigSchema,
            "archetypes" to Schema.array(
                items = entityArchetypeSchema,
                description = "1 to 3 custom enemy archetypes for this chunk"
            ),
            "platforms" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "id" to Schema.string(),
                        "x" to Schema.double("Local chunk X coordinate in [0, 2320]"),
                        "y" to Schema.double("World Y coordinate in [240, 780]"),
                        "width" to Schema.double("Width in [96, 620]"),
                        "height" to Schema.double("Height in [20, 180]"),
                        "kind" to Schema.enumeration(PlatformKind.entries.map { it.name })
                    )
                ),
                description = "Ordered left-to-right traversal platforms spanning 0..2400px"
            ),
            "hazards" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "id" to Schema.string(),
                        "x" to Schema.double(),
                        "y" to Schema.double(),
                        "width" to Schema.double(),
                        "height" to Schema.double(),
                        "kind" to Schema.enumeration(HazardKind.entries.map { it.name }),
                        "damage" to Schema.integer("1 or 2")
                    )
                )
            ),
            "collectibles" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "id" to Schema.string(),
                        "x" to Schema.double(),
                        "y" to Schema.double(),
                        "kind" to Schema.enumeration(CollectibleKind.entries.map { it.name }),
                        "value" to Schema.integer()
                    )
                )
            ),
            "enemySpawns" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "id" to Schema.string(),
                        "x" to Schema.double(),
                        "y" to Schema.double(),
                        "archetypeId" to Schema.string(),
                        "patrolRangePx" to Schema.double()
                    )
                )
            ),
            "npcSpawns" to Schema.array(
                items = npcSpawnSchema,
                description = "1 to 2 contextual NPCs (GUIDE_MERCHANT, LOREKEEPER, COMPANION, or RIVAL)"
            ),
            "environmentalStoryText" to Schema.string("Short diegetic narrative line displayed on HUD")
        )
    )

    val npcDialogueResponseSchema: Schema = Schema.obj(
        properties = mapOf(
            "replyText" to Schema.string("In-character NPC response (1 to 3 evocative sentences)"),
            "dispositionDelta" to Schema.integer("Change in NPC attitude between -35 and +35"),
            "narrativeBeat" to Schema.string("Optional concise 1-sentence story memory note for the chapter log"),
            "suggestedChoices" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "id" to Schema.string("Short choice id"),
                        "label" to Schema.string("Player dialogue option text under 60 chars"),
                        "intentHint" to Schema.string("Short mechanical hint, e.g. Accept Quest, Trade 40 Score, Recruit, Challenge")
                    )
                ),
                description = "2 to 3 tactical dialogue choices for the player"
            ),
            "consequence" to Schema.obj(
                properties = mapOf(
                    "type" to Schema.enumeration(ConsequenceType.entries.map { it.name }),
                    "summary" to Schema.string("Player-facing toast describing what changed"),
                    "questTitle" to Schema.string("Quest title if type is GRANT_QUEST"),
                    "questObjective" to Schema.enumeration(QuestObjectiveType.entries.map { it.name }),
                    "questTargetCount" to Schema.integer("Target count for quest between 1 and 8"),
                    "questRewardScore" to Schema.integer("Score reward between 50 and 300"),
                    "buffType" to Schema.enumeration(BuffType.entries.map { it.name }),
                    "buffDurationSec" to Schema.double("Buff duration in seconds (15..45, or 0 for permanent)"),
                    "scoreCost" to Schema.integer("Score spent for MERCHANT_TRADE (0 if free)"),
                    "healAmount" to Schema.integer("HP restored (0..2)"),
                    "chunkMutationPrompt" to Schema.string("Directive for MUTATE_NEXT_CHUNK")
                ),
                optionalProperties = listOf(
                    "questTitle",
                    "questObjective",
                    "questTargetCount",
                    "questRewardScore",
                    "buffType",
                    "buffDurationSec",
                    "scoreCost",
                    "healAmount",
                    "chunkMutationPrompt"
                )
            )
        ),
        optionalProperties = listOf("narrativeBeat")
    )
}

@JsonClass(generateAdapter = true)
data class GeminiPaletteEntryDto(val symbol: String, val hex: String)

@JsonClass(generateAdapter = true)
data class GeminiSpriteMatrixDto(
    val width: Int,
    val height: Int,
    val paletteEntries: List<GeminiPaletteEntryDto>,
    val frames: List<List<String>>
) {
    fun toDomain(): SpriteMatrix = SpriteMatrix(
        width = width,
        height = height,
        paletteMap = paletteEntries.associate { it.symbol.take(1) to it.hex },
        frames = frames
    )
}

@JsonClass(generateAdapter = true)
data class GeminiEntityArchetypeDto(
    val id: String,
    val name: String,
    val behaviorType: BehaviorType,
    val speed: Float,
    val hp: Int,
    val hitbox: Hitbox,
    val attackPattern: AttackPattern,
    val spriteMatrix: GeminiSpriteMatrixDto
) {
    fun toDomain(): EntityArchetype = EntityArchetype(
        id = id,
        name = name,
        behaviorType = behaviorType,
        speed = speed,
        hp = hp,
        hitbox = hitbox,
        attackPattern = attackPattern,
        spriteMatrix = spriteMatrix.toDomain()
    )
}

@JsonClass(generateAdapter = true)
data class GeminiNpcSpawnDto(
    val id: String,
    val name: String,
    val role: NpcRole,
    val personality: String,
    val openingLine: String,
    val x: Float,
    val y: Float,
    val disposition: Int = 0,
    val spriteMatrix: GeminiSpriteMatrixDto
) {
    fun toDomain(): NpcSpawn = NpcSpawn(
        id = id,
        name = name,
        role = role,
        personality = personality,
        openingLine = openingLine,
        x = x,
        y = y,
        disposition = disposition,
        spriteMatrix = spriteMatrix.toDomain()
    )
}

@JsonClass(generateAdapter = true)
data class GeminiChunkLayoutDto(
    val chunkIndex: Int = 0,
    val biome: BiomeConfig,
    val archetypes: List<GeminiEntityArchetypeDto> = emptyList(),
    val platforms: List<PlatformDef>,
    val hazards: List<HazardDef> = emptyList(),
    val collectibles: List<CollectibleDef> = emptyList(),
    val enemySpawns: List<EnemySpawn> = emptyList(),
    val npcSpawns: List<GeminiNpcSpawnDto> = emptyList(),
    val environmentalStoryText: String
) {
    fun toDomain(): ChunkLayout = ChunkLayout(
        chunkIndex = chunkIndex,
        biome = biome,
        archetypes = archetypes.map { it.toDomain() },
        platforms = platforms,
        hazards = hazards,
        collectibles = collectibles,
        enemySpawns = enemySpawns,
        npcSpawns = npcSpawns.map { it.toDomain() },
        environmentalStoryText = environmentalStoryText
    )
}

@JsonClass(generateAdapter = true)
data class GeminiDialogueChoiceDto(
    val id: String,
    val label: String,
    val intentHint: String
)

@JsonClass(generateAdapter = true)
data class GeminiDialogueConsequenceDto(
    val type: ConsequenceType = ConsequenceType.NONE,
    val summary: String = "",
    val questTitle: String? = null,
    val questObjective: QuestObjectiveType? = null,
    val questTargetCount: Int? = null,
    val questRewardScore: Int? = null,
    val buffType: BuffType? = null,
    val buffDurationSec: Float? = null,
    val scoreCost: Int? = null,
    val healAmount: Int? = null,
    val chunkMutationPrompt: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiNpcDialogueResponseDto(
    val replyText: String,
    val dispositionDelta: Int = 0,
    val narrativeBeat: String? = null,
    val suggestedChoices: List<GeminiDialogueChoiceDto> = emptyList(),
    val consequence: GeminiDialogueConsequenceDto = GeminiDialogueConsequenceDto(
        type = ConsequenceType.NONE,
        summary = ""
    )
)
