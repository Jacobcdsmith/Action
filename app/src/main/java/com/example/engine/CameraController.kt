package com.example.engine

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Manages camera tracking with velocity lookahead, hit-stop freeze frames,
 * and directional screen shake decay.
 */
class CameraController {
    var x: Float = 0f
        private set
    var y: Float = 0f
        private set

    var shakeOffsetX: Float = 0f
        private set
    var shakeOffsetY: Float = 0f
        private set

    private var shakeIntensity: Float = 0f
    private var shakeDirX: Float = 0f
    private var shakeDirY: Float = 0f
    private var hitStopRemainingMs: Float = 0f

    val isInHitStop: Boolean
        get() = hitStopRemainingMs > 0f

    fun reset(playerX: Float, playerY: Float) {
        x = (playerX - 240f).coerceAtLeast(0f)
        y = (playerY - 420f).coerceIn(-120f, 120f)
        shakeIntensity = 0f
        shakeOffsetX = 0f
        shakeOffsetY = 0f
        hitStopRemainingMs = 0f
    }

    fun triggerHitStop(durationMs: Long = PhysicsConstants.HIT_STOP_DURATION_MS) {
        hitStopRemainingMs = durationMs.toFloat()
    }

    fun addDirectionalShake(intensity: Float, dirX: Float = 1f, dirY: Float = 1f) {
        shakeIntensity = (shakeIntensity + intensity).coerceAtMost(26f)
        shakeDirX = dirX
        shakeDirY = dirY
    }

    fun consumeHitStop(dtMs: Float): Boolean {
        if (hitStopRemainingMs > 0f) {
            hitStopRemainingMs = (hitStopRemainingMs - dtMs).coerceAtLeast(0f)
            return true
        }
        return false
    }

    fun update(
        targetX: Float,
        targetY: Float,
        playerVx: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        dt: Float
    ) {
        val lookahead = (playerVx * 0.28f).coerceIn(-140f, 200f)
        val desiredX = (targetX + lookahead - viewportWidth * 0.34f).coerceAtLeast(0f)
        val desiredY = (targetY - viewportHeight * 0.56f).coerceIn(-140f, 160f)

        val lerpX = (1f - kotlin.math.exp(-7.5f * dt))
        val lerpY = (1f - kotlin.math.exp(-5.5f * dt))
        x += (desiredX - x) * lerpX
        y += (desiredY - y) * lerpY

        if (shakeIntensity > 0.1f) {
            val angle = Random.nextFloat() * Math.PI.toFloat() * 2f
            shakeOffsetX = (cos(angle) * 0.5f + shakeDirX * 0.5f) * shakeIntensity
            shakeOffsetY = (sin(angle) * 0.5f + shakeDirY * 0.5f) * shakeIntensity
            shakeIntensity *= (1f - (11f * dt).coerceAtMost(0.9f))
        } else {
            shakeIntensity = 0f
            shakeOffsetX = 0f
            shakeOffsetY = 0f
        }
    }
}
