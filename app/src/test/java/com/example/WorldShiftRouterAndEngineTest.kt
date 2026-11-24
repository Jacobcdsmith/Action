package com.example

import com.example.ai.AiProviderType
import com.example.ai.AiRouterSettings
import com.example.ai.RoutingMode
import com.example.engine.ChunkSource
import com.example.engine.FallbackGenerator
import com.example.engine.InputSnapshot
import com.example.engine.PlatformerEngine
import com.example.engine.ReachabilityValidator
import com.example.engine.WorldGenSpawnParams
import com.example.schemas.BuffType
import com.example.schemas.CollectibleDef
import com.example.schemas.CollectibleKind
import com.example.schemas.NpcArchetypeCatalog
import com.example.schemas.NpcArchetypeKind
import com.example.schemas.NpcArchetypeSpec
import com.example.schemas.SchemaValidator
import com.example.schemas.WeatherType
import com.example.ui.BasicPlayerGravityState
import com.example.ui.NpcSpawnPhase
import com.example.ui.NpcSpawnStateMachine
import com.example.ui.ParallaxBackgroundScroller
import com.example.ui.ParallaxBiomeTheme
import com.example.ui.ParallaxSpriteCatalog
import com.example.ui.stepBasicPlayerGravityPhysics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldShiftRouterAndEngineTest {

    @Test
    fun `router settings summary and readiness track BYOK and Firebase failover`() {
        val defaults = AiRouterSettings()
        assertEquals(0, defaults.enabledByokCount())
        assertTrue(defaults.activeSummary().contains("Firebase 3.8/3.5-Lite"))
        assertTrue(defaults.configFor(AiProviderType.FIREBASE_AI).isReady())
        assertFalse(defaults.configFor(AiProviderType.OPENROUTER).isReady())

        val withByok = defaults.copy(
            routingMode = RoutingMode.AUTO_ROUTER,
            providers = defaults.providers.map { cfg ->
                when (cfg.provider) {
                    AiProviderType.OPENROUTER -> cfg.copy(
                        enabled = true,
                        apiKey = "sk-or-test-key",
                        modelId = "openrouter/auto"
                    )
                    AiProviderType.CUSTOM_OPENAI -> cfg.copy(
                        enabled = true,
                        baseUrl = "http://10.0.2.2:11434/v1",
                        modelId = "qwen2.5-32b-instruct"
                    )
                    else -> cfg
                }
            }
        )

        assertEquals(2, withByok.enabledByokCount())
        assertTrue(withByok.activeSummary().contains("2 BYOK"))
        assertTrue(withByok.configFor(AiProviderType.OPENROUTER).isReady())
        assertTrue(withByok.configFor(AiProviderType.CUSTOM_OPENAI).isReady())
    }

    @Test
    fun `fallback generator and reachability validator produce schema-valid chunks with NPCs`() {
        val biome = FallbackGenerator.defaultBiomeForSeed("Cyberpunk fungal swamp")
        val chunk = FallbackGenerator.generateChunk(chunkIndex = 1, biomeTemplate = biome)
        val validated = SchemaValidator.validateChunkLayout(chunk).getOrThrow()
        val report = ReachabilityValidator.validateAndRepair(validated)

        assertTrue(report.layout.platforms.isNotEmpty())
        assertTrue(report.layout.npcSpawns.isNotEmpty())
        assertEquals(0, report.repairsApplied)
    }

    @Test
    fun `magnet buff pulls nearby collectible toward player`() {
        val engine = PlatformerEngine()
        engine.startNewRun("Magnet test seed")
        val customChunk = FallbackGenerator.generateChunk(
            chunkIndex = 0,
            biomeTemplate = engine.currentBiome()
        ).let { base ->
            base.copy(
                collectibles = listOf(
                    CollectibleDef(
                        id = "magnet_shard",
                        x = engine.player.x + 95f,
                        y = engine.player.y + 10f,
                        kind = CollectibleKind.SHARD,
                        value = 25
                    )
                )
            )
        }
        engine.submitGeneratedChunk(customChunk, ChunkSource.AI_GENERATED)
        engine.storyArcTracker.addOrRefreshBuff(BuffType.MAGNET, 20f)

        repeat(45) {
            engine.stepFrame(
                rawDeltaSec = 1f / 60f,
                input = InputSnapshot(),
                viewportWidth = 1280f,
                viewportHeight = 720f
            )
        }

        assertTrue(
            "Expected MAGNET buff to pull and collect nearby shard",
            engine.telemetry.collectiblesGathered >= 1 && engine.player.score >= 25
        )
    }

    @Test
    fun `basic player gravity physics handles delta-time fall, landing, and jump`() {
        val state = BasicPlayerGravityState(x = 100f, y = 200f)
        val groundY = 500f

        // Step downward under gravity until grounded
        repeat(60) {
            stepBasicPlayerGravityPhysics(
                state = state,
                deltaSec = 1f / 60f,
                moveInputX = 1f,
                jumpPressed = false,
                groundY = groundY,
                worldWidth = 1280f
            )
        }

        assertTrue(state.onGround)
        assertEquals(groundY - state.height, state.y, 0.01f)
        assertTrue(state.x > 100f)

        // Trigger jump from ground
        stepBasicPlayerGravityPhysics(
            state = state,
            deltaSec = 1f / 60f,
            moveInputX = 0f,
            jumpPressed = true,
            groundY = groundY,
            worldWidth = 1280f
        )

        assertFalse(state.onGround)
        assertTrue(state.vy < 0f)
        assertTrue(state.y < groundY - state.height)
    }

    @Test
    fun `npc archetype catalog covers all 4 archetypes with valid sprite matrices`() {
        NpcArchetypeKind.entries.forEach { kind ->
            val matches = NpcArchetypeCatalog.byKind(kind)
            assertTrue("Expected archetypes for $kind", matches.isNotEmpty())
            val first = matches.first()
            val spawn = first.toNpcSpawn(0, 0, 200f, 600f, "#00F5D4")
            assertTrue(SchemaValidator.validateSpriteMatrix(spawn.spriteMatrix).isSuccess)
        }
        assertTrue(NpcArchetypeCatalog.guides.first().spec is NpcArchetypeSpec.GuideSpec)
        assertTrue(NpcArchetypeCatalog.merchants.first().spec is NpcArchetypeSpec.MerchantSpec)
        assertTrue(NpcArchetypeCatalog.companions.first().spec is NpcArchetypeSpec.CombatCompanionSpec)
        assertTrue(NpcArchetypeCatalog.rivals.first().spec is NpcArchetypeSpec.RivalSpec)
    }

    @Test
    fun `npc spawn state machine evaluates world params and spawns adaptive archetype`() {
        val engine = PlatformerEngine()
        engine.startNewRun("Glacier Archive")
        val machine = NpcSpawnStateMachine(maxNpcsPerChunk = 4, spawnCooldownSec = 5f)

        val lowHpParams = WorldGenSpawnParams(
            chunkIndex = 0,
            biomeName = "Glacier Archive",
            weatherType = WeatherType.SPORES,
            gravityScale = 1.0f,
            enemyCountInChunk = 0,
            npcCountInChunk = 1,
            playerHp = 2,
            playerMaxHp = 5,
            playerScore = 40,
            hasActiveCompanion = true,
            playerWorldX = 120f,
            elapsedTimeSec = 3f
        )

        // Step through IDLE -> EVALUATING -> SELECTING -> SPAWNING -> SPAWNED -> COOLDOWN
        val phasesSeen = mutableListOf<NpcSpawnPhase>()
        repeat(5) {
            val s = machine.step(0.016f, lowHpParams) { archetype ->
                engine.spawnDynamicNpcFromArchetype(archetype)
            }
            phasesSeen += s.phase
        }

        assertEquals(
            listOf(
                NpcSpawnPhase.EVALUATING_WORLD_PARAMS,
                NpcSpawnPhase.SELECTING_ARCHETYPE,
                NpcSpawnPhase.SPAWNING,
                NpcSpawnPhase.SPAWNED,
                NpcSpawnPhase.COOLDOWN
            ),
            phasesSeen
        )
        assertNotNull(machine.state.selectedArchetype)
        assertEquals(NpcArchetypeKind.MERCHANT, machine.state.selectedArchetype?.kind)
        assertEquals(1, machine.state.totalDynamicSpawns)

        // New chunk wakes state machine out of COOLDOWN immediately
        val nextChunkParams = lowHpParams.copy(chunkIndex = 1, playerHp = 5, hasActiveCompanion = false, enemyCountInChunk = 3)
        val woke = machine.step(0.016f, nextChunkParams) { archetype ->
            engine.spawnDynamicNpcFromArchetype(archetype)
        }
        assertEquals(NpcSpawnPhase.IDLE, woke.phase)
    }

    @Test
    fun `parallax catalog builds depth-ordered biome sprite layers with valid matrices`() {
        val glacier = FallbackGenerator.defaultBiomeForSeed("Glacier Archive")
        val cinder = FallbackGenerator.defaultBiomeForSeed("Volcanic Cinder Foundry")
        val neon = FallbackGenerator.defaultBiomeForSeed("Cyberpunk Fungal Swamp")

        val baseParams = WorldGenSpawnParams(
            chunkIndex = 0,
            biomeName = glacier.name,
            weatherType = glacier.weatherType,
            gravityScale = 0.8f,
            enemyCountInChunk = 1,
            npcCountInChunk = 1,
            playerHp = 5,
            playerMaxHp = 5,
            playerScore = 0,
            hasActiveCompanion = false,
            playerWorldX = 120f,
            elapsedTimeSec = 1f
        )

        assertEquals(ParallaxBiomeTheme.GLACIER_ARCHIVE, ParallaxSpriteCatalog.resolveTheme(glacier, baseParams))
        assertEquals(
            ParallaxBiomeTheme.CINDER_FOUNDRY,
            ParallaxSpriteCatalog.resolveTheme(cinder, baseParams.copy(biomeName = cinder.name, weatherType = cinder.weatherType))
        )
        assertEquals(
            ParallaxBiomeTheme.NEON_MYCELIUM,
            ParallaxSpriteCatalog.resolveTheme(neon, baseParams.copy(biomeName = neon.name, weatherType = neon.weatherType))
        )

        val layers = ParallaxSpriteCatalog.buildLayers(glacier, baseParams)
        assertTrue(layers.size >= 3)
        for (i in 0 until layers.lastIndex) {
            assertTrue(layers[i].depthFactor <= layers[i + 1].depthFactor)
        }
        layers.forEach { layer ->
            assertTrue(
                "Invalid sprite matrix for ${layer.cacheKey}",
                SchemaValidator.validateSpriteMatrix(layer.spriteMatrix).isSuccess
            )
            assertEquals(10f, layer.bobAmplitudePx, 0.01f)
        }
    }

    @Test
    fun `parallax scroller wraps viewport sprites and moves near layers faster than far layers`() {
        val biome = FallbackGenerator.defaultBiomeForSeed("Cyberpunk Fungal Swamp")
        val params = WorldGenSpawnParams(
            chunkIndex = 0,
            biomeName = biome.name,
            weatherType = biome.weatherType,
            gravityScale = 1.0f,
            enemyCountInChunk = 0,
            npcCountInChunk = 1,
            playerHp = 5,
            playerMaxHp = 5,
            playerScore = 0,
            hasActiveCompanion = false,
            playerWorldX = 100f,
            elapsedTimeSec = 1f
        )
        val scroller = ParallaxBackgroundScroller()

        val initial = scroller.update(
            deltaSec = 0f,
            cameraX = 0f,
            biome = biome,
            params = params,
            viewportWidth = 1280f,
            viewportHeight = 720f
        )
        val scrolled = scroller.update(
            deltaSec = 0f,
            cameraX = 120f,
            biome = biome,
            params = params,
            viewportWidth = 1280f,
            viewportHeight = 720f
        )

        val depths = scroller.layers.map { it.depthFactor }
        val farDepth = depths.first()
        val nearDepth = depths.last()
        assertTrue(nearDepth > farDepth)

        val farBefore = initial.first { it.depthFactor == farDepth }.screenX
        val farAfter = scrolled.first { it.depthFactor == farDepth }.screenX
        val nearBefore = initial.first { it.depthFactor == nearDepth }.screenX
        val nearAfter = scrolled.first { it.depthFactor == nearDepth }.screenX

        val farShift = kotlin.math.abs(farBefore - farAfter)
        val nearShift = kotlin.math.abs(nearBefore - nearAfter)
        assertTrue("Expected near layer ($nearShift) to scroll faster than far layer ($farShift)", nearShift > farShift)
        assertTrue("Expected wrapped sprites to cover viewport width", scrolled.any { it.screenX in 0f..1280f })
    }

    @Test
    fun `schema validator normalizes hex colors missing leading hash`() {
        val biome = FallbackGenerator.defaultBiomeForSeed("Test Seed").copy(
            palette = com.example.schemas.BiomePalette(
                skyTop = "0D1B2A",
                skyBottom = "1B263B",
                fogColor = "415A77",
                platformFill = "778DA9",
                platformEdge = "E0E1DD",
                accentColor = "00F5D4"
            )
        )
        val validated = SchemaValidator.validateBiomeConfig(biome).getOrThrow()
        assertEquals("#0D1B2A", validated.palette.skyTop)
        assertEquals("#1B263B", validated.palette.skyBottom)
    }
}
