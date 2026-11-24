package com.example.engine

/**
 * Authoritative physical constants for the fixed-timestep 2D platformer engine
 * and the Reachability Validator / Auto-Repair middleware.
 */
object PhysicsConstants {
    // Fixed timestep simulation rate
    const val PHYSICS_TICK_RATE_HZ: Int = 60
    const val FIXED_TIMESTEP_SEC: Float = 1f / PHYSICS_TICK_RATE_HZ
    const val MAX_FRAME_DELTA_SEC: Float = 0.05f

    // World & streaming chunk dimensions
    const val CHUNK_WIDTH_PX: Float = 2400f
    const val CHUNK_HEIGHT_PX: Float = 900f
    const val GROUND_Y_PX: Float = 720f
    const val LOOKAHEAD_BUFFER_THRESHOLD: Float = 0.25f
    const val RETENTION_CHUNKS_BEHIND: Int = 2

    // Sprite rendering & hitbox dimensions
    const val SPRITE_SCALE_FACTOR: Int = 3
    const val PLAYER_WIDTH_PX: Float = 36f
    const val PLAYER_HEIGHT_PX: Float = 48f

    // Player movement & jump physics (world px/sec & px/sec^2)
    const val GRAVITY: Float = 2100f
    const val PLAYER_MOVE_SPEED: Float = 380f
    const val GROUND_ACCEL: Float = 2800f
    const val AIR_ACCEL: Float = 1950f
    const val BASE_FRICTION: Float = 0.84f
    const val JUMP_VELOCITY: Float = -780f
    const val JUMP_CUT_MULTIPLIER: Float = 0.45f
    const val MAX_FALL_SPEED: Float = 1150f

    // Game feel ("Juice")
    const val COYOTE_TIME_MS: Long = 100L
    const val JUMP_BUFFER_MS: Long = 120L
    const val HIT_STOP_DURATION_MS: Long = 40L
    const val INVULNERABILITY_MS: Long = 900L

    // Derived theoretical jump arc bounds
    // h_max = v_y^2 / (2 * g) ~= 144.86 px
    val MAX_JUMP_HEIGHT: Float = (JUMP_VELOCITY * JUMP_VELOCITY) / (2f * GRAVITY)

    // t_air = 2 * |v_y| / g ~= 0.7429 s -> d_max = v_x * t_air ~= 282.29 px
    val MAX_JUMP_DISTANCE: Float = PLAYER_MOVE_SPEED * ((2f * -JUMP_VELOCITY) / GRAVITY)

    // Reachability Validator safety threshold (85% of theoretical maximum jump arc)
    const val REACHABILITY_SAFETY_FACTOR: Float = 0.85f
    val SAFE_MAX_JUMP_HEIGHT: Float = MAX_JUMP_HEIGHT * REACHABILITY_SAFETY_FACTOR
    val SAFE_MAX_JUMP_DISTANCE: Float = MAX_JUMP_DISTANCE * REACHABILITY_SAFETY_FACTOR
}
