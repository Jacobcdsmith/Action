package com.example.engine

import com.example.ai.ActiveQuest
import com.example.ai.StoryArcTracker
import com.example.schemas.AttackPattern
import com.example.schemas.BehaviorType
import com.example.schemas.BiomeConfig
import com.example.schemas.BuffType
import com.example.schemas.ChunkLayout
import com.example.schemas.CollectibleKind
import com.example.schemas.EntityArchetype
import com.example.schemas.Hitbox
import com.example.schemas.NpcArchetype
import com.example.schemas.PlatformKind
import com.example.schemas.QuestObjectiveType
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Decoupled 60 Hz fixed-timestep 2D platformer simulation engine.
 * Supports coyote time, jump buffering, variable jump height, squash-and-stretch,
 * hit-stop, enemy AI, dynamic NPC proximity & dialogue pause, combat companion follow/attack,
 * Rival duel conversion, active player buffs, and quest progress tracking.
 */
class PlatformerEngine(
    val storyArcTracker: StoryArcTracker = StoryArcTracker()
) {

    val player = PlayerState()
    val camera = CameraController()
    val particlePool = ParticlePool(280)
    val telemetry = TelemetryTracker()

    var activeCompanion: ActiveCompanionState? = null
        private set

    var isPausedForDialogue: Boolean = false

    private var nearbyNpc: RuntimeNpc? = null
    private val activeChunks = TreeMap<Int, ActiveChunk>()
    private val projectiles = mutableListOf<RuntimeProjectile>()
    private val lookaheadRequested = HashSet<Int>()

    private var accumulatorSec: Float = 0f
    private var activeSeedPrompt: String = "Cyberpunk fungal swamp ruled by clockwork owls"
    private var activeChunkIndex: Int = 0
    private var totalAutoRepairs: Int = 0
    private var isGameOver: Boolean = false
    private var worldTimeSec: Float = 0f

    var onLookaheadNeeded: ((nextChunkIndex: Int, currentBiome: BiomeConfig, telemetry: TelemetryTracker) -> Unit)? = null
    var onEngineEvent: ((EngineEvent, BiomeConfig) -> Unit)? = null
    var onQuestCompleted: ((ActiveQuest) -> Unit)? = null

    fun startNewRun(seedPrompt: String, initialChunk: ChunkLayout? = null) {
        activeSeedPrompt = seedPrompt.ifBlank { "Neon fungal ruin" }
        activeChunks.clear()
        projectiles.clear()
        lookaheadRequested.clear()
        particlePool.clear()
        storyArcTracker.reset(activeSeedPrompt)
        activeCompanion = null
        nearbyNpc = null
        isPausedForDialogue = false

        accumulatorSec = 0f
        activeChunkIndex = 0
        totalAutoRepairs = 0
        isGameOver = false
        worldTimeSec = 0f

        telemetry.apply {
            deaths = 0
            enemiesDefeated = 0
            damageTaken = 0
            collectiblesGathered = 0
            totalElapsedSec = 0f
            totalAirTimeSec = 0f
            maxDistancePx = 0f
            speedSampleSum = 0f
            speedSampleCount = 0
        }

        val seedBiome = initialChunk?.biome ?: FallbackGenerator.defaultBiomeForSeed(activeSeedPrompt)
        val rawChunk0 = initialChunk ?: FallbackGenerator.generateChunk(0, seedBiome)
        installChunk(rawChunk0, if (initialChunk != null) ChunkSource.AI_GENERATED else ChunkSource.FALLBACK_PROCEDURAL)

        val startPlat = activeChunks[0]?.platforms?.firstOrNull()
        player.apply {
            x = (startPlat?.worldX ?: 0f) + 64f
            y = (startPlat?.y ?: PhysicsConstants.GROUND_Y_PX) - PhysicsConstants.PLAYER_HEIGHT_PX - 4f
            vx = 0f
            vy = 0f
            facing = 1
            onGround = false
            coyoteTimerMs = 0f
            jumpBufferTimerMs = 0f
            scaleX = 1f
            scaleY = 1f
            hp = 5
            maxHp = 5
            score = 0
            invulnerableTimerMs = 0f
            animTimeSec = 0f
            dashCooldownMs = 0f
            dashTimerMs = 0f
        }
        camera.reset(player.x, player.y)
        activeChunks[0]?.let {
            storyArcTracker.onChunkEntered(0, it.layout.biome.name, it.layout.environmentalStoryText)
        }
        onEngineEvent?.invoke(EngineEvent.BIOME_ENTERED, currentBiome())
    }

    fun submitGeneratedChunk(layout: ChunkLayout, source: ChunkSource) {
        if (layout.chunkIndex < activeChunkIndex - 1) return
        if (activeChunks[layout.chunkIndex]?.source == ChunkSource.AI_GENERATED) return
        installChunk(layout, source)
    }

    fun promoteNpcToCompanion(npc: RuntimeNpc) {
        npc.recruited = true
        npc.talkedTo = true
        activeCompanion = ActiveCompanionState(
            id = npc.id,
            name = npc.name,
            spriteMatrix = npc.spriteMatrix,
            worldX = npc.worldX,
            y = npc.y - 16f
        )
        storyArcTracker.setCompanion(npc.name)
        particlePool.emitBurst(
            x = npc.worldX + 20f,
            y = npc.y + 20f,
            count = 16,
            colorHex = "#4ADE80",
            gravityFactor = 0.05f
        )
    }

    fun convertRivalToHostile(npc: RuntimeNpc) {
        npc.turnedHostile = true
        npc.talkedTo = true
        val chunk = activeChunks[activeChunkIndex] ?: activeChunks.values.firstOrNull() ?: return
        val rivalArchetype = EntityArchetype(
            id = "rival_${npc.id}",
            name = npc.name,
            behaviorType = BehaviorType.CHARGER,
            speed = 165f,
            hp = 4,
            hitbox = Hitbox(4f, 4f, 36f, 40f),
            attackPattern = AttackPattern(rangePx = 320f, cooldownMs = 900, projectileSpeed = 260f, telegraphMs = 200),
            spriteMatrix = npc.spriteMatrix
        )
        chunk.enemies += RuntimeEnemy(
            id = "enemy_rival_${npc.id}",
            archetype = rivalArchetype,
            worldX = npc.worldX,
            y = npc.y,
            originX = npc.worldX,
            originY = npc.y,
            patrolRangePx = 220f,
            hp = rivalArchetype.hp,
            isRivalBoss = true
        )
        particlePool.emitBurst(
            x = npc.worldX + 20f,
            y = npc.y + 20f,
            count = 18,
            colorHex = "#FF4D6D"
        )
    }

    fun applyRewardScore(delta: Int) {
        player.score = (player.score + delta).coerceAtLeast(0)
    }

    fun healPlayer(amount: Int) {
        if (amount <= 0) return
        player.hp = (player.hp + amount).coerceAtMost(player.maxHp)
    }

    private fun installChunk(rawLayout: ChunkLayout, source: ChunkSource) {
        val prevLastY = activeChunks[rawLayout.chunkIndex - 1]?.platforms?.lastOrNull()?.y
            ?: PhysicsConstants.GROUND_Y_PX
        val report = ReachabilityValidator.validateAndRepair(rawLayout, prevLastY)
        val repaired = report.layout
        totalAutoRepairs += report.repairsApplied

        val startWorldX = repaired.chunkIndex * PhysicsConstants.CHUNK_WIDTH_PX
        val archetypeById = repaired.archetypes.associateBy { it.id }

        val platforms = repaired.platforms.map { p ->
            RuntimePlatform(
                id = p.id,
                worldX = startWorldX + p.x,
                y = p.y,
                width = p.width,
                height = p.height,
                kind = p.kind
            )
        }.toMutableList()

        val hazards = repaired.hazards.map { h ->
            RuntimeHazard(
                id = h.id,
                worldX = startWorldX + h.x,
                y = h.y,
                width = h.width,
                height = h.height,
                kind = h.kind,
                damage = h.damage
            )
        }.toMutableList()

        val collectibles = repaired.collectibles.mapIndexed { idx, c ->
            RuntimeCollectible(
                id = c.id,
                worldX = startWorldX + c.x,
                y = c.y,
                kind = c.kind,
                value = c.value,
                phaseOffset = idx * 0.7f
            )
        }.toMutableList()

        val enemies = repaired.enemySpawns.mapNotNull { spawn ->
            val archetype = archetypeById[spawn.archetypeId] ?: return@mapNotNull null
            RuntimeEnemy(
                id = spawn.id,
                archetype = archetype,
                worldX = startWorldX + spawn.x,
                y = spawn.y,
                originX = startWorldX + spawn.x,
                originY = spawn.y,
                patrolRangePx = spawn.patrolRangePx.coerceAtLeast(48f),
                hp = archetype.hp
            )
        }.toMutableList()

        val npcs = repaired.npcSpawns.mapIndexed { idx, npc ->
            RuntimeNpc(
                id = npc.id,
                name = npc.name,
                role = npc.role,
                personality = npc.personality,
                openingLine = npc.openingLine,
                worldX = startWorldX + npc.x,
                y = npc.y,
                disposition = npc.disposition,
                spriteMatrix = npc.spriteMatrix,
                bobPhase = idx * 1.1f
            )
        }.toMutableList()

        activeChunks[repaired.chunkIndex] = ActiveChunk(
            chunkIndex = repaired.chunkIndex,
            startWorldX = startWorldX,
            layout = repaired,
            source = source,
            autoRepairCount = report.repairsApplied,
            platforms = platforms,
            hazards = hazards,
            collectibles = collectibles,
            enemies = enemies,
            npcs = npcs
        )
    }

    fun stepFrame(
        rawDeltaSec: Float,
        input: InputSnapshot,
        viewportWidth: Float,
        viewportHeight: Float
    ) {
        if (activeChunks.isEmpty() || isPausedForDialogue) return
        val clampedDelta = rawDeltaSec.coerceIn(0f, PhysicsConstants.MAX_FRAME_DELTA_SEC)
        accumulatorSec += clampedDelta

        var jumpPressedRemaining = input.jumpJustPressed
        var jumpReleasedRemaining = input.jumpJustReleased
        var dashPressedRemaining = input.dashJustPressed

        while (accumulatorSec >= PhysicsConstants.FIXED_TIMESTEP_SEC) {
            val stepInput = input.copy(
                jumpJustPressed = jumpPressedRemaining,
                jumpJustReleased = jumpReleasedRemaining,
                dashJustPressed = dashPressedRemaining
            )
            fixedUpdate(PhysicsConstants.FIXED_TIMESTEP_SEC, stepInput, viewportWidth, viewportHeight)
            jumpPressedRemaining = false
            jumpReleasedRemaining = false
            dashPressedRemaining = false
            accumulatorSec -= PhysicsConstants.FIXED_TIMESTEP_SEC
        }
    }

    private fun fixedUpdate(
        dt: Float,
        input: InputSnapshot,
        viewportWidth: Float,
        viewportHeight: Float
    ) {
        val dtMs = dt * 1000f
        worldTimeSec += dt
        storyArcTracker.tickBuffs(dt)

        if (camera.consumeHitStop(dtMs)) {
            camera.update(player.x, player.y, player.vx, viewportWidth, viewportHeight, dt)
            return
        }

        if (isGameOver) {
            particlePool.update(dt)
            camera.update(player.x, player.y, 0f, viewportWidth, viewportHeight, dt)
            return
        }

        val chunkIdx = (player.x / PhysicsConstants.CHUNK_WIDTH_PX).toInt().coerceAtLeast(0)
        ensureChunkStreamed(chunkIdx)

        val currentChunk = activeChunks[activeChunkIndex] ?: activeChunks.values.first()
        val biome = currentChunk.layout.biome

        particlePool.emitWeather(
            cameraX = camera.x,
            cameraY = camera.y,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            weatherType = biome.weatherType,
            accentHex = biome.palette.accentColor
        )

        updatePlatforms(dtMs)
        updatePlayer(dt, dtMs, input, biome)
        updateCompanion(dt, dtMs, biome)
        updateEnemies(dt, dtMs, biome)
        updateProjectiles(dt, dtMs, biome)
        updateNearbyNpc()
        checkHazardsAndCollectibles(dt, biome)
        particlePool.update(dt)

        telemetry.totalElapsedSec += dt
        if (!player.onGround) telemetry.totalAirTimeSec += dt
        telemetry.maxDistancePx = maxOf(telemetry.maxDistancePx, player.x)
        telemetry.speedSampleSum += abs(player.vx)
        telemetry.speedSampleCount += 1

        camera.update(
            targetX = player.x + PhysicsConstants.PLAYER_WIDTH_PX * 0.5f,
            targetY = player.y + PhysicsConstants.PLAYER_HEIGHT_PX * 0.5f,
            playerVx = player.vx,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            dt = dt
        )
    }

    private fun ensureChunkStreamed(playerChunkIdx: Int) {
        if (playerChunkIdx != activeChunkIndex) {
            activeChunkIndex = playerChunkIdx
            if (!activeChunks.containsKey(activeChunkIndex)) {
                val template = activeChunks.values.lastOrNull()?.layout?.biome
                    ?: FallbackGenerator.defaultBiomeForSeed(activeSeedPrompt)
                installChunk(
                    FallbackGenerator.generateChunk(activeChunkIndex, template),
                    ChunkSource.FALLBACK_PROCEDURAL
                )
            }
            activeChunks[activeChunkIndex]?.let { entered ->
                storyArcTracker.onChunkEntered(
                    chunkIndex = activeChunkIndex,
                    biomeName = entered.layout.biome.name,
                    storyText = entered.layout.environmentalStoryText
                )
                triggerQuestProgress(QuestObjectiveType.REACH_CHUNK, absoluteValue = activeChunkIndex + 1, biome = entered.layout.biome)
                onEngineEvent?.invoke(EngineEvent.BIOME_ENTERED, entered.layout.biome)
            }
        }

        val localX = player.x - activeChunkIndex * PhysicsConstants.CHUNK_WIDTH_PX
        val progress = (localX / PhysicsConstants.CHUNK_WIDTH_PX).coerceIn(0f, 1f)
        val nextIndex = activeChunkIndex + 1

        if (progress >= PhysicsConstants.LOOKAHEAD_BUFFER_THRESHOLD && !lookaheadRequested.contains(nextIndex)) {
            lookaheadRequested.add(nextIndex)
            val currentBiome = currentBiome()
            if (onLookaheadNeeded != null) {
                onLookaheadNeeded?.invoke(nextIndex, currentBiome, telemetry)
            } else {
                installChunk(
                    FallbackGenerator.generateChunk(nextIndex, currentBiome),
                    ChunkSource.FALLBACK_PROCEDURAL
                )
            }
        }

        val disposeBefore = activeChunkIndex - PhysicsConstants.RETENTION_CHUNKS_BEHIND
        val keysToRemove = activeChunks.keys.filter { it <= disposeBefore }
        for (key in keysToRemove) {
            activeChunks.remove(key)
        }
    }

    private fun updatePlatforms(dtMs: Float) {
        for (chunk in activeChunks.values) {
            for (platform in chunk.platforms) {
                if (platform.kind == PlatformKind.CRUMBLING) {
                    if (platform.crumbleTimerMs > 0f) {
                        platform.crumbleTimerMs -= dtMs
                        if (platform.crumbleTimerMs <= 0f) {
                            platform.solid = false
                            platform.respawningTimerMs = 2600f
                            particlePool.emitBurst(
                                x = platform.worldX + platform.width * 0.5f,
                                y = platform.y,
                                count = 10,
                                colorHex = chunk.layout.biome.palette.platformEdge
                            )
                        }
                    } else if (!platform.solid && platform.respawningTimerMs > 0f) {
                        platform.respawningTimerMs -= dtMs
                        if (platform.respawningTimerMs <= 0f) {
                            platform.solid = true
                            platform.crumbleTimerMs = -1f
                        }
                    }
                }
            }
        }
    }

    private fun updatePlayer(dt: Float, dtMs: Float, input: InputSnapshot, biome: BiomeConfig) {
        player.animTimeSec += dt
        if (player.invulnerableTimerMs > 0f) {
            player.invulnerableTimerMs = (player.invulnerableTimerMs - dtMs).coerceAtLeast(0f)
        }
        if (player.dashCooldownMs > 0f) {
            player.dashCooldownMs = (player.dashCooldownMs - dtMs).coerceAtLeast(0f)
        }

        val recoverRate = (12f * dt).coerceAtMost(1f)
        player.scaleX += (1f - player.scaleX) * recoverRate
        player.scaleY += (1f - player.scaleY) * recoverRate

        if (player.onGround) {
            player.coyoteTimerMs = PhysicsConstants.COYOTE_TIME_MS.toFloat()
        } else {
            player.coyoteTimerMs = (player.coyoteTimerMs - dtMs).coerceAtLeast(0f)
        }

        if (input.jumpJustPressed) {
            player.jumpBufferTimerMs = PhysicsConstants.JUMP_BUFFER_MS.toFloat()
        } else {
            player.jumpBufferTimerMs = (player.jumpBufferTimerMs - dtMs).coerceAtLeast(0f)
        }

        val speedMult = if (storyArcTracker.hasBuff(BuffType.SPEED_SURGE)) 1.30f else 1.0f
        val jumpMult = if (storyArcTracker.hasBuff(BuffType.JUMP_BOOST)) 1.20f else 1.0f
        val gravBuffMult = if (storyArcTracker.hasBuff(BuffType.LOW_GRAVITY)) 0.65f else 1.0f

        if (input.dashJustPressed && player.dashCooldownMs <= 0f) {
            player.dashTimerMs = 135f
            player.dashCooldownMs = 680f
            player.vx = player.facing * PhysicsConstants.PLAYER_MOVE_SPEED * speedMult * 2.15f
            player.vy = 0f
            player.scaleX = 1.32f
            player.scaleY = 0.76f
            particlePool.emitBurst(
                x = player.x + PhysicsConstants.PLAYER_WIDTH_PX * 0.5f,
                y = player.y + PhysicsConstants.PLAYER_HEIGHT_PX * 0.5f,
                count = 10,
                colorHex = biome.palette.accentColor,
                gravityFactor = 0.1f,
                upwardBias = 0f
            )
            onEngineEvent?.invoke(EngineEvent.DASH, biome)
        }

        if (player.dashTimerMs > 0f) {
            player.dashTimerMs = (player.dashTimerMs - dtMs).coerceAtLeast(0f)
        } else {
            val targetVx = input.moveX * PhysicsConstants.PLAYER_MOVE_SPEED * speedMult
            if (abs(input.moveX) > 0.05f) {
                player.facing = if (input.moveX > 0f) 1 else -1
                val accel = (if (player.onGround) PhysicsConstants.GROUND_ACCEL else PhysicsConstants.AIR_ACCEL) * speedMult
                val deltaVx = accel * dt
                player.vx = when {
                    player.vx < targetVx -> (player.vx + deltaVx).coerceAtMost(targetVx)
                    player.vx > targetVx -> (player.vx - deltaVx).coerceAtLeast(targetVx)
                    else -> targetVx
                }
            } else {
                val friction = if (player.onGround) biome.physicsModifiers.friction else 0.94f
                player.vx *= friction
                if (abs(player.vx) < 6f) player.vx = 0f
            }

            player.vx += biome.physicsModifiers.windForceX * dt * 0.4f
            player.vy = (player.vy + PhysicsConstants.GRAVITY * biome.physicsModifiers.gravityScale * gravBuffMult * dt)
                .coerceAtMost(PhysicsConstants.MAX_FALL_SPEED)
        }

        if (player.jumpBufferTimerMs > 0f && player.coyoteTimerMs > 0f) {
            player.vy = PhysicsConstants.JUMP_VELOCITY * jumpMult
            player.onGround = false
            player.coyoteTimerMs = 0f
            player.jumpBufferTimerMs = 0f
            player.scaleX = 0.74f
            player.scaleY = 1.32f
            particlePool.emitBurst(
                x = player.x + PhysicsConstants.PLAYER_WIDTH_PX * 0.5f,
                y = player.y + PhysicsConstants.PLAYER_HEIGHT_PX,
                count = 8,
                colorHex = biome.palette.platformEdge,
                speedMin = 40f,
                speedMax = 140f,
                upwardBias = -30f
            )
            onEngineEvent?.invoke(EngineEvent.JUMP, biome)
        }

        if (input.jumpJustReleased && player.vy < -120f) {
            player.vy *= PhysicsConstants.JUMP_CUT_MULTIPLIER
        }

        val wasOnGround = player.onGround
        val nearbyPlatforms = nearbyPlatforms(player.x)
        val resolution = AabbCollision.resolveEntityAgainstPlatforms(
            x = player.x,
            y = player.y,
            width = PhysicsConstants.PLAYER_WIDTH_PX,
            height = PhysicsConstants.PLAYER_HEIGHT_PX,
            vx = player.vx,
            vy = player.vy,
            prevY = player.y,
            dt = dt,
            platforms = nearbyPlatforms
        )

        player.x = resolution.x
        player.y = resolution.y
        player.vx = resolution.vx
        player.vy = resolution.vy
        player.onGround = resolution.onGround

        if (!wasOnGround && player.onGround) {
            player.scaleX = 1.28f
            player.scaleY = 0.74f
            particlePool.emitBurst(
                x = player.x + PhysicsConstants.PLAYER_WIDTH_PX * 0.5f,
                y = player.y + PhysicsConstants.PLAYER_HEIGHT_PX,
                count = 7,
                colorHex = biome.palette.platformEdge,
                speedMin = 40f,
                speedMax = 130f,
                upwardBias = -20f
            )
            onEngineEvent?.invoke(EngineEvent.LAND, biome)
        }

        val landed = resolution.landedPlatform
        if (landed != null && landed.kind == PlatformKind.CRUMBLING && landed.crumbleTimerMs < 0f) {
            landed.crumbleTimerMs = 450f
        }

        if (player.y > PhysicsConstants.CHUNK_HEIGHT_PX + 120f) {
            applyDamageToPlayer(1, biome, knockbackY = -560f)
            if (!isGameOver) {
                val safePlatform = nearbyPlatforms.minByOrNull { abs(it.worldX - player.x) }
                if (safePlatform != null) {
                    player.x = safePlatform.worldX + safePlatform.width * 0.4f
                    player.y = safePlatform.y - PhysicsConstants.PLAYER_HEIGHT_PX - 12f
                    player.vx = 0f
                    player.vy = 0f
                }
            }
        }
    }

    private fun updateCompanion(dt: Float, dtMs: Float, biome: BiomeConfig) {
        val comp = activeCompanion ?: return
        comp.animTimeSec += dt
        if (comp.attackCooldownMs > 0f) {
            comp.attackCooldownMs = (comp.attackCooldownMs - dtMs).coerceAtLeast(0f)
        }

        val desiredX = player.x - player.facing * 52f
        val desiredY = player.y - 26f + sin(comp.animTimeSec * 4.2f) * 8f
        val followLerp = (6.5f * dt).coerceAtMost(1f)
        comp.worldX += (desiredX - comp.worldX) * followLerp
        comp.y += (desiredY - comp.y) * followLerp
        comp.facing = if (player.x >= comp.worldX) 1 else -1

        // Fire assisting emerald bolts at closest enemy within 340px
        if (comp.attackCooldownMs <= 0f) {
            val target = activeChunks.values
                .flatMap { it.enemies }
                .filter { it.alive }
                .minByOrNull { hypot(it.worldX - comp.worldX, it.y - comp.y) }

            if (target != null && hypot(target.worldX - comp.worldX, target.y - comp.y) <= 340f) {
                comp.attackCooldownMs = 1350f
                val dx = (target.worldX + target.archetype.hitbox.width * 0.5f) - comp.worldX
                val dy = (target.y + target.archetype.hitbox.height * 0.5f) - comp.y
                val dist = hypot(dx, dy).coerceAtLeast(1f)
                val boltSpeed = 460f
                projectiles += RuntimeProjectile(
                    id = "comp_bolt_${worldTimeSec}",
                    worldX = comp.worldX + 16f,
                    y = comp.y + 16f,
                    vx = (dx / dist) * boltSpeed,
                    vy = (dy / dist) * boltSpeed,
                    radius = 6f,
                    damage = 1,
                    colorHex = "#4ADE80",
                    lifeMs = 1400f,
                    fromPlayerTeam = true
                )
                onEngineEvent?.invoke(EngineEvent.COMPANION_BOLT, biome)
            }
        }
    }

    private fun updateNearbyNpc() {
        val px = player.x + PhysicsConstants.PLAYER_WIDTH_PX * 0.5f
        val py = player.y + PhysicsConstants.PLAYER_HEIGHT_PX * 0.5f
        var best: RuntimeNpc? = null
        var bestDist = 96f

        for (chunk in activeChunks.values) {
            for (npc in chunk.npcs) {
                if (npc.recruited || npc.turnedHostile) continue
                val nx = npc.worldX + 20f
                val ny = npc.y + 22f
                val d = hypot(nx - px, ny - py)
                if (d < bestDist) {
                    bestDist = d
                    best = npc
                }
            }
        }
        nearbyNpc = best
    }

    private fun updateEnemies(dt: Float, dtMs: Float, biome: BiomeConfig) {
        val playerRect = AabbRect(
            player.x,
            player.y,
            PhysicsConstants.PLAYER_WIDTH_PX,
            PhysicsConstants.PLAYER_HEIGHT_PX
        )
        val platforms = nearbyPlatforms(player.x)

        for (chunk in activeChunks.values) {
            for (enemy in chunk.enemies) {
                if (!enemy.alive) continue
                if (abs(enemy.worldX - player.x) > 1600f) continue

                enemy.stateTimerSec += dt
                if (enemy.attackCooldownTimerMs > 0f) {
                    enemy.attackCooldownTimerMs = (enemy.attackCooldownTimerMs - dtMs).coerceAtLeast(0f)
                }
                if (enemy.hitFlashMs > 0f) {
                    enemy.hitFlashMs = (enemy.hitFlashMs - dtMs).coerceAtLeast(0f)
                }

                val dxToPlayer = player.x - enemy.worldX
                val distToPlayer = abs(dxToPlayer)

                when (enemy.archetype.behaviorType) {
                    BehaviorType.PATROL -> {
                        if (enemy.vx == 0f) enemy.vx = enemy.archetype.speed * enemy.facing
                        if (enemy.worldX <= enemy.originX - enemy.patrolRangePx) {
                            enemy.facing = 1
                            enemy.vx = enemy.archetype.speed
                        } else if (enemy.worldX >= enemy.originX + enemy.patrolRangePx) {
                            enemy.facing = -1
                            enemy.vx = -enemy.archetype.speed
                        }
                        enemy.vy += PhysicsConstants.GRAVITY * dt
                    }
                    BehaviorType.LEAPER -> {
                        enemy.vx = if (distToPlayer < enemy.archetype.attackPattern.rangePx) {
                            (if (dxToPlayer > 0f) 1f else -1f) * enemy.archetype.speed
                        } else {
                            enemy.facing * enemy.archetype.speed * 0.5f
                        }
                        if (enemy.onGround && enemy.attackCooldownTimerMs <= 0f) {
                            enemy.vy = -560f
                            enemy.onGround = false
                            enemy.attackCooldownTimerMs = enemy.archetype.attackPattern.cooldownMs.toFloat()
                        }
                        enemy.vy += PhysicsConstants.GRAVITY * dt
                    }
                    BehaviorType.FLYING_SINE -> {
                        if (enemy.worldX <= enemy.originX - enemy.patrolRangePx) enemy.facing = 1
                        if (enemy.worldX >= enemy.originX + enemy.patrolRangePx) enemy.facing = -1
                        enemy.vx = enemy.facing * enemy.archetype.speed
                        val desiredY = enemy.originY + sin(enemy.stateTimerSec * 3.4f) * 34f
                        enemy.vy = (desiredY - enemy.y) * 5f
                    }
                    BehaviorType.CHARGER -> {
                        val aggro = distToPlayer < enemy.archetype.attackPattern.rangePx
                        val mult = if (aggro) 1.75f else 0.7f
                        enemy.facing = if (aggro) (if (dxToPlayer >= 0f) 1 else -1) else enemy.facing
                        enemy.vx = enemy.facing * enemy.archetype.speed * mult
                        enemy.vy += PhysicsConstants.GRAVITY * dt
                    }
                    BehaviorType.PROJECTILE_SHOOTER -> {
                        enemy.facing = if (dxToPlayer >= 0f) 1 else -1
                        enemy.vx = 0f
                        enemy.vy += PhysicsConstants.GRAVITY * dt
                        if (distToPlayer < enemy.archetype.attackPattern.rangePx && enemy.attackCooldownTimerMs <= 0f) {
                            enemy.attackCooldownTimerMs = enemy.archetype.attackPattern.cooldownMs.toFloat()
                            val speed = enemy.archetype.attackPattern.projectileSpeed.coerceAtLeast(220f)
                            projectiles += RuntimeProjectile(
                                id = "proj_${enemy.id}_${worldTimeSec}",
                                worldX = enemy.worldX + enemy.archetype.hitbox.width * 0.5f,
                                y = enemy.y + enemy.archetype.hitbox.height * 0.4f,
                                vx = enemy.facing * speed,
                                vy = 0f,
                                colorHex = biome.palette.accentColor
                            )
                        }
                    }
                }

                if (enemy.archetype.behaviorType == BehaviorType.FLYING_SINE) {
                    enemy.worldX += enemy.vx * dt
                    enemy.y += enemy.vy * dt
                } else {
                    val res = AabbCollision.resolveEntityAgainstPlatforms(
                        x = enemy.worldX,
                        y = enemy.y,
                        width = enemy.archetype.hitbox.width,
                        height = enemy.archetype.hitbox.height,
                        vx = enemy.vx,
                        vy = enemy.vy,
                        prevY = enemy.y,
                        dt = dt,
                        platforms = platforms
                    )
                    enemy.worldX = res.x
                    enemy.y = res.y
                    enemy.vx = res.vx
                    enemy.vy = res.vy
                    enemy.onGround = res.onGround
                }

                val enemyRect = AabbRect(
                    x = enemy.worldX + enemy.archetype.hitbox.offsetX,
                    y = enemy.y + enemy.archetype.hitbox.offsetY,
                    width = enemy.archetype.hitbox.width,
                    height = enemy.archetype.hitbox.height
                )
                if (playerRect.overlaps(enemyRect)) {
                    val isStomping = player.vy > 40f &&
                        (playerRect.bottom - enemyRect.top) < 24f
                    if (isStomping || player.dashTimerMs > 0f) {
                        damageEnemy(enemy, 1, biome)
                        player.vy = PhysicsConstants.JUMP_VELOCITY * 0.72f
                        camera.triggerHitStop(PhysicsConstants.HIT_STOP_DURATION_MS)
                        camera.addDirectionalShake(9f, 0f, 1f)
                    } else {
                        val dir = if (playerRect.x < enemyRect.x) -1f else 1f
                        applyDamageToPlayer(1, biome, knockbackX = dir * 240f, knockbackY = -360f)
                    }
                }
            }
        }
    }

    private fun damageEnemy(enemy: RuntimeEnemy, amount: Int, biome: BiomeConfig) {
        enemy.hp -= amount
        enemy.hitFlashMs = 140f
        particlePool.emitBurst(
            x = enemy.worldX + enemy.archetype.hitbox.width * 0.5f,
            y = enemy.y + enemy.archetype.hitbox.height * 0.5f,
            count = 12,
            colorHex = biome.palette.accentColor
        )
        if (enemy.hp <= 0) {
            enemy.alive = false
            val scoreMult = if (storyArcTracker.hasBuff(BuffType.DOUBLE_SCORE)) 2 else 1
            val baseReward = if (enemy.isRivalBoss) 200 else 50
            player.score += baseReward * scoreMult
            telemetry.enemiesDefeated += 1
            triggerQuestProgress(QuestObjectiveType.DEFEAT_ENEMIES, amount = 1, biome = biome)
            if (enemy.isRivalBoss) {
                triggerQuestProgress(QuestObjectiveType.DEFEAT_RIVAL, amount = 1, biome = biome)
            }
        }
        onEngineEvent?.invoke(EngineEvent.STOMP_ENEMY, biome)
    }

    private fun updateProjectiles(dt: Float, dtMs: Float, biome: BiomeConfig) {
        val playerRect = AabbRect(
            player.x,
            player.y,
            PhysicsConstants.PLAYER_WIDTH_PX,
            PhysicsConstants.PLAYER_HEIGHT_PX
        )
        val iter = projectiles.iterator()
        while (iter.hasNext()) {
            val proj = iter.next()
            proj.lifeMs -= dtMs
            proj.worldX += proj.vx * dt
            proj.y += proj.vy * dt
            if (proj.lifeMs <= 0f) {
                iter.remove()
                continue
            }
            val projRect = AabbRect(
                proj.worldX - proj.radius,
                proj.y - proj.radius,
                proj.radius * 2f,
                proj.radius * 2f
            )
            if (proj.fromPlayerTeam) {
                var hitEnemy = false
                for (chunk in activeChunks.values) {
                    for (enemy in chunk.enemies) {
                        if (!enemy.alive) continue
                        val eRect = AabbRect(
                            enemy.worldX + enemy.archetype.hitbox.offsetX,
                            enemy.y + enemy.archetype.hitbox.offsetY,
                            enemy.archetype.hitbox.width,
                            enemy.archetype.hitbox.height
                        )
                        if (projRect.overlaps(eRect)) {
                            damageEnemy(enemy, proj.damage, biome)
                            hitEnemy = true
                            break
                        }
                    }
                    if (hitEnemy) break
                }
                if (hitEnemy) {
                    iter.remove()
                }
            } else if (playerRect.overlaps(projRect)) {
                applyDamageToPlayer(proj.damage, biome, knockbackX = if (proj.vx >= 0f) 200f else -200f)
                iter.remove()
            }
        }
    }

    private fun checkHazardsAndCollectibles(dt: Float, biome: BiomeConfig) {
        val playerRect = AabbRect(
            player.x,
            player.y,
            PhysicsConstants.PLAYER_WIDTH_PX,
            PhysicsConstants.PLAYER_HEIGHT_PX
        )
        val hasMagnet = storyArcTracker.hasBuff(BuffType.MAGNET)
        val scoreMult = if (storyArcTracker.hasBuff(BuffType.DOUBLE_SCORE)) 2 else 1

        for (chunk in activeChunks.values) {
            for (hazard in chunk.hazards) {
                val hazRect = AabbRect(hazard.worldX, hazard.y, hazard.width, hazard.height)
                if (playerRect.overlaps(hazRect)) {
                    applyDamageToPlayer(hazard.damage, biome, knockbackY = -440f)
                }
            }
            for (col in chunk.collectibles) {
                if (col.collected) continue
                val colRect = AabbRect(col.worldX - 14f, col.y - 14f, 28f, 28f)
                if (playerRect.overlaps(colRect)) {
                    col.collected = true
                    telemetry.collectiblesGathered += 1
                    if (col.kind == CollectibleKind.HEAL_ORB) {
                        player.hp = (player.hp + 1).coerceAtMost(player.maxHp)
                    } else {
                        player.score += col.value * scoreMult
                    }
                    triggerQuestProgress(QuestObjectiveType.COLLECT_SHARDS, amount = 1, biome = biome)
                    particlePool.emitBurst(
                        x = col.worldX,
                        y = col.y,
                        count = 10,
                        colorHex = biome.palette.accentColor,
                        gravityFactor = 0.1f
                    )
                    onEngineEvent?.invoke(EngineEvent.COLLECT, biome)
                } else if (hasMagnet) {
                    val px = player.x + PhysicsConstants.PLAYER_WIDTH_PX * 0.5f
                    val py = player.y + PhysicsConstants.PLAYER_HEIGHT_PX * 0.5f
                    val dist = hypot(px - col.worldX, py - col.y)
                    if (dist < 180f) {
                        val pull = (4.5f * dt).coerceAtMost(1f)
                        col.worldX += (px - col.worldX) * pull
                        col.y += (py - col.y) * pull
                    }
                }
            }
        }
    }

    private fun triggerQuestProgress(
        type: QuestObjectiveType,
        amount: Int = 1,
        absoluteValue: Int? = null,
        biome: BiomeConfig
    ) {
        val completed = storyArcTracker.recordQuestProgress(type, amount, absoluteValue)
        for (quest in completed) {
            player.score += quest.rewardScore
            storyArcTracker.addOrRefreshBuff(quest.rewardBuff, 30f)
            onQuestCompleted?.invoke(quest)
            onEngineEvent?.invoke(EngineEvent.QUEST_COMPLETE, biome)
        }
    }

    private fun applyDamageToPlayer(
        damage: Int,
        biome: BiomeConfig,
        knockbackX: Float = 0f,
        knockbackY: Float = -320f
    ) {
        if (player.invulnerableTimerMs > 0f || isGameOver) return

        // Aegis Shield buff absorbs one hit cleanly
        if (storyArcTracker.hasBuff(BuffType.SHIELD)) {
            storyArcTracker.consumeBuff(BuffType.SHIELD)
            player.invulnerableTimerMs = PhysicsConstants.INVULNERABILITY_MS.toFloat()
            player.vx = knockbackX * 0.6f
            player.vy = knockbackY * 0.7f
            camera.addDirectionalShake(8f, 1f, 0f)
            particlePool.emitBurst(
                x = player.x + PhysicsConstants.PLAYER_WIDTH_PX * 0.5f,
                y = player.y + PhysicsConstants.PLAYER_HEIGHT_PX * 0.5f,
                count = 18,
                colorHex = "#38BDF8"
            )
            return
        }

        player.hp = (player.hp - damage).coerceAtLeast(0)
        player.invulnerableTimerMs = PhysicsConstants.INVULNERABILITY_MS.toFloat()
        player.vx = knockbackX
        player.vy = knockbackY
        telemetry.damageTaken += damage
        camera.triggerHitStop(PhysicsConstants.HIT_STOP_DURATION_MS)
        camera.addDirectionalShake(14f, if (knockbackX >= 0f) 1f else -1f, -0.5f)
        particlePool.emitBurst(
            x = player.x + PhysicsConstants.PLAYER_WIDTH_PX * 0.5f,
            y = player.y + PhysicsConstants.PLAYER_HEIGHT_PX * 0.5f,
            count = 16,
            colorHex = "#FF4D6D"
        )
        if (player.hp <= 0) {
            isGameOver = true
            telemetry.deaths += 1
            onEngineEvent?.invoke(EngineEvent.PLAYER_DEATH, biome)
        } else {
            onEngineEvent?.invoke(EngineEvent.PLAYER_DAMAGE, biome)
        }
    }

    private fun nearbyPlatforms(worldX: Float): List<RuntimePlatform> {
        val list = mutableListOf<RuntimePlatform>()
        for (chunk in activeChunks.values) {
            if (worldX + 900f >= chunk.startWorldX &&
                worldX - 900f <= chunk.startWorldX + PhysicsConstants.CHUNK_WIDTH_PX
            ) {
                list.addAll(chunk.platforms)
            }
        }
        return list
    }

    fun currentBiome(): BiomeConfig =
        activeChunks[activeChunkIndex]?.layout?.biome
            ?: activeChunks.values.firstOrNull()?.layout?.biome
            ?: FallbackGenerator.defaultBiomeForSeed(activeSeedPrompt)

    fun chunksSnapshot(): List<ActiveChunk> = activeChunks.values.toList()
    fun projectilesSnapshot(): List<RuntimeProjectile> = projectiles.toList()
    fun getWorldTimeSec(): Float = worldTimeSec

    fun buildWorldGenSpawnParams(): WorldGenSpawnParams {
        val chunk = activeChunks[activeChunkIndex] ?: activeChunks.values.firstOrNull()
        val biome = chunk?.layout?.biome ?: currentBiome()
        return WorldGenSpawnParams(
            chunkIndex = activeChunkIndex,
            biomeName = biome.name,
            weatherType = biome.weatherType,
            gravityScale = biome.physicsModifiers.gravityScale,
            enemyCountInChunk = chunk?.enemies?.count { it.alive } ?: 0,
            npcCountInChunk = chunk?.npcs?.size ?: 0,
            playerHp = player.hp,
            playerMaxHp = player.maxHp,
            playerScore = player.score,
            hasActiveCompanion = activeCompanion != null,
            playerWorldX = player.x,
            elapsedTimeSec = worldTimeSec
        )
    }

    fun spawnDynamicNpcFromArchetype(archetype: NpcArchetype): RuntimeNpc? {
        val chunk = activeChunks[activeChunkIndex] ?: activeChunks.values.firstOrNull() ?: return null
        val biome = chunk.layout.biome

        val candidatePlatforms = chunk.platforms
            .filter { it.kind != PlatformKind.CRUMBLING && it.width >= 110f }
            .ifEmpty { chunk.platforms }
        if (candidatePlatforms.isEmpty()) return null

        // Prefer a platform ahead of the player that does not crowd an existing NPC
        val uncrowdedAhead = candidatePlatforms.firstOrNull { plat ->
            val centerX = plat.worldX + plat.width * 0.55f
            centerX >= player.x + 150f &&
                chunk.npcs.none { existing -> abs(existing.worldX - centerX) < 140f }
        }
        val fallbackPlat = candidatePlatforms.lastOrNull { plat ->
            val centerX = plat.worldX + plat.width * 0.5f
            chunk.npcs.none { existing -> abs(existing.worldX - centerX) < 120f }
        } ?: candidatePlatforms.last()

        val anchor = uncrowdedAhead ?: fallbackPlat
        val localX = ((anchor.worldX - chunk.startWorldX) + anchor.width * 0.52f)
            .coerceIn(24f, PhysicsConstants.CHUNK_WIDTH_PX - 64f)
        val spawnY = (anchor.y - 46f).coerceIn(120f, PhysicsConstants.GROUND_Y_PX)
        val slot = chunk.npcs.size
        val npcDef = archetype.toNpcSpawn(
            chunkIndex = chunk.chunkIndex,
            slot = slot,
            x = localX,
            y = spawnY,
            edgeHex = biome.palette.platformEdge
        )

        val runtimeNpc = RuntimeNpc(
            id = "${npcDef.id}_${(worldTimeSec * 100).toInt()}",
            name = npcDef.name,
            role = npcDef.role,
            personality = npcDef.personality,
            openingLine = npcDef.openingLine,
            worldX = chunk.startWorldX + localX,
            y = spawnY,
            disposition = npcDef.disposition,
            spriteMatrix = npcDef.spriteMatrix,
            bobPhase = slot * 1.1f
        )
        chunk.npcs += runtimeNpc
        particlePool.emitBurst(
            x = runtimeNpc.worldX + 20f,
            y = runtimeNpc.y + 22f,
            count = 14,
            colorHex = archetype.visorHex,
            gravityFactor = 0.05f
        )
        return runtimeNpc
    }

    fun buildHudSnapshot(): HudSnapshot {
        val chunk = activeChunks[activeChunkIndex] ?: activeChunks.values.firstOrNull()
        val biome = chunk?.layout?.biome ?: FallbackGenerator.defaultBiomeForSeed(activeSeedPrompt)
        return HudSnapshot(
            isRunning = !isGameOver && activeChunks.isNotEmpty(),
            isGameOver = isGameOver,
            isPausedForDialogue = isPausedForDialogue,
            hp = player.hp,
            maxHp = player.maxHp,
            score = player.score,
            currentChunkIndex = activeChunkIndex,
            currentBiomeName = biome.name,
            loreWhisper = biome.loreWhisper,
            storyText = chunk?.layout?.environmentalStoryText.orEmpty(),
            autoRepairsApplied = totalAutoRepairs,
            activeChunkCount = activeChunks.size,
            currentChunkSource = chunk?.source ?: ChunkSource.FALLBACK_PROCEDURAL,
            averageSpeed = telemetry.averageSpeed,
            airTimeRatio = telemetry.airTimeRatio,
            enemiesDefeated = telemetry.enemiesDefeated,
            deaths = telemetry.deaths,
            currentBiome = biome,
            nearbyNpc = nearbyNpc,
            activeCompanionName = activeCompanion?.name
        )
    }
}
