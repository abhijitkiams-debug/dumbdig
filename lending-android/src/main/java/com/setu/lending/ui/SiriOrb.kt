package com.setu.lending.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import com.setu.lending.assistant.Phase
import kotlin.math.cos
import kotlin.math.sin

/**
 * A Siri-inspired voice orb: soft, overlapping gradient blobs that rotate and
 * breathe, glow brighter while speaking, and swell with the caller's voice
 * amplitude while listening.
 */
@Composable
fun SiriOrb(
    phase: Phase,
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "orb")
    val rotation by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "rot"
    )
    val breathe by transition.animateFloat(
        initialValue = 0f, targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "breathe"
    )
    // speaking gets its own faster shimmer
    val speakPulse by transition.animateFloat(
        initialValue = 0f, targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Restart),
        label = "speak"
    )

    val activity = when (phase) {
        Phase.LISTENING -> amplitude.coerceIn(0f, 1f)
        Phase.SPEAKING -> 0.45f + 0.25f * ((sin(speakPulse) + 1f) / 2f)
        Phase.THINKING -> 0.30f
        else -> 0.15f
    }

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val base = minOf(size.width, size.height) / 2f
        val breatheScale = 1f + 0.04f * sin(breathe)
        val core = base * (0.52f + 0.30f * activity) * breatheScale

        // outer halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Accent2.copy(alpha = 0.28f + 0.25f * activity), Color.Transparent),
                center = Offset(cx, cy),
                radius = base
            ),
            radius = base,
            center = Offset(cx, cy)
        )

        // three coloured blobs orbiting, blended additively for the Siri sheen
        val blobs = listOf(
            Triple(Accent1, 0f, 0.42f),
            Triple(Accent3, 120f, 0.36f),
            Triple(Accent4, 240f, 0.40f)
        )
        val orbit = core * (0.16f + 0.20f * activity)
        for ((color, phaseDeg, sizeFactor) in blobs) {
            val ang = Math.toRadians((rotation + phaseDeg).toDouble())
            val bx = cx + orbit * cos(ang).toFloat()
            val by = cy + orbit * sin(ang).toFloat()
            drawBlob(bx, by, core * sizeFactor, color.copy(alpha = 0.85f))
        }

        // bright inner core
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.9f),
                    Accent2.copy(alpha = 0.55f),
                    Color.Transparent
                ),
                center = Offset(cx, cy),
                radius = core * 0.9f
            ),
            radius = core * 0.9f,
            center = Offset(cx, cy)
        )

        // listening ripple ring
        if (phase == Phase.LISTENING) {
            drawCircle(
                color = Color.White.copy(alpha = 0.12f + 0.30f * activity),
                radius = core * (1.05f + 0.25f * activity),
                center = Offset(cx, cy),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
            )
        }
    }
}

private fun DrawScope.drawBlob(x: Float, y: Float, radius: Float, color: Color) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color, color.copy(alpha = 0f)),
            center = Offset(x, y),
            radius = radius
        ),
        radius = radius,
        center = Offset(x, y),
        style = Fill,
        blendMode = BlendMode.Plus
    )
}
