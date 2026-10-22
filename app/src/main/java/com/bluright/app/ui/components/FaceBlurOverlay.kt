package com.bluright.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bluright.app.model.BlurStyle
import com.bluright.app.model.DetectedFace
import com.bluright.app.privacy.PrivacyDecision
import com.bluright.app.privacy.PrivacyPolicy
import com.bluright.app.ui.theme.CyanAccent
import com.bluright.app.ui.theme.CyanPrimary
import com.bluright.app.ui.theme.NavyCard
import com.bluright.app.ui.theme.NavyDarkBackground
import com.bluright.app.ui.theme.TealAccent
import kotlin.math.max

/**
 * High-performance Jetpack Compose Canvas overlay that projects normalized face bounding boxes
 * onto the CameraX view coordinates (using FILL_CENTER aspect ratio mapping) and renders
 * real-time privacy blur/pixelation masks over every detected face.
 */
@Composable
fun FaceBlurOverlay(
    faces: List<DetectedFace>,
    unmaskedFaces: List<DetectedFace> = emptyList(),
    frameAspectRatio: Float,
    blurStyle: BlurStyle,
    isFailClosedBlackoutActive: Boolean = false,
    decision: PrivacyDecision? = null,
    hasObservedOptOutInSession: Boolean = false,
    onFaceClick: ((DetectedFace) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewWidth = constraints.maxWidth.toFloat()
        val viewHeight = constraints.maxHeight.toFloat()

        if (viewWidth <= 0f || viewHeight <= 0f) return@BoxWithConstraints

        // CameraX PreviewView with ScaleType.FILL_CENTER fills the view, cropping the excess
        val safeFrameAspect = if (frameAspectRatio > 0.05f) frameAspectRatio else (9f / 16f)
        val viewAspect = viewWidth / viewHeight

        val (scaledW, scaledH, offX, offY) = if (viewAspect > safeFrameAspect) {
            // View is wider than camera frame -> scale by width, cropped on top/bottom
            val sw = viewWidth
            val sh = viewWidth / safeFrameAspect
            val ox = 0f
            val oy = (viewHeight - sh) / 2f
            TransformationInfo(sw, sh, ox, oy)
        } else {
            // View is taller than camera frame -> scale by height, cropped on left/right
            val sh = viewHeight
            val sw = viewHeight * safeFrameAspect
            val ox = (viewWidth - sw) / 2f
            val oy = 0f
            TransformationInfo(sw, sh, ox, oy)
        }

        val tapModifier = if (onFaceClick != null) {
            Modifier.pointerInput(faces, unmaskedFaces, scaledW, scaledH, offX, offY) {
                detectTapGestures { tapOffset ->
                    val allFaces = faces + unmaskedFaces
                    val clicked = allFaces.firstOrNull { face ->
                        val expanded = face.expandedAdaptive()
                        val boxLeft = expanded.normLeft * scaledW + offX
                        val boxTop = expanded.normTop * scaledH + offY
                        val boxWidth = expanded.normWidth * scaledW
                        val boxHeight = expanded.normHeight * scaledH
                        tapOffset.x in boxLeft..(boxLeft + boxWidth) &&
                            tapOffset.y in boxTop..(boxTop + boxHeight)
                    }
                    if (clicked != null) {
                        onFaceClick(clicked)
                    }
                }
            }
        } else Modifier

        Canvas(modifier = Modifier.fillMaxSize().then(tapModifier)) {
            val isBlackout = isFailClosedBlackoutActive ||
                (decision != null && PrivacyPolicy.shouldApplyFailClosedBlackout(decision, hasObservedOptOutInSession))

            if (isBlackout && faces.isEmpty()) {
                // Render fail-closed blackout shield over preview
                drawRect(
                    color = Color(0xF2050811),
                    topLeft = Offset.Zero,
                    size = size
                )

                val bannerW = (size.width * 0.90f).coerceAtMost(800f)
                val bannerH = 130f
                val bannerLeft = (size.width - bannerW) / 2f
                val bannerTop = (size.height - bannerH) / 2f

                drawRoundRect(
                    color = NavyDarkBackground.copy(alpha = 0.95f),
                    topLeft = Offset(bannerLeft, bannerTop),
                    size = Size(bannerW, bannerH),
                    cornerRadius = CornerRadius(16f, 16f),
                    style = Fill
                )
                drawRoundRect(
                    color = CyanPrimary,
                    topLeft = Offset(bannerLeft, bannerTop),
                    size = Size(bannerW, bannerH),
                    cornerRadius = CornerRadius(16f, 16f),
                    style = Stroke(width = 2.5f)
                )

                val headerStyle = TextStyle(
                    color = CyanPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                val subStyle = TextStyle(
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal
                )

                val t1 = textMeasurer.measure("FAIL-CLOSED PRIVACY SHIELD", headerStyle)
                drawText(
                    textMeasurer = textMeasurer,
                    text = "FAIL-CLOSED PRIVACY SHIELD",
                    style = headerStyle,
                    topLeft = Offset(bannerLeft + (bannerW - t1.size.width) / 2f, bannerTop + 20f)
                )

                val reasonText = if (decision?.isOptOutActive == true) {
                    "OPT-OUT ACTIVE // COVERAGE UNVERIFIED"
                } else {
                    "OPT-OUT OBSERVED IN SESSION // COVERAGE UNVERIFIED"
                }
                val t2 = textMeasurer.measure(reasonText, subStyle)
                drawText(
                    textMeasurer = textMeasurer,
                    text = reasonText,
                    style = subStyle,
                    topLeft = Offset(bannerLeft + (bannerW - t2.size.width) / 2f, bannerTop + 58f)
                )

                val t3 = textMeasurer.measure("0 PIXELS LEAKED IN EXPORT", subStyle)
                drawText(
                    textMeasurer = textMeasurer,
                    text = "0 PIXELS LEAKED IN EXPORT",
                    style = subStyle,
                    topLeft = Offset(bannerLeft + (bannerW - t3.size.width) / 2f, bannerTop + 88f)
                )
                return@Canvas
            }
            for (face in faces) {
                // Under OPT_OUT_ONLY, only mask faces mandated by the unified privacy policy
                val shouldMask = if (decision != null) {
                    PrivacyPolicy.shouldMaskFace(face, decision, hasObservedOptOutInSession)
                } else {
                    true
                }
                if (!shouldMask) {
                    continue
                }

                // Adaptive safety expansion (15-25%) identical to video export pipeline
                val expanded = face.expandedAdaptive()

                val boxLeft = expanded.normLeft * scaledW + offX
                val boxTop = expanded.normTop * scaledH + offY
                val boxWidth = expanded.normWidth * scaledW
                val boxHeight = expanded.normHeight * scaledH

                val faceRect = Rect(
                    offset = Offset(boxLeft, boxTop),
                    size = Size(boxWidth, boxHeight)
                )

                // Render the chosen blur/privacy mask style
                when (blurStyle) {
                    BlurStyle.PIXELATE -> drawPixelatedMosaicMask(faceRect)
                    BlurStyle.FROSTED -> drawFrostedGlassMask(faceRect)
                    BlurStyle.SOLID_SHIELD -> drawSolidShieldMask(faceRect)
                }

                // Cyber Privacy Corner Brackets
                drawCornerBrackets(faceRect, CyanPrimary)

                // Privacy Status Badge above the face
                drawFaceBadge(
                    faceRect = faceRect,
                    confidence = face.confidence,
                    textMeasurer = textMeasurer
                )
            }

            // Unmasked detected faces (e.g. operator or bystander with NO opt-out request)
            for (face in unmaskedFaces) {
                val boxLeft = face.normLeft * scaledW + offX
                val boxTop = face.normTop * scaledH + offY
                val boxWidth = face.normWidth * scaledW
                val boxHeight = face.normHeight * scaledH

                val faceRect = Rect(
                    offset = Offset(boxLeft, boxTop),
                    size = Size(boxWidth, boxHeight)
                )

                // Render subtle corner reticle without privacy blur
                drawCornerBrackets(faceRect, TealAccent.copy(alpha = 0.6f))

                // Clear Face Badge
                drawClearFaceBadge(
                    faceRect = faceRect,
                    confidence = face.confidence,
                    textMeasurer = textMeasurer
                )
            }
        }
    }
}

/**
 * Draws a pixelated mosaic grid with alternating dark navy & teal blocks and subtle grid lines.
 */
private fun DrawScope.drawPixelatedMosaicMask(rect: Rect) {
    val cols = 10
    val rows = 12
    val blockW = rect.width / cols
    val blockH = rect.height / rows

    val blockColors = listOf(
        Color(0xF0070D1E),
        Color(0xEA0D1B2A),
        Color(0xE00D2B3E),
        Color(0xE51B263B),
        Color(0xDD003B46)
    )

    // Clip to rounded rectangle
    val clipPath = Path().apply {
        addRoundRect(RoundRect(rect, CornerRadius(16f, 16f)))
    }

    // Outer subtle cyan glow shadow
    drawRoundRect(
        color = Color(0x3300E5FF),
        topLeft = rect.topLeft - Offset(4f, 4f),
        size = Size(rect.width + 8f, rect.height + 8f),
        cornerRadius = CornerRadius(20f, 20f)
    )

    // Draw individual pixel blocks
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            val cellLeft = rect.left + c * blockW
            val cellTop = rect.top + r * blockH
            val colorIndex = ((r * 17 + c * 31 + (r xor c)) and 0x7FFFFFFF) % blockColors.size
            val color = blockColors[colorIndex]

            drawRect(
                color = color,
                topLeft = Offset(cellLeft, cellTop),
                size = Size(blockW + 0.5f, blockH + 0.5f)
            )

            // Block subtle border for authentic mosaic texture
            drawRect(
                color = Color(0x2200E5FF),
                topLeft = Offset(cellLeft, cellTop),
                size = Size(blockW, blockH),
                style = Stroke(width = 0.5f)
            )
        }
    }

    // Outer border
    drawRoundRect(
        color = CyanPrimary.copy(alpha = 0.6f),
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = CornerRadius(16f, 16f),
        style = Stroke(width = 1.5f)
    )
}

/**
 * Draws a multi-layer frosted glass privacy mask with gradient and diagonal hash matrix lines.
 */
private fun DrawScope.drawFrostedGlassMask(rect: Rect) {
    // Multi-gradient frosted glass
    val gradient = Brush.linearGradient(
        colors = listOf(
            Color(0xF2070D1E),
            Color(0xE60D2B3E),
            Color(0xEA1B263B),
            Color(0xF2070D1E)
        ),
        start = rect.topLeft,
        end = rect.bottomRight
    )

    drawRoundRect(
        brush = gradient,
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = CornerRadius(20f, 20f)
    )

    // Diagonal frosted privacy lines
    val step = 14f
    var offset = 0f
    while (offset < rect.width + rect.height) {
        val startX = (rect.left + offset).coerceAtMost(rect.right)
        val startY = rect.top + max(0f, offset - rect.width)
        val endX = rect.left + max(0f, offset - rect.height)
        val endY = (rect.top + offset).coerceAtMost(rect.bottom)

        drawLine(
            color = Color(0x1A00E5FF),
            start = Offset(startX, startY),
            end = Offset(endX, endY),
            strokeWidth = 1f
        )
        offset += step
    }

    // Border
    drawRoundRect(
        color = TealAccent.copy(alpha = 0.8f),
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = CornerRadius(20f, 20f),
        style = Stroke(width = 2f)
    )
}

/**
 * Draws a solid high-tech privacy shield with cybernetic gradient and lock emblem.
 */
private fun DrawScope.drawSolidShieldMask(rect: Rect) {
    val gradient = Brush.radialGradient(
        colors = listOf(
            Color(0xF21B263B),
            Color(0xFA0D1B2A),
            Color(0xFF070D1E)
        ),
        center = rect.center,
        radius = max(rect.width, rect.height) * 0.7f
    )

    drawRoundRect(
        brush = gradient,
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = CornerRadius(24f, 24f)
    )

    // Inner shield glow
    drawRoundRect(
        color = CyanPrimary.copy(alpha = 0.25f),
        topLeft = rect.topLeft + Offset(6f, 6f),
        size = Size(rect.width - 12f, rect.height - 12f),
        cornerRadius = CornerRadius(18f, 18f),
        style = Stroke(width = 1f)
    )

    // Glowing border
    drawRoundRect(
        color = CyanPrimary,
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = CornerRadius(24f, 24f),
        style = Stroke(width = 2f)
    )
}

/**
 * Draws 4 futuristic corner brackets around the masked face bounding box.
 */
private fun DrawScope.drawCornerBrackets(rect: Rect, color: Color) {
    val bracketLen = minOf(rect.width, rect.height) * 0.22f
    val strokeW = 3f

    // Top-Left
    drawLine(color, Offset(rect.left - 2f, rect.top), Offset(rect.left + bracketLen, rect.top), strokeW)
    drawLine(color, Offset(rect.left, rect.top - 2f), Offset(rect.left, rect.top + bracketLen), strokeW)

    // Top-Right
    drawLine(color, Offset(rect.right + 2f, rect.top), Offset(rect.right - bracketLen, rect.top), strokeW)
    drawLine(color, Offset(rect.right, rect.top - 2f), Offset(rect.right, rect.top + bracketLen), strokeW)

    // Bottom-Left
    drawLine(color, Offset(rect.left - 2f, rect.bottom), Offset(rect.left + bracketLen, rect.bottom), strokeW)
    drawLine(color, Offset(rect.left, rect.bottom + 2f), Offset(rect.left, rect.bottom - bracketLen), strokeW)

    // Bottom-Right
    drawLine(color, Offset(rect.right + 2f, rect.bottom), Offset(rect.right - bracketLen, rect.bottom), strokeW)
    drawLine(color, Offset(rect.right, rect.bottom + 2f), Offset(rect.right, rect.bottom - bracketLen), strokeW)
}

/**
 * Renders a neat "PRIVACY MASKED • XX%" pill badge above each detected face.
 */
private fun DrawScope.drawFaceBadge(
    faceRect: Rect,
    confidence: Float,
    textMeasurer: TextMeasurer
) {
    val confPercent = (confidence * 100).toInt().coerceIn(1, 99)
    val label = "MASKED $confPercent%"

    val textStyle = TextStyle(
        color = CyanPrimary,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold
    )
    val textLayout = textMeasurer.measure(label, textStyle)

    val badgeW = textLayout.size.width + 16f
    val badgeH = textLayout.size.height + 8f

    val badgeLeft = (faceRect.left + (faceRect.width - badgeW) / 2f).coerceAtLeast(8f)
    val badgeTop = (faceRect.top - badgeH - 6f).coerceAtLeast(8f)

    // Badge Background
    drawRoundRect(
        color = NavyDarkBackground.copy(alpha = 0.92f),
        topLeft = Offset(badgeLeft, badgeTop),
        size = Size(badgeW, badgeH),
        cornerRadius = CornerRadius(8f, 8f),
        style = Fill
    )

    // Badge Border
    drawRoundRect(
        color = CyanAccent.copy(alpha = 0.8f),
        topLeft = Offset(badgeLeft, badgeTop),
        size = Size(badgeW, badgeH),
        cornerRadius = CornerRadius(8f, 8f),
        style = Stroke(width = 1f)
    )

    // Badge Text
    drawText(
        textMeasurer = textMeasurer,
        text = label,
        style = textStyle,
        topLeft = Offset(badgeLeft + 8f, badgeTop + 4f)
    )
}

/**
 * Renders a subtle "CLEAR • XX%" badge above detected faces that do not require privacy masking.
 */
private fun DrawScope.drawClearFaceBadge(
    faceRect: Rect,
    confidence: Float,
    textMeasurer: TextMeasurer
) {
    val confPercent = (confidence * 100).toInt().coerceIn(1, 99)
    val label = "CLEAR $confPercent%"

    val textStyle = TextStyle(
        color = TealAccent,
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold
    )
    val textLayout = textMeasurer.measure(label, textStyle)

    val badgeW = textLayout.size.width + 16f
    val badgeH = textLayout.size.height + 8f

    val badgeLeft = (faceRect.left + (faceRect.width - badgeW) / 2f).coerceAtLeast(8f)
    val badgeTop = (faceRect.top - badgeH - 6f).coerceAtLeast(8f)

    drawRoundRect(
        color = NavyDarkBackground.copy(alpha = 0.85f),
        topLeft = Offset(badgeLeft, badgeTop),
        size = Size(badgeW, badgeH),
        cornerRadius = CornerRadius(8f, 8f),
        style = Fill
    )

    drawRoundRect(
        color = TealAccent.copy(alpha = 0.6f),
        topLeft = Offset(badgeLeft, badgeTop),
        size = Size(badgeW, badgeH),
        cornerRadius = CornerRadius(8f, 8f),
        style = Stroke(width = 1f)
    )

    drawText(
        textMeasurer = textMeasurer,
        text = label,
        style = textStyle,
        topLeft = Offset(badgeLeft + 8f, badgeTop + 4f)
    )
}

private data class TransformationInfo(
    val scaledWidth: Float,
    val scaledHeight: Float,
    val offsetX: Float,
    val offsetY: Float
)
