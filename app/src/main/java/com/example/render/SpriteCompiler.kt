package com.example.render

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.engine.PhysicsConstants
import com.example.schemas.SpriteMatrix

data class CompiledSpriteSheet(
    val widthPx: Int,
    val heightPx: Int,
    val scaleFactor: Int,
    val frames: List<ImageBitmap>
)

/**
 * Compiles compact 16x16 or 24x24 indexed pixel matrices into nearest-neighbor
 * scaled ImageBitmap spritesheets cached in memory for zero-allocation 60 FPS drawing.
 */
object SpriteCompiler {

    private val cache = HashMap<String, CompiledSpriteSheet>(64)

    fun clearCache() {
        cache.clear()
    }

    fun getOrCompile(
        cacheKey: String,
        matrix: SpriteMatrix,
        scaleFactor: Int = PhysicsConstants.SPRITE_SCALE_FACTOR
    ): CompiledSpriteSheet {
        val fullKey = "${cacheKey}_${scaleFactor}_${matrix.hashCode()}"
        return cache.getOrPut(fullKey) {
            compileMatrix(matrix, scaleFactor)
        }
    }

    fun getOrCompilePlayer(accentHex: String, edgeHex: String): CompiledSpriteSheet {
        val playerMatrix = SpriteMatrix(
            width = 16,
            height = 16,
            paletteMap = mapOf(
                "A" to "#11162B",
                "B" to edgeHex,
                "C" to accentHex,
                "D" to "#E2E8F0",
                "E" to "#263159"
            ),
            frames = listOf(
                // Frame 0: Idle / stride A
                listOf(
                    ".....BBBBBB.....",
                    "....BAAAAAAB....",
                    "...BAAAAAAAAB...",
                    "...BAACCCCCAB...",
                    "...BAACDDDDAB...",
                    "...BAAAAAAAAB...",
                    "....BAAAAAAB....",
                    "..CCCEEEEEB.....",
                    ".CC.BEEEEEEB....",
                    "....BEEECCEB....",
                    "....BEEEEEEB....",
                    ".....BAAAAB.....",
                    "....BAA..AAB....",
                    "....BB....BB....",
                    "...AA......AA...",
                    "................"
                ),
                // Frame 1: Stride B
                listOf(
                    "................",
                    ".....BBBBBB.....",
                    "....BAAAAAAB....",
                    "...BAAAAAAAAB...",
                    "...BAACCCCCAB...",
                    "...BAACDDDDAB...",
                    "...BAAAAAAAAB...",
                    ".CC.BAAAAAAB....",
                    "CC..CEEEEEB.....",
                    "....BEEEEEEB....",
                    "....BEEECCEB....",
                    "....BEEEEEEB....",
                    ".....BAAAAB.....",
                    ".....BB..BB.....",
                    "....AA....AA....",
                    "................"
                )
            )
        )
        return getOrCompile("player_${accentHex}_$edgeHex", playerMatrix, PhysicsConstants.SPRITE_SCALE_FACTOR)
    }

    private fun compileMatrix(matrix: SpriteMatrix, scaleFactor: Int): CompiledSpriteSheet {
        val scaledW = matrix.width * scaleFactor
        val scaledH = matrix.height * scaleFactor
        val colorLookup = HashMap<Char, Int>(8)

        for ((symbol, hex) in matrix.paletteMap) {
            val ch = symbol.firstOrNull() ?: continue
            colorLookup[ch] = try {
                android.graphics.Color.parseColor(hex)
            } catch (_: Exception) {
                android.graphics.Color.MAGENTA
            }
        }

        val bitmaps = matrix.frames.map { rows ->
            val pixels = IntArray(scaledW * scaledH)
            for (y in 0 until matrix.height) {
                val row = rows.getOrNull(y).orEmpty()
                for (x in 0 until matrix.width) {
                    val ch = row.getOrNull(x) ?: '.'
                    if (ch == '.') continue
                    val argb = colorLookup[ch] ?: continue
                    val baseY = y * scaleFactor
                    val baseX = x * scaleFactor
                    for (sy in 0 until scaleFactor) {
                        val rowOffset = (baseY + sy) * scaledW + baseX
                        for (sx in 0 until scaleFactor) {
                            pixels[rowOffset + sx] = argb
                        }
                    }
                }
            }
            val bmp = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
            bmp.setPixels(pixels, 0, scaledW, 0, 0, scaledW, scaledH)
            bmp.asImageBitmap()
        }

        return CompiledSpriteSheet(
            widthPx = scaledW,
            heightPx = scaledH,
            scaleFactor = scaleFactor,
            frames = bitmaps
        )
    }
}
