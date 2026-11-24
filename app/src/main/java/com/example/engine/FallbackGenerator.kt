package com.example.engine

import com.example.schemas.AttackPattern
import com.example.schemas.AudioParams
import com.example.schemas.BehaviorType
import com.example.schemas.BiomeConfig
import com.example.schemas.BiomePalette
import com.example.schemas.ChunkLayout
import com.example.schemas.CollectibleDef
import com.example.schemas.CollectibleKind
import com.example.schemas.EnemySpawn
import com.example.schemas.EntityArchetype
import com.example.schemas.HazardDef
import com.example.schemas.HazardKind
import com.example.schemas.Hitbox
import com.example.schemas.NpcRole
import com.example.schemas.NpcSpawn
import com.example.schemas.ParallaxLayerConfig
import com.example.schemas.ParallaxSilhouetteType
import com.example.schemas.PhysicsModifiers
import com.example.schemas.PlatformDef
import com.example.schemas.PlatformKind
import com.example.schemas.SpriteMatrix
import com.example.schemas.WaveformTimbre
import com.example.schemas.WeatherType
import kotlin.random.Random

/**
 * Deterministic procedural fallback generator seeded by the active biome and chunk index.
 * Spawns platforms, hazards, collectibles, enemy archetypes, AND contextual NPCs across
 * all 3 roles (Guide/Merchant/Lorekeeper, Companion, Rival) with 16x16 pixel matrices.
 */
object FallbackGenerator {

    fun defaultBiomeForSeed(promptSeed: String): BiomeConfig {
        val lower = promptSeed.lowercase()
        return when {
            lower.contains("glacier") || lower.contains("ice") || lower.contains("library") -> BiomeConfig(
                name = "Glacier Archive",
                loreWhisper = "Frozen pages hum beneath prism-lit vaults.",
                palette = BiomePalette(
                    skyTop = "#0C1B33",
                    skyBottom = "#23496D",
                    fogColor = "#5998C5",
                    platformFill = "#1C3554",
                    platformEdge = "#8AE1FC",
                    accentColor = "#A8F9FF"
                ),
                weatherType = WeatherType.SNOW,
                parallaxConfig = listOf(
                    ParallaxLayerConfig(0.14f, ParallaxSilhouetteType.JAGGED_PEAKS, "#173252", 0.55f, 0.72f),
                    ParallaxLayerConfig(0.32f, ParallaxSilhouetteType.ARCHIVE_SHELVES, "#224B70", 0.65f, 0.58f),
                    ParallaxLayerConfig(0.56f, ParallaxSilhouetteType.CRYSTALLINE_SPIRES, "#2D648E", 0.75f, 0.44f)
                ),
                physicsModifiers = PhysicsModifiers(gravityScale = 0.94f, friction = 0.90f, windForceX = -18f),
                audioParams = AudioParams(
                    bpm = 108,
                    rootNoteHz = 110f,
                    scaleIntervals = listOf(0, 2, 3, 5, 7, 8, 10),
                    bassPattern = listOf(0, -1, 0, 4, 2, -1, 5, 4),
                    leadArpPattern = listOf(0, 2, 4, 6, 4, 2, 5, 3),
                    waveformTimbre = WaveformTimbre.TRIANGLE
                )
            )
            lower.contains("lava") || lower.contains("volcan") || lower.contains("ember") -> BiomeConfig(
                name = "Cinder Foundry",
                loreWhisper = "Basalt bridges creak above the molten tide.",
                palette = BiomePalette(
                    skyTop = "#1D090C",
                    skyBottom = "#541914",
                    fogColor = "#B83B1D",
                    platformFill = "#2D181A",
                    platformEdge = "#FF6B35",
                    accentColor = "#FFD166"
                ),
                weatherType = WeatherType.EMBER,
                parallaxConfig = listOf(
                    ParallaxLayerConfig(0.15f, ParallaxSilhouetteType.JAGGED_PEAKS, "#381213", 0.6f, 0.75f),
                    ParallaxLayerConfig(0.34f, ParallaxSilhouetteType.RUINED_MEGASTRUCTURES, "#4C1918", 0.7f, 0.6f),
                    ParallaxLayerConfig(0.58f, ParallaxSilhouetteType.CRYSTALLINE_SPIRES, "#6E231D", 0.65f, 0.42f)
                ),
                physicsModifiers = PhysicsModifiers(gravityScale = 1.06f, friction = 0.82f, windForceX = 24f),
                audioParams = AudioParams(
                    bpm = 132,
                    rootNoteHz = 82.41f,
                    scaleIntervals = listOf(0, 1, 4, 5, 7, 8, 10),
                    bassPattern = listOf(0, 0, -1, 3, 1, 0, -1, 4),
                    leadArpPattern = listOf(0, 1, 2, 4, 6, 4, 2, 1),
                    waveformTimbre = WaveformTimbre.SAWTOOTH
                )
            )
            else -> BiomeConfig(
                name = "Neon Mycelium",
                loreWhisper = "Clockwork spores drift between rusted pylons.",
                palette = BiomePalette(
                    skyTop = "#0C0A1E",
                    skyBottom = "#1D2448",
                    fogColor = "#3B2E6B",
                    platformFill = "#1C2541",
                    platformEdge = "#00F5D4",
                    accentColor = "#FF4D8D"
                ),
                weatherType = WeatherType.FIREFLIES,
                parallaxConfig = listOf(
                    ParallaxLayerConfig(0.15f, ParallaxSilhouetteType.RUINED_MEGASTRUCTURES, "#161B3B", 0.6f, 0.78f),
                    ParallaxLayerConfig(0.35f, ParallaxSilhouetteType.ORGANIC_TENDRILS, "#23295A", 0.72f, 0.6f),
                    ParallaxLayerConfig(0.60f, ParallaxSilhouetteType.FLOATING_MONOLITHS, "#2C3573", 0.68f, 0.42f)
                ),
                physicsModifiers = PhysicsModifiers(gravityScale = 1.0f, friction = 0.84f, windForceX = 0f),
                audioParams = AudioParams(
                    bpm = 122,
                    rootNoteHz = 98f,
                    scaleIntervals = listOf(0, 2, 3, 5, 7, 9, 10),
                    bassPattern = listOf(0, -1, 0, 2, 3, -1, 4, 2),
                    leadArpPattern = listOf(0, 2, 4, 5, 6, 4, 3, 2),
                    waveformTimbre = WaveformTimbre.SQUARE
                )
            )
        }
    }

    fun generateChunk(
        chunkIndex: Int,
        biomeTemplate: BiomeConfig,
        seedSalt: Int = 1337
    ): ChunkLayout {
        val rng = Random(chunkIndex * 7919 + seedSalt)
        val platforms = mutableListOf<PlatformDef>()
        val hazards = mutableListOf<HazardDef>()
        val collectibles = mutableListOf<CollectibleDef>()
        val spawns = mutableListOf<EnemySpawn>()
        val npcSpawns = mutableListOf<NpcSpawn>()

        // Entry deck
        var cursorX = 0f
        var currentY = PhysicsConstants.GROUND_Y_PX
        val entryWidth = 300f
        val entryPlat = PlatformDef(
            id = "fb_plat_${chunkIndex}_0",
            x = cursorX,
            y = currentY,
            width = entryWidth,
            height = 180f,
            kind = PlatformKind.SOLID
        )
        platforms += entryPlat
        cursorX += entryWidth

        var platCounter = 1
        while (cursorX < PhysicsConstants.CHUNK_WIDTH_PX - 260f) {
            val gap = rng.nextInt(95, 195).toFloat()
            val deltaY = rng.nextInt(-95, 85).toFloat()
            currentY = (currentY + deltaY).coerceIn(360f, PhysicsConstants.GROUND_Y_PX)
            val width = rng.nextInt(160, 320).toFloat()
            val nextX = cursorX + gap
            if (nextX + width > PhysicsConstants.CHUNK_WIDTH_PX - 120f) break

            val kind = when {
                platCounter % 5 == 0 -> PlatformKind.BOUNCY
                platCounter % 3 == 0 -> PlatformKind.ONE_WAY
                else -> PlatformKind.SOLID
            }

            platforms += PlatformDef(
                id = "fb_plat_${chunkIndex}_$platCounter",
                x = nextX,
                y = currentY,
                width = width,
                height = if (kind == PlatformKind.ONE_WAY) 24f else 180f,
                kind = kind
            )

            collectibles += CollectibleDef(
                id = "fb_col_${chunkIndex}_$platCounter",
                x = nextX + width * 0.5f,
                y = currentY - 46f,
                kind = if (platCounter % 4 == 0) CollectibleKind.CORE_FRAGMENT else CollectibleKind.SHARD,
                value = if (platCounter % 4 == 0) 25 else 10
            )

            if (kind == PlatformKind.SOLID && width >= 240f && rng.nextFloat() < 0.35f) {
                hazards += HazardDef(
                    id = "fb_haz_${chunkIndex}_$platCounter",
                    x = nextX + width * 0.38f,
                    y = currentY - 18f,
                    width = 56f,
                    height = 18f,
                    kind = if (biomeTemplate.weatherType == WeatherType.EMBER) HazardKind.LAVA_POOL else HazardKind.SPIKES,
                    damage = 1
                )
            } else if (width >= 180f && rng.nextFloat() < 0.55f) {
                val archetypeId = if (platCounter % 2 == 0) "fb_drone_$chunkIndex" else "fb_stalker_$chunkIndex"
                spawns += EnemySpawn(
                    id = "fb_enemy_${chunkIndex}_$platCounter",
                    x = nextX + width * 0.55f,
                    y = currentY - 38f,
                    archetypeId = archetypeId,
                    patrolRangePx = (width * 0.35f).coerceAtLeast(48f)
                )
            }

            cursorX = nextX + width
            platCounter++
        }

        // Exit bridge
        val exitPlat = PlatformDef(
            id = "fb_plat_${chunkIndex}_exit",
            x = (PhysicsConstants.CHUNK_WIDTH_PX - 240f).coerceAtLeast(cursorX + 90f)
                .coerceAtMost(PhysicsConstants.CHUNK_WIDTH_PX - 170f),
            y = PhysicsConstants.GROUND_Y_PX,
            width = 240f,
            height = 180f,
            kind = PlatformKind.SOLID
        )
        platforms += exitPlat

        // Spawn 2 contextual NPCs per chunk cycling across all 3 archetypes
        val primaryAnchor = entryPlat
        val midSolid = platforms.firstOrNull { it.id != entryPlat.id && it.id != exitPlat.id && it.kind == PlatformKind.SOLID }
            ?: exitPlat

        val primaryRole = when (chunkIndex % 3) {
            0 -> NpcRole.GUIDE_MERCHANT
            1 -> NpcRole.COMPANION
            else -> NpcRole.LOREKEEPER
        }
        npcSpawns += createFallbackNpc(
            chunkIndex = chunkIndex,
            slot = 0,
            role = primaryRole,
            x = primaryAnchor.x + 195f,
            y = primaryAnchor.y - 46f,
            accentHex = biomeTemplate.palette.accentColor,
            edgeHex = biomeTemplate.palette.platformEdge
        )

        val secondaryRole = when (chunkIndex % 3) {
            0 -> NpcRole.COMPANION
            1 -> NpcRole.RIVAL
            else -> NpcRole.RIVAL
        }
        npcSpawns += createFallbackNpc(
            chunkIndex = chunkIndex,
            slot = 1,
            role = secondaryRole,
            x = midSolid.x + midSolid.width * 0.45f,
            y = midSolid.y - 46f,
            accentHex = biomeTemplate.palette.accentColor,
            edgeHex = biomeTemplate.palette.platformEdge
        )

        val archetypes = listOf(
            createFallbackStalker(chunkIndex, biomeTemplate.palette.accentColor),
            createFallbackDrone(chunkIndex, biomeTemplate.palette.platformEdge)
        )

        return ChunkLayout(
            chunkIndex = chunkIndex,
            biome = biomeTemplate,
            archetypes = archetypes,
            platforms = platforms,
            hazards = hazards,
            collectibles = collectibles,
            enemySpawns = spawns,
            npcSpawns = npcSpawns,
            environmentalStoryText = "Sector ${chunkIndex + 1} — ${biomeTemplate.loreWhisper}"
        )
    }

    private fun createFallbackNpc(
        chunkIndex: Int,
        slot: Int,
        role: NpcRole,
        x: Float,
        y: Float,
        accentHex: String,
        edgeHex: String
    ): NpcSpawn {
        val (name, personality, opening, disposition, robeHex, visorHex) = when (role) {
            NpcRole.GUIDE_MERCHANT -> NpcSeedSpec(
                name = "Vesper the Quartermaster",
                personality = "A shrewd clockwork tinkerer trading arcane buffs and sector lore for resonance shards.",
                opening = "Hold, runner! My satchel hums with spare boosters—or perhaps you seek a bounty?",
                disposition = 25,
                robeHex = "#0EA5E9",
                visorHex = "#00F5D4"
            )
            NpcRole.LOREKEEPER -> NpcSeedSpec(
                name = "Archivist Orin",
                personality = "An ancient memory-scribe weaving prophecies that physically reshape upcoming sectors.",
                opening = "Every step you take rewrites the archive. Tell me what fate awaits beyond this bridge.",
                disposition = 30,
                robeHex = "#8B5CF6",
                visorHex = "#E879F9"
            )
            NpcRole.COMPANION -> NpcSeedSpec(
                name = "Pip-09 Drone Sprite",
                personality = "A loyal hovering sentinel eager to escort travelers and blast hostile anomalies.",
                opening = "Chirp-chirp! Link my tether to yours and I'll cover your flank across the ruins!",
                disposition = 40,
                robeHex = "#10B981",
                visorHex = "#4ADE80"
            )
            NpcRole.RIVAL -> NpcSeedSpec(
                name = "Kaelen the Ash-Runner",
                personality = "A proud rival wanderer testing travelers—can be bargained with, won over, or dueled.",
                opening = "You dare race my horizon? Convince me you're worthy, or draw sparks right here.",
                disposition = -20,
                robeHex = "#EA580C",
                visorHex = "#FF4D6D"
            )
        }

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
            id = "fb_npc_${chunkIndex}_$slot",
            name = name,
            role = role,
            personality = personality,
            openingLine = opening,
            x = x,
            y = y,
            disposition = disposition,
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

    private data class NpcSeedSpec(
        val name: String,
        val personality: String,
        val opening: String,
        val disposition: Int,
        val robeHex: String,
        val visorHex: String
    )

    private fun createFallbackStalker(chunkIndex: Int, accentHex: String): EntityArchetype {
        val frame0 = listOf(
            "....AAAAAA......",
            "...AABBBBAA.....",
            "..AABCCCCCAA....",
            "..ABCCDDCCBA....",
            "..ABCCDDCCBA....",
            "..AABBCCBBAA....",
            "...AAEEEEAA.....",
            "..AAEAAAAEAA....",
            "..AA.AAAA.AA....",
            ".....AAAA.......",
            "....AA..AA......",
            "...AA....AA.....",
            "...AA....AA.....",
            "..BB......BB....",
            "................",
            "................"
        )
        val frame1 = listOf(
            "................",
            "....AAAAAA......",
            "...AABBBBAA.....",
            "..AABCCCCCAA....",
            "..ABCCDDCCBA....",
            "..ABCCDDCCBA....",
            "..AABBCCBBAA....",
            "...AAEEEEAA.....",
            "..AAEAAAAEAA....",
            ".....AAAA.......",
            "....AA..AA......",
            "....AA..AA......",
            "...BB....BB.....",
            "................",
            "................",
            "................"
        )
        return EntityArchetype(
            id = "fb_stalker_$chunkIndex",
            name = "Clockwork Stalker",
            behaviorType = BehaviorType.PATROL,
            speed = 95f,
            hp = 2,
            hitbox = Hitbox(4f, 4f, 32f, 36f),
            attackPattern = AttackPattern(rangePx = 180f, cooldownMs = 1100, projectileSpeed = 0f, telegraphMs = 200),
            spriteMatrix = SpriteMatrix(
                width = 16,
                height = 16,
                paletteMap = mapOf(
                    "A" to "#1B1F3B",
                    "B" to "#4B558A",
                    "C" to accentHex,
                    "D" to "#FFF3B0",
                    "E" to "#FF4D6D"
                ),
                frames = listOf(frame0, frame1)
            )
        )
    }

    private fun createFallbackDrone(chunkIndex: Int, accentHex: String): EntityArchetype {
        val frame0 = listOf(
            "......AAAA......",
            "....AABBBBAA....",
            "..AABCCCCCCBAA..",
            ".AABCCDDDDCCBAA.",
            ".ABCCDEEEEDCCBA.",
            ".AABCCDDDDCCBAA.",
            "..AABCCCCCCBAA..",
            "....AABBBBAA....",
            "...AA......AA...",
            "..AA........AA..",
            "................",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        val frame1 = listOf(
            "................",
            "......AAAA......",
            "....AABBBBAA....",
            "..AABCCCCCCBAA..",
            ".AABCCDDDDCCBAA.",
            ".ABCCDEEEEDCCBA.",
            ".AABCCDDDDCCBAA.",
            "..AABCCCCCCBAA..",
            "....AABBBBAA....",
            "..AA........AA..",
            "...AA......AA...",
            "................",
            "................",
            "................",
            "................",
            "................"
        )
        return EntityArchetype(
            id = "fb_drone_$chunkIndex",
            name = "Resonance Wisp",
            behaviorType = BehaviorType.FLYING_SINE,
            speed = 110f,
            hp = 1,
            hitbox = Hitbox(4f, 4f, 30f, 30f),
            attackPattern = AttackPattern(rangePx = 280f, cooldownMs = 1800, projectileSpeed = 240f, telegraphMs = 300),
            spriteMatrix = SpriteMatrix(
                width = 16,
                height = 16,
                paletteMap = mapOf(
                    "A" to "#151A30",
                    "B" to "#2D4263",
                    "C" to accentHex,
                    "D" to "#FFFFFF",
                    "E" to "#FF2E63"
                ),
                frames = listOf(frame0, frame1)
            )
        )
    }
}
