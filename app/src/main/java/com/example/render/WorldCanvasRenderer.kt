package com.example.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.engine.ActiveChunk
import com.example.engine.PhysicsConstants
import com.example.engine.PlatformerEngine
import com.example.render.ParallaxRenderer.renderBiomeBackdrop
import com.example.schemas.BuffType
import com.example.schemas.CollectibleKind
import com.example.schemas.HazardKind
import com.example.schemas.NpcRole
import com.example.schemas.PlatformKind
import kotlin.math.abs
import kotlin.math.sin

/**
 * Pure Compose DrawScope renderer decoupled from UI state.
 * Renders procedural parallax backdrops, chunks, NPCs with role sigils,
 * recruited combat companions, compiled nearest-neighbor sprites, shield aura, and particles.
 */
object WorldCanvasRenderer {

    private val colorCache = HashMap<String, Color>(64)

    fun parseHexColor(hex: String, fallback: Color = Color.Magenta): Color {
        return colorCache.getOrPut(hex) {
            try {
                val normalized = if (hex.startsWith("#")) hex else "#$hex"
                Color(android.graphics.Color.parseColor(normalized))
            } catch (_: Exception) {
                fallback
            }
        }
    }

    fun DrawScope.renderWorld(
        engine: PlatformerEngine,
        nearbyNpcId: String? = null,
        onDrawParallaxSprites: (DrawScope.() -> Unit)? = null
    ) {
        val biome = engine.currentBiome()
        val camX = engine.camera.x + engine.camera.shakeOffsetX
        val camY = engine.camera.y + engine.camera.shakeOffsetY
        val time = engine.getWorldTimeSec()

        val worldScale = (size.height / PhysicsConstants.CHUNK_HEIGHT_PX).coerceIn(0.45f, 2.2f)
        val viewWorldW = size.width / worldScale
        val viewWorldH = size.height / worldScale

        renderBiomeBackdrop(biome = biome, camX = camX, timeSec = time)
        onDrawParallaxSprites?.invoke(this)

        withTransform({
            scale(worldScale, worldScale, pivot = Offset.Zero)
            translate(left = -camX, top = -camY)
        }) {
            val chunks = engine.chunksSnapshot()
            for (chunk in chunks) {
                if (chunk.startWorldX + PhysicsConstants.CHUNK_WIDTH_PX < camX - 100f ||
                    chunk.startWorldX > camX + viewWorldW + 100f
                ) {
                    continue
                }
                drawChunk(chunk, camX, viewWorldW, time, nearbyNpcId)
            }

            // Active Combat Companion following player
            engine.activeCompanion?.let { comp ->
                val sheet = SpriteCompiler.getOrCompile(comp.id, comp.spriteMatrix)
                val frameIdx = ((time * 6f).toInt()) % sheet.frames.size
                val bmp = sheet.frames[frameIdx]
                val pivotX = comp.worldX + 20f
                val pivotY = comp.y + 20f
                drawCircle(
                    color = Color(0x334ADE80),
                    radius = 26f,
                    center = Offset(pivotX, pivotY)
                )
                withTransform({
                    if (comp.facing < 0) scale(-1f, 1f, pivot = Offset(pivotX, pivotY))
                }) {
                    drawImage(
                        image = bmp,
                        dstOffset = IntOffset(comp.worldX.toInt(), comp.y.toInt()),
                        dstSize = IntSize(38, 38),
                        filterQuality = FilterQuality.None
                    )
                }
            }

            // Projectiles (both hostile and companion emerald bolts)
            for (proj in engine.projectilesSnapshot()) {
                val pColor = parseHexColor(proj.colorHex, Color.Yellow)
                drawCircle(
                    color = pColor.copy(alpha = 0.35f),
                    radius = proj.radius * 1.8f,
                    center = Offset(proj.worldX, proj.y)
                )
                drawCircle(
                    color = pColor,
                    radius = proj.radius,
                    center = Offset(proj.worldX, proj.y)
                )
            }

            drawPlayer(engine, biome.palette.accentColor, biome.palette.platformEdge)

            for (p in engine.particlePool.particles) {
                if (!p.active) continue
                if (p.x < camX - 40f || p.x > camX + viewWorldW + 40f) continue
                if (p.y < camY - 40f || p.y > camY + viewWorldH + 40f) continue
                val alpha = (p.lifeMs / p.maxLifeMs).coerceIn(0f, 1f)
                val pColor = parseHexColor(p.colorHex, Color.White).copy(alpha = alpha)
                drawRect(
                    color = pColor,
                    topLeft = Offset(p.x - p.size * 0.5f, p.y - p.size * 0.5f),
                    size = Size(p.size, p.size)
                )
            }
        }
    }

    private fun DrawScope.drawChunk(
        chunk: ActiveChunk,
        camX: Float,
        viewWorldW: Float,
        time: Float,
        nearbyNpcId: String?
    ) {
        val palette = chunk.layout.biome.palette
        val platFill = parseHexColor(palette.platformFill, Color(0xFF1E2B45))
        val platEdge = parseHexColor(palette.platformEdge, Color(0xFF00F5D4))
        val accent = parseHexColor(palette.accentColor, Color(0xFFFF4D8D))

        for (plat in chunk.platforms) {
            if (!plat.solid) continue
            if (plat.worldX + plat.width < camX - 40f || plat.worldX > camX + viewWorldW + 40f) continue

            val alpha = if (plat.crumbleTimerMs > 0f) 0.55f else 1f
            drawRect(
                color = platFill.copy(alpha = alpha),
                topLeft = Offset(plat.worldX, plat.y),
                size = Size(plat.width, plat.height)
            )
            val edgeColor = when (plat.kind) {
                PlatformKind.BOUNCY -> accent
                PlatformKind.CRUMBLING -> Color(0xFFFFB703)
                else -> platEdge
            }
            drawRect(
                color = edgeColor.copy(alpha = alpha),
                topLeft = Offset(plat.worldX, plat.y),
                size = Size(plat.width, 6f)
            )
        }

        for (hazard in chunk.hazards) {
            if (hazard.worldX + hazard.width < camX - 40f || hazard.worldX > camX + viewWorldW + 40f) continue
            val hazardColor = when (hazard.kind) {
                HazardKind.LAVA_POOL -> Color(0xFFFF5400)
                HazardKind.ACID_POOL -> Color(0xFF80FF72)
                HazardKind.LASER_BEAM -> Color(0xFFFF006E)
                HazardKind.VOID_RIFT -> Color(0xFF9D4EDD)
                HazardKind.SPIKES -> Color(0xFFFF3355)
            }
            if (hazard.kind == HazardKind.SPIKES) {
                val teeth = (hazard.width / 14f).toInt().coerceAtLeast(2)
                val toothW = hazard.width / teeth
                val path = Path()
                for (t in 0 until teeth) {
                    val sx = hazard.worldX + t * toothW
                    path.moveTo(sx, hazard.y + hazard.height)
                    path.lineTo(sx + toothW * 0.5f, hazard.y)
                    path.lineTo(sx + toothW, hazard.y + hazard.height)
                }
                path.close()
                drawPath(path, color = hazardColor)
            } else {
                drawRect(
                    color = hazardColor.copy(alpha = 0.85f),
                    topLeft = Offset(hazard.worldX, hazard.y),
                    size = Size(hazard.width, hazard.height)
                )
            }
        }

        for (col in chunk.collectibles) {
            if (col.collected) continue
            if (col.worldX < camX - 40f || col.worldX > camX + viewWorldW + 40f) continue
            val bobY = col.y + sin(time * 4.5f + col.phaseOffset) * 5f
            val colColor = when (col.kind) {
                CollectibleKind.HEAL_ORB -> Color(0xFF4ADE80)
                CollectibleKind.CORE_FRAGMENT -> Color(0xFFFACC15)
                CollectibleKind.MUTATION_RELIC -> Color(0xFFC084FC)
                CollectibleKind.SHARD -> accent
            }
            drawCircle(
                color = colColor.copy(alpha = 0.25f),
                radius = 16f,
                center = Offset(col.worldX, bobY)
            )
            drawCircle(
                color = colColor,
                radius = 9f,
                center = Offset(col.worldX, bobY)
            )
            drawCircle(
                color = Color.White,
                radius = 3.5f,
                center = Offset(col.worldX - 2f, bobY - 2f)
            )
        }

        // Dynamic NPCs with role-coded overhead sigils & proximity highlight
        for (npc in chunk.npcs) {
            if (npc.recruited || npc.turnedHostile) continue
            if (npc.worldX < camX - 80f || npc.worldX > camX + viewWorldW + 80f) continue

            val sheet = SpriteCompiler.getOrCompile(npc.id, npc.spriteMatrix)
            val frameIdx = if (sheet.frames.size > 1) ((time * 3f).toInt()) % sheet.frames.size else 0
            val bmp = sheet.frames[frameIdx]

            val roleColor = when (npc.role) {
                NpcRole.GUIDE_MERCHANT -> Color(0xFF00F5D4)
                NpcRole.LOREKEEPER -> Color(0xFFC084FC)
                NpcRole.COMPANION -> Color(0xFF4ADE80)
                NpcRole.RIVAL -> Color(0xFFFB923C)
            }

            if (npc.id == nearbyNpcId) {
                drawCircle(
                    color = roleColor.copy(alpha = 0.22f),
                    radius = 38f,
                    center = Offset(npc.worldX + 22f, npc.y + 22f)
                )
                drawCircle(
                    color = roleColor,
                    radius = 38f,
                    center = Offset(npc.worldX + 22f, npc.y + 22f),
                    style = Stroke(width = 2.5f)
                )
            }

            drawImage(
                image = bmp,
                dstOffset = IntOffset(npc.worldX.toInt(), npc.y.toInt()),
                dstSize = IntSize(44, 46),
                filterQuality = FilterQuality.None
            )

            // Floating diamond role sigil above NPC
            val sigilY = npc.y - 16f + sin(time * 3.5f + npc.bobPhase) * 4f
            val sigilX = npc.worldX + 22f
            val sigil = Path().apply {
                moveTo(sigilX, sigilY - 7f)
                lineTo(sigilX + 6f, sigilY)
                lineTo(sigilX, sigilY + 7f)
                lineTo(sigilX - 6f, sigilY)
                close()
            }
            drawPath(sigil, color = roleColor)
        }

        for (enemy in chunk.enemies) {
            if (!enemy.alive) continue
            if (enemy.worldX < camX - 80f || enemy.worldX > camX + viewWorldW + 80f) continue

            val sheet = SpriteCompiler.getOrCompile(
                cacheKey = enemy.archetype.id,
                matrix = enemy.archetype.spriteMatrix
            )
            val frameIdx = if (sheet.frames.size > 1) {
                ((time * 6f).toInt()) % sheet.frames.size
            } else {
                0
            }
            val bitmap = sheet.frames[frameIdx]
            val targetW = enemy.archetype.hitbox.width + 8f
            val targetH = enemy.archetype.hitbox.height + 8f
            val pivotX = enemy.worldX + targetW * 0.5f
            val pivotY = enemy.y + targetH * 0.5f

            if (enemy.isRivalBoss) {
                drawCircle(
                    color = Color(0x44FF4D6D),
                    radius = 32f,
                    center = Offset(pivotX, pivotY)
                )
            }

            withTransform({
                if (enemy.facing < 0) {
                    scale(scaleX = -1f, scaleY = 1f, pivot = Offset(pivotX, pivotY))
                }
            }) {
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset(enemy.worldX.toInt(), enemy.y.toInt()),
                    dstSize = IntSize(targetW.toInt(), targetH.toInt()),
                    filterQuality = FilterQuality.None,
                    colorFilter = if (enemy.hitFlashMs > 0f) ColorFilter.tint(Color.White) else null
                )
            }
        }
    }

    private fun DrawScope.drawPlayer(
        engine: PlatformerEngine,
        accentHex: String,
        edgeHex: String
    ) {
        val p = engine.player
        if (p.invulnerableTimerMs > 0f && ((p.invulnerableTimerMs / 65f).toInt() % 2 == 0)) {
            return
        }
        val w = PhysicsConstants.PLAYER_WIDTH_PX
        val h = PhysicsConstants.PLAYER_HEIGHT_PX
        val sheet = SpriteCompiler.getOrCompilePlayer(accentHex, edgeHex)
        val frameIdx = if (abs(p.vx) > 25f && sheet.frames.size > 1) {
            ((p.animTimeSec * 9f).toInt()) % sheet.frames.size
        } else {
            0
        }
        val bitmap = sheet.frames[frameIdx]
        val drawW = w + 10f
        val drawH = h
        val drawX = p.x - 5f
        val drawY = p.y
        val pivotX = p.x + w * 0.5f
        val pivotY = p.y + h

        if (engine.storyArcTracker.hasBuff(BuffType.SHIELD)) {
            drawCircle(
                color = Color(0x4438BDF8),
                radius = 34f,
                center = Offset(pivotX, p.y + h * 0.5f)
            )
            drawCircle(
                color = Color(0xAA38BDF8),
                radius = 34f,
                center = Offset(pivotX, p.y + h * 0.5f),
                style = Stroke(width = 2f)
            )
        }

        withTransform({
            scale(
                scaleX = p.scaleX * p.facing,
                scaleY = p.scaleY,
                pivot = Offset(pivotX, pivotY)
            )
        }) {
            drawImage(
                image = bitmap,
                dstOffset = IntOffset(drawX.toInt(), drawY.toInt()),
                dstSize = IntSize(drawW.toInt(), drawH.toInt()),
                filterQuality = FilterQuality.None
            )
        }
    }
}
