package com.example.ai

import android.util.Log
import com.example.engine.PhysicsConstants
import com.example.engine.TelemetryTracker
import com.example.schemas.BiomeConfig
import com.example.schemas.SchemaValidator
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AiDirector"

/**
 * Coordinates world-chunk synthesis across the multi-provider AiModelRouter
 * (BYOK keys + Firebase AI Logic Auto Router) using strict JSON schema validation.
 */
class AiDirector(
    private val router: AiModelRouter
) {
    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    private val dtoAdapter by lazy {
        moshi.adapter(GeminiChunkLayoutDto::class.java)
    }

    private val systemInstruction = """
        You are the AI World Director for a 2D side-scrolling action-platformer.
        Generate a complete, cohesive ChunkLayout JSON for a chunk of width ${PhysicsConstants.CHUNK_WIDTH_PX.toInt()}px and height ${PhysicsConstants.CHUNK_HEIGHT_PX.toInt()}px.
        Follow these strict spatial and visual rules:
        1. Platforms must progress left-to-right from x=0 to x>=2150, with y between 280 and 740, width between 110 and 420, height 24 for ONE_WAY or 160 for SOLID.
        2. Keep horizontal gaps between consecutive platforms <= 210px and upward climbs <= 115px.
        3. Define 1 to 2 enemy archetypes with 16x16 spriteMatrix (width=16, height=16). Every frame MUST contain exactly 16 strings, each of length EXACTLY 16, using '.' for transparency and 1-char symbols defined in paletteEntries (max 6 colors).
        4. Include 1 to 2 contextual npcSpawns (mixing GUIDE_MERCHANT, LOREKEEPER, COMPANION, and RIVAL across chunks) each with a 16x16 spriteMatrix, evocative name, personality, openingLine, and x/y placed on a SOLID platform.
        5. parallaxConfig MUST have exactly 3 layers ordered by increasing depthFactor (e.g. 0.15, 0.35, 0.60).
        6. All hex colors MUST be valid 6-digit hex strings (#RRGGBB).
        7. audioParams: bpm in [75, 165], rootNoteHz in [65, 220], scaleIntervals length 5..7, bassPattern & leadArpPattern length 8 using -1 (rest) or scale indices 0..6.
        8. Adapt enemy pacing, NPC lore, hazards, and environmentalStoryText to the player's telemetry, ongoing Story Arc memory, and any active Reality Shift directive.
    """.trimIndent()

    private val byokJsonContract = """
        Top-level JSON keys:
        {
          "chunkIndex": int,
          "biome": {
            "name": string,
            "loreWhisper": string,
            "palette": { "skyTop":"#RRGGBB", "skyBottom":"#RRGGBB", "fogColor":"#RRGGBB", "platformFill":"#RRGGBB", "platformEdge":"#RRGGBB", "accentColor":"#RRGGBB" },
            "weatherType": "NONE"|"ASH"|"RAIN"|"FIREFLIES"|"SNOW"|"SPORES"|"EMBER"|"DATA_SHARDS",
            "parallaxConfig": [ { "depthFactor":0.15, "silhouetteType":"CRYSTALLINE_SPIRES"|"RUINED_MEGASTRUCTURES"|"ORGANIC_TENDRILS"|"JAGGED_PEAKS"|"FLOATING_MONOLITHS"|"ARCHIVE_SHELVES", "tintHex":"#RRGGBB", "density":0.6, "heightScale":0.7 } ] (EXACTLY 3 items),
            "physicsModifiers": { "gravityScale":0.5..1.6, "friction":0.65..0.96, "windForceX":-150..150 },
            "audioParams": { "bpm":75..165, "rootNoteHz":65..220, "scaleIntervals":[0,2,3,5,7,10], "bassPattern":[0,-1,3,-1,4,-1,2,-1], "leadArpPattern":[0,2,4,5,3,2,1,-1], "waveformTimbre":"SAWTOOTH"|"SQUARE"|"TRIANGLE"|"SINE" }
          },
          "archetypes": [ { "id":string, "name":string, "behaviorType":"PATROL"|"LEAPER"|"FLYING_SINE"|"CHARGER"|"PROJECTILE_SHOOTER", "speed":40..260, "hp":1..6, "hitbox":{"offsetX":4,"offsetY":4,"width":24,"height":24}, "attackPattern":{"rangePx":180,"cooldownMs":1200,"projectileSpeed":0,"telegraphMs":250}, "spriteMatrix":{"width":16,"height":16,"paletteEntries":[{"symbol":"A","hex":"#RRGGBB"}],"frames":[[16 strings of len 16]]} } ],
          "platforms": [ { "id":string, "x":0..2280, "y":240..740, "width":110..420, "height":24|160, "kind":"SOLID"|"ONE_WAY"|"CRUMBLING"|"BOUNCY" } ],
          "hazards": [ { "id":string, "x":number, "y":number, "width":32..160, "height":16..40, "kind":"SPIKES"|"LAVA_POOL"|"ACID_POOL"|"LASER_BEAM"|"VOID_RIFT", "damage":1|2 } ],
          "collectibles": [ { "id":string, "x":number, "y":number, "kind":"SHARD"|"CORE_FRAGMENT"|"HEAL_ORB"|"MUTATION_RELIC", "value":10..50 } ],
          "enemySpawns": [ { "id":string, "x":number, "y":number, "archetypeId":string, "patrolRangePx":80..260 } ],
          "npcSpawns": [ { "id":string, "name":string, "role":"GUIDE_MERCHANT"|"LOREKEEPER"|"COMPANION"|"RIVAL", "personality":string, "openingLine":string, "x":number, "y":number, "disposition":-50..50, "spriteMatrix":{"width":16,"height":16,"paletteEntries":[{"symbol":"A","hex":"#RRGGBB"}],"frames":[[16 strings of len 16]]} } ],
          "environmentalStoryText": string
        }
    """.trimIndent()

    suspend fun generateChunk(
        chunkIndex: Int,
        seedPrompt: String,
        previousBiome: BiomeConfig?,
        telemetry: TelemetryTracker,
        storySummary: String,
        realityShiftOverride: String?
    ): AiChunkResult = withContext(Dispatchers.IO) {
        val telemetryPayload = AiTelemetryPayload.fromTracker(
            tracker = telemetry,
            biomeName = previousBiome?.name ?: "Origin"
        )
        val prompt = buildDirectorPrompt(
            chunkIndex = chunkIndex,
            seedPrompt = seedPrompt,
            previousBiome = previousBiome,
            telemetry = telemetryPayload,
            storySummary = storySummary,
            realityShiftOverride = realityShiftOverride
        )

        var rawText: String? = null
        try {
            when (
                val routed = router.generateStructured(
                    taskKind = AiTaskKind.CHUNK_LAYOUT,
                    systemInstruction = systemInstruction,
                    userPrompt = prompt,
                    byokJsonContract = byokJsonContract
                )
            ) {
                is RoutedAiResult.Failure -> {
                    return@withContext AiChunkResult.Failure(
                        errorMessage = routed.errorMessage,
                        rawJson = null,
                        latencyMs = routed.latencyMs
                    )
                }

                is RoutedAiResult.Success -> {
                    rawText = routed.text
                    val dto = dtoAdapter.fromJson(rawText)
                        ?: return@withContext AiChunkResult.Failure(
                            errorMessage = "${routed.routeLabel}: Failed to deserialize ChunkLayout JSON.",
                            rawJson = rawText,
                            latencyMs = routed.latencyMs
                        )

                    val domain = dto.toDomain().copy(chunkIndex = chunkIndex)
                    val validated = SchemaValidator.validateChunkLayout(domain).getOrElse { err ->
                        Log.e(TAG, "Schema validation failed from ${routed.routeLabel} for chunk $chunkIndex: ${err.message}", err)
                        return@withContext AiChunkResult.Failure(
                            errorMessage = "${routed.routeLabel} schema invalid: ${err.message}",
                            rawJson = rawText,
                            latencyMs = routed.latencyMs
                        )
                    }

                    AiChunkResult.Success(
                        layout = validated,
                        rawJson = rawText,
                        providerLabel = routed.routeLabel,
                        latencyMs = routed.latencyMs
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Chunk $chunkIndex generation failed", e)
            AiChunkResult.Failure(
                errorMessage = e.localizedMessage ?: "AI generation error.",
                rawJson = rawText,
                latencyMs = 0L
            )
        }
    }

    private fun buildDirectorPrompt(
        chunkIndex: Int,
        seedPrompt: String,
        previousBiome: BiomeConfig?,
        telemetry: AiTelemetryPayload,
        storySummary: String,
        realityShiftOverride: String?
    ): String {
        val shiftSection = if (!realityShiftOverride.isNullOrBlank()) {
            """
            PRIORITY REALITY SHIFT DIRECTIVE (MUST APPLY IMMEDIATELY IN THIS CHUNK):
            "$realityShiftOverride"
            Dramatically mutate biome palette, physicsModifiers (e.g. gravityScale/friction/windForceX), hazards, weather, and enemy archetypes to embody this shift!
            """.trimIndent()
        } else {
            "Continue evolving the world naturally from the creative seed and ongoing story."
        }

        val prevBiomeSummary = if (previousBiome != null) {
            "Previous Biome: ${previousBiome.name} (gravityScale=${previousBiome.physicsModifiers.gravityScale}, bpm=${previousBiome.audioParams.bpm}, weather=${previousBiome.weatherType})"
        } else {
            "Opening Biome (Chunk 0)."
        }

        return """
            Creative World Seed: "$seedPrompt"
            Target Chunk Index: $chunkIndex
            $prevBiomeSummary

            Ongoing Story Memory:
            $storySummary

            Player Telemetry Summary:
            - Deaths: ${telemetry.deaths}
            - Average Speed: ${"%.1f".format(telemetry.averageSpeed)} px/s
            - Air Time Ratio: ${"%.2f".format(telemetry.airTimeRatio)}
            - Enemies Defeated: ${telemetry.enemiesDefeated}
            - Damage Taken: ${telemetry.damageTaken}
            - Collectibles Gathered: ${telemetry.collectiblesGathered}

            $shiftSection

            Use telemetry and story memory to tune pacing and craft NPCs that react to the player's journey.
        """.trimIndent()
    }
}
