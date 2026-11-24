package com.example.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.example.schemas.BiomeConfig
import com.example.schemas.ParallaxLayerConfig
import com.example.schemas.ParallaxSilhouetteType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Procedural multi-layer parallax background renderer driven by BiomeConfig rules:
 * sky gradient stops, celestial focal glow, 3 silhouette layers, and horizon fog.
 */
object ParallaxRenderer {

    fun DrawScope.renderBiomeBackdrop(
        biome: BiomeConfig,
        camX: Float,
        timeSec: Float
    ) {
        val skyTop = WorldCanvasRenderer.parseHexColor(biome.palette.skyTop, Color(0xFF0C0A1E))
        val skyBottom = WorldCanvasRenderer.parseHexColor(biome.palette.skyBottom, Color(0xFF1D2448))
        val fogColor = WorldCanvasRenderer.parseHexColor(biome.palette.fogColor, Color(0xFF3B2E6B))
        val accentColor = WorldCanvasRenderer.parseHexColor(biome.palette.accentColor, Color(0xFFFF4D8D))

        // 1. Sky vertical gradient
        drawRect(
            brush = Brush.verticalGradient(listOf(skyTop, skyBottom)),
            size = size
        )

        // 2. Subtle celestial disc & atmospheric halo drifting at ultra-far depth
        val discCenterX = size.width * 0.76f - ((camX * 0.02f) % (size.width * 0.35f))
        val discCenterY = size.height * 0.24f
        val discRadius = (size.minDimension * 0.085f).coerceIn(28f, 84f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accentColor.copy(alpha = 0.26f), Color.Transparent),
                center = Offset(discCenterX, discCenterY),
                radius = discRadius * 3.1f
            ),
            radius = discRadius * 3.1f,
            center = Offset(discCenterX, discCenterY)
        )
        drawCircle(
            color = accentColor.copy(alpha = 0.28f),
            radius = discRadius,
            center = Offset(discCenterX, discCenterY)
        )

        // 3. Three procedural parallax silhouette layers
        biome.parallaxConfig.forEachIndexed { idx, layer ->
            drawSilhouetteLayer(
                layer = layer,
                layerIndex = idx,
                camX = camX,
                timeSec = timeSec,
                accentColor = accentColor
            )
        }

        // 4. Atmospheric horizon fog
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, fogColor.copy(alpha = 0.38f)),
                startY = size.height * 0.42f,
                endY = size.height
            ),
            size = size
        )
    }

    private fun DrawScope.drawSilhouetteLayer(
        layer: ParallaxLayerConfig,
        layerIndex: Int,
        camX: Float,
        timeSec: Float,
        accentColor: Color
    ) {
        val tint = WorldCanvasRenderer.parseHexColor(layer.tintHex, Color(0xFF1F264A))
        val span = (170f / layer.density.coerceIn(0.2f, 1.0f)).coerceIn(110f, 420f)
        val scrollOffset = (camX * layer.depthFactor) % span
        val baseY = size.height
        val maxPeakH = size.height * layer.heightScale * 0.72f
        val count = (size.width / span).toInt() + 4
        val startTile = ((camX * layer.depthFactor) / span).toInt() - 1

        when (layer.silhouetteType) {
            ParallaxSilhouetteType.CRYSTALLINE_SPIRES,
            ParallaxSilhouetteType.JAGGED_PEAKS -> {
                val path = Path()
                path.moveTo(-span, baseY)
                for (i in 0..count) {
                    val worldIdx = startTile + i
                    val x0 = i * span - scrollOffset
                    val hHash = abs(sin(worldIdx * 17.3 + layerIndex * 31.7)).toFloat()
                    val h = (0.34f + 0.66f * hHash) * maxPeakH
                    val shoulder = h * (0.28f + 0.14f * abs(cos(worldIdx * 9.1)).toFloat())
                    path.lineTo(x0 + span * 0.48f, baseY - h)
                    path.lineTo(x0 + span, baseY - shoulder)
                }
                path.lineTo(size.width + span, baseY)
                path.close()
                drawPath(path, color = tint.copy(alpha = 0.88f))
            }

            ParallaxSilhouetteType.RUINED_MEGASTRUCTURES,
            ParallaxSilhouetteType.ARCHIVE_SHELVES -> {
                for (i in 0..count) {
                    val worldIdx = startTile + i
                    val x0 = i * span - scrollOffset
                    val hHash = abs(cos(worldIdx * 11.9 + layerIndex * 19.1)).toFloat()
                    val h = (0.30f + 0.70f * hHash) * maxPeakH
                    val colW = span * 0.70f
                    drawRect(
                        color = tint.copy(alpha = 0.84f),
                        topLeft = Offset(x0, baseY - h),
                        size = Size(colW, h)
                    )
                    // Diegetic lit shelf/window pips on nearer layers
                    if (layerIndex >= 1 && worldIdx % 2 == 0) {
                        val pipY = baseY - h + 18f
                        drawRect(
                            color = accentColor.copy(alpha = 0.25f),
                            topLeft = Offset(x0 + colW * 0.2f, pipY),
                            size = Size(colW * 0.6f, 4f)
                        )
                    }
                }
            }

            ParallaxSilhouetteType.ORGANIC_TENDRILS -> {
                for (i in 0..count) {
                    val worldIdx = startTile + i
                    val x0 = i * span - scrollOffset
                    val hHash = abs(sin(worldIdx * 23.1 + layerIndex * 7.3)).toFloat()
                    val sway = sin(timeSec * 1.1f + worldIdx) * 8f
                    val h = (0.32f + 0.58f * hHash) * maxPeakH
                    val stalkW = span * 0.22f
                    drawRect(
                        color = tint.copy(alpha = 0.80f),
                        topLeft = Offset(x0 + span * 0.35f + sway * 0.4f, baseY - h),
                        size = Size(stalkW, h)
                    )
                    drawCircle(
                        color = accentColor.copy(alpha = 0.16f),
                        radius = stalkW * 0.9f,
                        center = Offset(x0 + span * 0.35f + stalkW * 0.5f + sway * 0.4f, baseY - h)
                    )
                }
            }

            ParallaxSilhouetteType.FLOATING_MONOLITHS -> {
                for (i in 0..count) {
                    val worldIdx = startTile + i
                    val x0 = i * span - scrollOffset
                    val hHash = abs(sin(worldIdx * 19.7 + layerIndex * 13.3)).toFloat()
                    val hover = sin(timeSec * 1.4f + worldIdx * 0.8f) * 7f
                    val monH = (0.24f + 0.32f * hHash) * maxPeakH
                    val monW = span * 0.38f
                    val topY = size.height * (0.18f + hHash * 0.34f) + hover
                    drawRect(
                        color = tint.copy(alpha = 0.82f),
                        topLeft = Offset(x0 + span * 0.25f, topY),
                        size = Size(monW, monH)
                    )
                    drawRect(
                        color = accentColor.copy(alpha = 0.28f),
                        topLeft = Offset(x0 + span * 0.25f, topY),
                        size = Size(monW, 3f)
                    )
                }
            }
        }
    }
}
