package com.example.engine

import com.example.schemas.PlatformKind

data class AabbRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    val left: Float get() = x
    val right: Float get() = x + width
    val top: Float get() = y
    val bottom: Float get() = y + height

    fun overlaps(other: AabbRect): Boolean {
        return left < other.right &&
            right > other.left &&
            top < other.bottom &&
            bottom > other.top
    }
}

data class CollisionResolution(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val onGround: Boolean,
    val landedPlatform: RuntimePlatform? = null
)

/**
 * Axis-separated AABB collision solver supporting SOLID, ONE_WAY, CRUMBLING, and BOUNCY platforms.
 */
object AabbCollision {

    fun resolveEntityAgainstPlatforms(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        vx: Float,
        vy: Float,
        prevY: Float,
        dt: Float,
        platforms: List<RuntimePlatform>
    ): CollisionResolution {
        var nextX = (x + vx * dt).coerceAtLeast(0f)
        var nextVx = vx

        // Horizontal sweep against SOLID / CRUMBLING / BOUNCY platforms
        val horizRect = AabbRect(nextX, y + 2f, width, height - 4f)
        for (platform in platforms) {
            if (!platform.solid || platform.kind == PlatformKind.ONE_WAY) continue
            val pRect = AabbRect(platform.worldX, platform.y, platform.width, platform.height)
            if (horizRect.overlaps(pRect)) {
                if (vx > 0f) {
                    nextX = pRect.left - width
                    nextVx = 0f
                } else if (vx < 0f) {
                    nextX = pRect.right
                    nextVx = 0f
                }
            }
        }

        // Vertical sweep
        var nextY = y + vy * dt
        var nextVy = vy
        var onGround = false
        var landedPlatform: RuntimePlatform? = null

        val vertRect = AabbRect(nextX + 1f, nextY, width - 2f, height)
        val prevBottom = prevY + height

        for (platform in platforms) {
            if (!platform.solid) continue
            val pRect = AabbRect(platform.worldX, platform.y, platform.width, platform.height)
            if (!vertRect.overlaps(pRect)) continue

            if (platform.kind == PlatformKind.ONE_WAY) {
                // Only collide when falling from above the top surface
                if (vy >= 0f && prevBottom <= pRect.top + 8f) {
                    nextY = pRect.top - height
                    nextVy = 0f
                    onGround = true
                    landedPlatform = platform
                }
            } else {
                if (vy >= 0f && prevBottom <= pRect.top + 14f) {
                    nextY = pRect.top - height
                    if (platform.kind == PlatformKind.BOUNCY) {
                        nextVy = PhysicsConstants.JUMP_VELOCITY * 1.25f
                        onGround = false
                    } else {
                        nextVy = 0f
                        onGround = true
                    }
                    landedPlatform = platform
                } else if (vy < 0f && prevY >= pRect.bottom - 14f) {
                    nextY = pRect.bottom
                    nextVy = 0f
                }
            }
        }

        return CollisionResolution(
            x = nextX,
            y = nextY,
            vx = nextVx,
            vy = nextVy,
            onGround = onGround,
            landedPlatform = landedPlatform
        )
    }
}
