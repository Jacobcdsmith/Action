package com.example.schemas

import com.example.engine.PhysicsConstants

/**
 * Runtime schema validator enforcing invariants equivalent to Zod refinements
 * (hex colors, matrix dimensions, palette key coverage, audio bounds, and spatial bounds).
 */
object SchemaValidator {

    private val HEX_COLOR_REGEX = Regex("^#?[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$")
    private val VALID_SPRITE_SIZES = setOf(16, 24)

    private fun normalizeHex(hex: String): String {
        val trimmed = hex.trim()
        return if (trimmed.startsWith("#")) trimmed else "#$trimmed"
    }

    fun validateChunkLayout(chunk: ChunkLayout): Result<ChunkLayout> = runCatching {
        require(chunk.chunkIndex >= 0) { "chunkIndex must be >= 0" }
        require(chunk.environmentalStoryText.isNotBlank()) { "environmentalStoryText must be non-empty" }
        val validatedBiome = validateBiomeConfig(chunk.biome).getOrThrow()

        val archetypeIds = mutableSetOf<String>()
        val validatedArchetypes = chunk.archetypes.map { archetype ->
            val validArch = validateEntityArchetype(archetype).getOrThrow()
            require(archetypeIds.add(validArch.id)) { "Duplicate archetype id: ${validArch.id}" }
            validArch
        }

        require(chunk.platforms.isNotEmpty()) { "Chunk must contain at least one platform" }
        chunk.platforms.forEach { p ->
            require(p.width >= 32f && p.height >= 12f) { "Platform ${p.id} dimensions too small" }
            require(p.x >= 0f && p.x + p.width <= PhysicsConstants.CHUNK_WIDTH_PX + 64f) {
                "Platform ${p.id} x-bounds out of chunk range"
            }
            require(p.y in 80f..PhysicsConstants.CHUNK_HEIGHT_PX) {
                "Platform ${p.id} y-coordinate out of vertical bounds"
            }
        }

        val validatedHazards = chunk.hazards.map { h ->
            require(h.width >= 16f && h.height >= 8f) { "Hazard ${h.id} dimensions invalid" }
            require(h.damage in 1..5) { "Hazard ${h.id} damage out of range [1, 5]" }
            val normMatrix = h.spriteMatrix?.let { validateSpriteMatrix(it).getOrThrow() }
            h.copy(spriteMatrix = normMatrix)
        }

        val validatedCollectibles = chunk.collectibles.map { c ->
            require(c.value > 0) { "Collectible ${c.id} value must be > 0" }
            val normMatrix = c.spriteMatrix?.let { validateSpriteMatrix(it).getOrThrow() }
            c.copy(spriteMatrix = normMatrix)
        }

        chunk.enemySpawns.forEach { spawn ->
            require(spawn.archetypeId in archetypeIds) {
                "EnemySpawn ${spawn.id} references unknown archetypeId=${spawn.archetypeId}"
            }
            require(spawn.patrolRangePx >= 0f) { "EnemySpawn ${spawn.id} patrolRangePx must be >= 0" }
        }

        val validatedNpcs = chunk.npcSpawns.map { npc ->
            require(npc.id.isNotBlank() && npc.name.isNotBlank()) { "NpcSpawn id/name cannot be blank" }
            require(npc.openingLine.isNotBlank()) { "NpcSpawn ${npc.id} openingLine cannot be blank" }
            val validMatrix = validateSpriteMatrix(npc.spriteMatrix).getOrThrow()
            npc.copy(spriteMatrix = validMatrix)
        }

        chunk.copy(
            biome = validatedBiome,
            archetypes = validatedArchetypes,
            hazards = validatedHazards,
            collectibles = validatedCollectibles,
            npcSpawns = validatedNpcs
        )
    }

    fun validateBiomeConfig(biome: BiomeConfig): Result<BiomeConfig> = runCatching {
        require(biome.name.isNotBlank()) { "Biome name cannot be blank" }
        require(biome.loreWhisper.isNotBlank()) { "Biome loreWhisper cannot be blank" }

        val palette = biome.palette
        val normSkyTop = normalizeHex(palette.skyTop)
        val normSkyBottom = normalizeHex(palette.skyBottom)
        val normFog = normalizeHex(palette.fogColor)
        val normFill = normalizeHex(palette.platformFill)
        val normEdge = normalizeHex(palette.platformEdge)
        val normAccent = normalizeHex(palette.accentColor)

        listOf(normSkyTop, normSkyBottom, normFog, normFill, normEdge, normAccent).forEach { hex ->
            require(HEX_COLOR_REGEX.matches(hex)) { "Invalid biome hex color: $hex" }
        }
        val normalizedPalette = palette.copy(
            skyTop = normSkyTop,
            skyBottom = normSkyBottom,
            fogColor = normFog,
            platformFill = normFill,
            platformEdge = normEdge,
            accentColor = normAccent
        )

        require(biome.parallaxConfig.size == 3) {
            "parallaxConfig must specify exactly 3 parallax layers"
        }
        val normalizedParallax = biome.parallaxConfig.map { layer ->
            require(layer.depthFactor in 0.05f..0.95f) { "Parallax depthFactor must be in [0.05, 0.95]" }
            val normTint = normalizeHex(layer.tintHex)
            require(HEX_COLOR_REGEX.matches(normTint)) { "Invalid parallax tintHex: ${layer.tintHex}" }
            require(layer.density in 0.1f..1.0f) { "Parallax density must be in [0.1, 1.0]" }
            require(layer.heightScale in 0.15f..1.0f) { "Parallax heightScale must be in [0.15, 1.0]" }
            layer.copy(tintHex = normTint)
        }

        require(biome.physicsModifiers.gravityScale in 0.35f..2.2f) {
            "gravityScale must be within [0.35, 2.2] to keep platforming playable"
        }
        require(biome.physicsModifiers.friction in 0.5f..0.98f) {
            "friction must be within [0.5, 0.98]"
        }
        require(biome.physicsModifiers.windForceX in -240f..240f) {
            "windForceX must be within [-240, 240]"
        }

        val audio = biome.audioParams
        require(audio.bpm in 50..200) { "bpm must be in [50, 200]" }
        require(audio.rootNoteHz in 40f..880f) { "rootNoteHz must be in [40, 880]" }
        require(audio.scaleIntervals.size in 3..8 && audio.scaleIntervals.all { it in 0..24 }) {
            "scaleIntervals must have 3..8 semitone offsets in [0, 24]"
        }
        require(audio.bassPattern.size in 4..16) { "bassPattern must contain 4..16 steps" }
        require(audio.leadArpPattern.size in 4..16) { "leadArpPattern must contain 4..16 steps" }

        biome.copy(palette = normalizedPalette, parallaxConfig = normalizedParallax)
    }

    fun validateSpriteMatrix(matrix: SpriteMatrix): Result<SpriteMatrix> = runCatching {
        require(matrix.width in VALID_SPRITE_SIZES && matrix.height in VALID_SPRITE_SIZES) {
            "SpriteMatrix dimensions must be 16x16 or 24x24 (got ${matrix.width}x${matrix.height})"
        }
        require(matrix.paletteMap.size in 1..6) {
            "paletteMap must contain 1..6 colors"
        }
        val normalizedPaletteMap = matrix.paletteMap.entries.associate { (symbol, hex) ->
            require(symbol.length == 1 && symbol != ".") {
                "paletteMap key '$symbol' must be a single non-'.' character"
            }
            val normHex = normalizeHex(hex)
            require(HEX_COLOR_REGEX.matches(normHex)) { "Invalid paletteMap hex '$hex' for key '$symbol'" }
            symbol to normHex
        }
        require(matrix.frames.size in 1..3) { "SpriteMatrix must contain 1..3 animation frames" }
        matrix.frames.forEachIndexed { frameIdx, rows ->
            require(rows.size == matrix.height) {
                "Frame $frameIdx row count ${rows.size} != height ${matrix.height}"
            }
            rows.forEachIndexed { rowIdx, row ->
                require(row.length == matrix.width) {
                    "Frame $frameIdx row $rowIdx length ${row.length} != width ${matrix.width}"
                }
                row.forEach { ch ->
                    require(ch == '.' || normalizedPaletteMap.containsKey(ch.toString())) {
                        "Frame $frameIdx row $rowIdx uses unmapped pixel token '$ch'"
                    }
                }
            }
        }
        matrix.copy(paletteMap = normalizedPaletteMap)
    }

    fun validateEntityArchetype(archetype: EntityArchetype): Result<EntityArchetype> = runCatching {
        require(archetype.id.isNotBlank()) { "EntityArchetype id must be non-blank" }
        require(archetype.name.isNotBlank()) { "EntityArchetype name must be non-blank" }
        require(archetype.speed in 0f..520f) { "EntityArchetype speed must be in [0, 520]" }
        require(archetype.hp in 1..20) { "EntityArchetype hp must be in [1, 20]" }
        require(archetype.hitbox.width > 4f && archetype.hitbox.height > 4f) {
            "EntityArchetype hitbox must have positive width and height > 4"
        }
        require(archetype.attackPattern.rangePx >= 0f && archetype.attackPattern.cooldownMs >= 200) {
            "EntityArchetype attackPattern invalid"
        }
        val validatedMatrix = validateSpriteMatrix(archetype.spriteMatrix).getOrThrow()
        archetype.copy(spriteMatrix = validatedMatrix)
    }
}
