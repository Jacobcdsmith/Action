package com.example.engine

import com.example.schemas.WeatherType
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

data class Particle(
    var active: Boolean = false,
    var x: Float = 0f,
    var y: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var size: Float = 4f,
    var lifeMs: Float = 0f,
    var maxLifeMs: Float = 500f,
    var colorHex: String = "#FFFFFF",
    var gravityFactor: Float = 0.2f,
    var isWeather: Boolean = false
)

/**
 * Fixed-capacity pre-allocated particle pool to eliminate GC churn during 60 FPS gameplay.
 */
class ParticlePool(capacity: Int = 260) {

    val particles: Array<Particle> = Array(capacity) { Particle() }
    private var cursor = 0

    fun clear() {
        for (p in particles) {
            p.active = false
        }
    }

    fun emitBurst(
        x: Float,
        y: Float,
        count: Int,
        colorHex: String,
        speedMin: Float = 50f,
        speedMax: Float = 210f,
        sizeMin: Float = 3f,
        sizeMax: Float = 7f,
        lifeMs: Float = 420f,
        gravityFactor: Float = 0.45f,
        upwardBias: Float = -60f
    ) {
        repeat(count) {
            val angle = Random.nextFloat() * Math.PI.toFloat() * 2f
            val speed = speedMin + Random.nextFloat() * (speedMax - speedMin)
            spawn(
                x = x,
                y = y,
                vx = cos(angle) * speed,
                vy = sin(angle) * speed + upwardBias,
                size = sizeMin + Random.nextFloat() * (sizeMax - sizeMin),
                lifeMs = lifeMs * (0.7f + Random.nextFloat() * 0.5f),
                colorHex = colorHex,
                gravityFactor = gravityFactor,
                isWeather = false
            )
        }
    }

    fun emitWeather(
        cameraX: Float,
        cameraY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        weatherType: WeatherType,
        accentHex: String
    ) {
        if (weatherType == WeatherType.NONE) return
        val spawnChance = when (weatherType) {
            WeatherType.RAIN -> 0.85f
            WeatherType.ASH, WeatherType.SNOW, WeatherType.EMBER -> 0.45f
            WeatherType.FIREFLIES, WeatherType.SPORES, WeatherType.DATA_SHARDS -> 0.30f
            WeatherType.NONE -> 0f
        }
        if (Random.nextFloat() > spawnChance) return

        val x = cameraX + Random.nextFloat() * (viewportWidth + 120f)
        val y = when (weatherType) {
            WeatherType.EMBER, WeatherType.FIREFLIES -> cameraY + viewportHeight * (0.35f + Random.nextFloat() * 0.6f)
            else -> cameraY - 20f
        }

        val (vx, vy, color, size, life) = when (weatherType) {
            WeatherType.RAIN -> WeatherSpec(-90f, 620f, "#7CD4FF", 2.5f, 1400f)
            WeatherType.ASH -> WeatherSpec(-35f, 65f, "#B5B5C3", 4f, 3200f)
            WeatherType.SNOW -> WeatherSpec(-25f, 95f, "#F4F8FF", 4.5f, 3400f)
            WeatherType.EMBER -> WeatherSpec(25f, -110f, "#FF8A3D", 4f, 2400f)
            WeatherType.FIREFLIES -> WeatherSpec((Random.nextFloat() - 0.5f) * 55f, (Random.nextFloat() - 0.5f) * 40f, accentHex, 5f, 2600f)
            WeatherType.SPORES -> WeatherSpec(18f, 36f, accentHex, 4.5f, 3000f)
            WeatherType.DATA_SHARDS -> WeatherSpec(-50f, 180f, accentHex, 3.5f, 2200f)
            WeatherType.NONE -> return
        }

        spawn(
            x = x,
            y = y,
            vx = vx,
            vy = vy,
            size = size,
            lifeMs = life,
            colorHex = color,
            gravityFactor = 0f,
            isWeather = true
        )
    }

    fun update(dt: Float) {
        val dtMs = dt * 1000f
        for (p in particles) {
            if (!p.active) continue
            p.lifeMs -= dtMs
            if (p.lifeMs <= 0f) {
                p.active = false
                continue
            }
            p.vy += PhysicsConstants.GRAVITY * p.gravityFactor * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
        }
    }

    private fun spawn(
        x: Float,
        y: Float,
        vx: Float,
        vy: Float,
        size: Float,
        lifeMs: Float,
        colorHex: String,
        gravityFactor: Float,
        isWeather: Boolean
    ) {
        val p = particles[cursor]
        cursor = (cursor + 1) % particles.size
        p.active = true
        p.x = x
        p.y = y
        p.vx = vx
        p.vy = vy
        p.size = size
        p.lifeMs = lifeMs
        p.maxLifeMs = lifeMs
        p.colorHex = colorHex
        p.gravityFactor = gravityFactor
        p.isWeather = isWeather
    }

    private data class WeatherSpec(
        val vx: Float,
        val vy: Float,
        val color: String,
        val size: Float,
        val lifeMs: Float
    )
}
