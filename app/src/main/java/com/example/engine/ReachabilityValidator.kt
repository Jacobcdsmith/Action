package com.example.engine

import com.example.schemas.ChunkLayout
import com.example.schemas.PlatformDef
import com.example.schemas.PlatformKind
import kotlin.math.abs
import kotlin.math.ceil

data class ValidationRepairReport(
    val layout: ChunkLayout,
    val repairsApplied: Int,
    val notes: List<String>
)

/**
 * Middleware sitting between the LLM JSON output (or fallback generator) and Chunk Loader.
 * Verifies every sequential traversal gap and elevation climb against 85% of the player's
 * theoretical jump arc, automatically clamping elevations or inserting stepping stones,
 * and snaps NPC spawns cleanly onto safe solid platforms.
 */
object ReachabilityValidator {

    fun validateAndRepair(
        chunk: ChunkLayout,
        entryElevationY: Float = PhysicsConstants.GROUND_Y_PX
    ): ValidationRepairReport {
        val gravityScale = chunk.biome.physicsModifiers.gravityScale.coerceIn(0.45f, 1.8f)
        val safeHeight = PhysicsConstants.SAFE_MAX_JUMP_HEIGHT / gravityScale
        val safeDistance = PhysicsConstants.SAFE_MAX_JUMP_DISTANCE / kotlin.math.sqrt(gravityScale)

        val notes = mutableListOf<String>()
        var repairs = 0

        val sorted = chunk.platforms
            .sortedBy { it.x }
            .map { platform ->
                val clampedX = platform.x.coerceIn(0f, PhysicsConstants.CHUNK_WIDTH_PX - 64f)
                val clampedWidth = platform.width.coerceIn(72f, 900f)
                val clampedY = platform.y.coerceIn(180f, PhysicsConstants.CHUNK_HEIGHT_PX - 60f)
                if (clampedX != platform.x || clampedY != platform.y || clampedWidth != platform.width) {
                    repairs++
                    notes += "Clamped platform ${platform.id} within world bounds"
                }
                platform.copy(x = clampedX, y = clampedY, width = clampedWidth)
            }
            .toMutableList()

        if (sorted.isEmpty() || sorted.first().x > safeDistance * 0.6f) {
            sorted.add(
                0,
                PlatformDef(
                    id = "repair_entry_${chunk.chunkIndex}",
                    x = 0f,
                    y = entryElevationY.coerceIn(240f, PhysicsConstants.GROUND_Y_PX),
                    width = 220f,
                    height = 180f,
                    kind = PlatformKind.SOLID
                )
            )
            repairs++
            notes += "Inserted entry bridge platform at x=0"
        }

        val repaired = mutableListOf<PlatformDef>()
        var prev = sorted.first()
        repaired += prev

        for (i in 1 until sorted.size) {
            var current = sorted[i]
            val horizontalGap = current.x - (prev.x + prev.width)
            val verticalClimb = prev.y - current.y

            if (verticalClimb > safeHeight && horizontalGap <= safeDistance) {
                val clampedY = prev.y - safeHeight * 0.9f
                current = current.copy(y = clampedY)
                repairs++
                notes += "Lowered high platform ${current.id} to stay within safe jump height"
            }

            val updatedGap = current.x - (prev.x + prev.width)
            val updatedClimb = prev.y - current.y

            if (updatedGap > safeDistance || updatedClimb > safeHeight) {
                val stepsByDist = ceil((updatedGap / (safeDistance * 0.85f)).toDouble()).toInt().coerceAtLeast(1)
                val stepsByHeight = if (updatedClimb > 0f) {
                    ceil((updatedClimb / (safeHeight * 0.85f)).toDouble()).toInt().coerceAtLeast(1)
                } else {
                    1
                }
                val stepCount = maxOf(stepsByDist, stepsByHeight)

                val startX = prev.x + prev.width
                val totalSpanX = (current.x - startX).coerceAtLeast(stepCount * 96f)

                for (step in 1..stepCount) {
                    val t = step.toFloat() / (stepCount + 1)
                    val bridgeWidth = 104f
                    val bridgeX = (startX + totalSpanX * t - bridgeWidth * 0.5f)
                        .coerceIn(0f, PhysicsConstants.CHUNK_WIDTH_PX - bridgeWidth)
                    val bridgeY = prev.y + (current.y - prev.y) * t
                    repaired += PlatformDef(
                        id = "repair_step_${chunk.chunkIndex}_${i}_$step",
                        x = bridgeX,
                        y = bridgeY.coerceIn(200f, PhysicsConstants.GROUND_Y_PX + 20f),
                        width = bridgeWidth,
                        height = 24f,
                        kind = PlatformKind.ONE_WAY
                    )
                    repairs++
                }
                notes += "Inserted $stepCount stepping-stone platform(s) before ${current.id}"
            }

            repaired += current
            prev = current
        }

        val last = repaired.last()
        val tailGap = PhysicsConstants.CHUNK_WIDTH_PX - (last.x + last.width)
        if (tailGap > safeDistance * 0.8f) {
            val exitX = (PhysicsConstants.CHUNK_WIDTH_PX - 210f).coerceAtLeast(last.x + last.width + 64f)
            repaired += PlatformDef(
                id = "repair_exit_${chunk.chunkIndex}",
                x = exitX.coerceAtMost(PhysicsConstants.CHUNK_WIDTH_PX - 140f),
                y = PhysicsConstants.GROUND_Y_PX,
                width = 210f,
                height = 180f,
                kind = PlatformKind.SOLID
            )
            repairs++
            notes += "Inserted exit runway platform near chunk boundary"
        }

        // Snap NPC spawns onto valid platforms so they never float over bottomless pits
        val solidCandidates = repaired.filter { it.kind == PlatformKind.SOLID || it.kind == PlatformKind.ONE_WAY }
        val snappedNpcs = chunk.npcSpawns.map { npc ->
            val host = solidCandidates.minByOrNull { p ->
                val center = p.x + p.width * 0.5f
                abs(center - npc.x)
            }
            if (host != null) {
                val safeX = npc.x.coerceIn(host.x + 24f, (host.x + host.width - 56f).coerceAtLeast(host.x + 24f))
                val safeY = host.y - 46f
                if (abs(safeX - npc.x) > 12f || abs(safeY - npc.y) > 12f) {
                    repairs++
                    notes += "Anchored NPC ${npc.name} cleanly onto platform ${host.id}"
                }
                npc.copy(x = safeX, y = safeY)
            } else {
                npc
            }
        }

        return ValidationRepairReport(
            layout = chunk.copy(platforms = repaired, npcSpawns = snappedNpcs),
            repairsApplied = repairs,
            notes = notes
        )
    }
}
