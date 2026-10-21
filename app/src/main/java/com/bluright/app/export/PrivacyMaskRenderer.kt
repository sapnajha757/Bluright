package com.bluright.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.bluright.app.model.BlurStyle
import com.bluright.app.model.DetectedFace
import kotlin.math.max
import kotlin.math.min

/**
 * Permanently burns privacy masks (Cyber Mosaic, Frosted Glass, Shield) directly
 * into the raw pixels of video frame Bitmaps before they reach the video encoder.
 *
 * Guarantees that exported MP4 video frames permanently contain the privacy masks
 * in the encoded video stream, regardless of player, platform, or presentation layer.
 */
object PrivacyMaskRenderer {

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#00E5FF") // CyanPrimary
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val tagPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        textSize = 14f
        isFakeBoldText = true
        isAntiAlias = true
    }

    private val tagBgPaint = Paint().apply {
        color = Color.parseColor("#CC070D1E") // Semi-transparent dark navy
        style = Paint.Style.FILL
    }

    private val shieldPaint = Paint().apply {
        color = Color.parseColor("#EE0B1528") // Deep navy opaque shield
        style = Paint.Style.FILL
    }

    private val blackoutPaint = Paint().apply {
        color = Color.parseColor("#050811") // Opaque deep blackout
        style = Paint.Style.FILL
    }

    private val blackoutBannerPaint = Paint().apply {
        color = Color.parseColor("#00E5FF") // CyanPrimary
        textSize = 18f
        isFakeBoldText = true
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val blackoutSubtextPaint = Paint().apply {
        color = Color.parseColor("#94A3B8") // Slate gray
        textSize = 13f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val blackoutBoxPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    /**
     * Applies a fail-closed full-frame blackout over the entire bitmap.
     * Guaranteed zero-pixel visual leakage when an opt-out beacon is active
     * but facial coverage cannot be 100% verified by computer vision.
     */
    fun burnFullFrameBlackout(
        bitmap: Bitmap,
        reason: String = "OPT-OUT SIGNAL ACTIVE // COVERAGE UNVERIFIED"
    ): Boolean {
        if (!bitmap.isMutable) return false
        val width = bitmap.width
        val height = bitmap.height
        val canvas = Canvas(bitmap)

        // 1. Completely paint entire frame opaque black
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), blackoutPaint)

        // 2. Render prominent fail-closed privacy shield banner
        val centerX = width / 2f
        val centerY = height / 2f
        val bannerW = (width * 0.85f).coerceAtMost(420f)
        val bannerH = 100f
        val bannerRect = RectF(
            centerX - bannerW / 2f,
            centerY - bannerH / 2f,
            centerX + bannerW / 2f,
            centerY + bannerH / 2f
        )

        canvas.drawRoundRect(bannerRect, 12f, 12f, blackoutBoxPaint)
        canvas.drawText("FAIL-CLOSED PRIVACY SHIELD", centerX, centerY - 15f, blackoutBannerPaint)
        canvas.drawText("OPT-OUT ACTIVE // ZERO PIXELS LEAKED", centerX, centerY + 10f, blackoutSubtextPaint)
        canvas.drawText(reason, centerX, centerY + 30f, blackoutSubtextPaint)

        return true
    }

    /**
     * Permanently burns privacy masks for the given faces into the [bitmap].
     *
     * @param bitmap Mutable bitmap whose pixels will be directly modified in-place.
     * @param faces List of detected faces with normalized [0..1] bounding coordinates.
     * @param style Selected visual privacy mask style.
     * @return Number of faces successfully masked onto the bitmap.
     */
    fun burnMask(
        bitmap: Bitmap,
        faces: List<DetectedFace>,
        style: BlurStyle = BlurStyle.PIXELATE
    ): Int {
        if (faces.isEmpty() || !bitmap.isMutable) {
            return 0
        }

        val width = bitmap.width
        val height = bitmap.height
        val canvas = Canvas(bitmap)

        var maskedCount = 0

        for (face in faces) {
            // Adaptive safety expansion margin (15% - 25%) identical to preview overlay
            val expanded = face.expandedAdaptive()

            val pixelLeft = max(0, (expanded.normLeft * width).toInt())
            val pixelTop = max(0, (expanded.normTop * height).toInt())
            val pixelRight = min(width, (expanded.normRight * width).toInt())
            val pixelBottom = min(height, (expanded.normBottom * height).toInt())

            val faceW = pixelRight - pixelLeft
            val faceH = pixelBottom - pixelTop

            if (faceW <= 4 || faceH <= 4) continue

            when (style) {
                BlurStyle.PIXELATE -> {
                    // 1. Geometric Mosaic Block Pixelation
                    applyMosaicPixelation(bitmap, pixelLeft, pixelTop, pixelRight, pixelBottom, blockSize = max(8, faceW / 10))

                    // 2. Draw glowing cyber privacy border & confidence badge
                    val rectF = RectF(pixelLeft.toFloat(), pixelTop.toFloat(), pixelRight.toFloat(), pixelBottom.toFloat())
                    canvas.drawRoundRect(rectF, 12f, 12f, borderPaint)

                    val confText = "MASKED ${(face.confidence * 100).toInt()}%"
                    val textW = tagPaint.measureText(confText)
                    val tagRect = RectF(
                        pixelLeft + 6f,
                        pixelBottom - 22f,
                        pixelLeft + textW + 16f,
                        pixelBottom - 4f
                    )
                    canvas.drawRoundRect(tagRect, 4f, 4f, tagBgPaint)
                    canvas.drawText(confText, pixelLeft + 11f, pixelBottom - 8f, tagPaint)
                }

                BlurStyle.FROSTED -> {
                    // Multi-pass box blur simulation via downscale + upscale
                    applyFrostedGlassBlur(bitmap, pixelLeft, pixelTop, pixelRight, pixelBottom)
                    val rectF = RectF(pixelLeft.toFloat(), pixelTop.toFloat(), pixelRight.toFloat(), pixelBottom.toFloat())
                    canvas.drawRoundRect(rectF, 16f, 16f, borderPaint)
                }

                BlurStyle.SOLID_SHIELD -> {
                    // Full opaque privacy shield
                    val rectF = RectF(pixelLeft.toFloat(), pixelTop.toFloat(), pixelRight.toFloat(), pixelBottom.toFloat())
                    canvas.drawRoundRect(rectF, 18f, 18f, shieldPaint)
                    canvas.drawRoundRect(rectF, 18f, 18f, borderPaint)

                    val tagText = "BLURIGHT PRIVACY SHIELD"
                    val textW = tagPaint.measureText(tagText)
                    canvas.drawText(tagText, rectF.centerX() - textW / 2f, rectF.centerY() + 5f, tagPaint)
                }
            }

            maskedCount++
        }

        return maskedCount
    }

    /**
     * Efficiently downscales the face region into low-resolution blocks and paints
     * them back onto the bitmap with nearest-neighbor interpolation to create the mosaic.
     */
    private fun applyMosaicPixelation(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        blockSize: Int
    ) {
        val w = right - left
        val h = bottom - top

        val blocksX = max(1, w / blockSize)
        val blocksY = max(1, h / blockSize)

        val srcPixels = IntArray(w * h)
        bitmap.getPixels(srcPixels, 0, w, left, top, w, h)

        for (by in 0 until blocksY) {
            val startY = by * blockSize
            val endY = min(h, startY + blockSize)

            for (bx in 0 until blocksX) {
                val startX = bx * blockSize
                val endX = min(w, startX + blockSize)

                // Compute block average color
                var sumR = 0
                var sumG = 0
                var sumB = 0
                var count = 0

                for (y in startY until endY) {
                    val rowOffset = y * w
                    for (x in startX until endX) {
                        val c = srcPixels[rowOffset + x]
                        sumR += (c shr 16) and 0xFF
                        sumG += (c shr 8) and 0xFF
                        sumB += c and 0xFF
                        count++
                    }
                }

                if (count == 0) continue
                val avgColor = Color.rgb(sumR / count, sumG / count, sumB / count)

                // Fill block with average color
                for (y in startY until endY) {
                    val rowOffset = y * w
                    for (x in startX until endX) {
                        srcPixels[rowOffset + x] = avgColor
                    }
                }
            }
        }

        bitmap.setPixels(srcPixels, 0, w, left, top, w, h)
    }

    /**
     * Applies frosted glass blur via low-resolution downsampling and bilinear upscaling.
     */
    private fun applyFrostedGlassBlur(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val w = right - left
        val h = bottom - top
        if (w <= 8 || h <= 8) return

        try {
            val faceCropped = Bitmap.createBitmap(bitmap, left, top, w, h)
            val tiny = Bitmap.createScaledBitmap(faceCropped, max(4, w / 16), max(4, h / 16), true)
            val blurred = Bitmap.createScaledBitmap(tiny, w, h, true)

            val canvas = Canvas(bitmap)
            canvas.drawBitmap(blurred, left.toFloat(), top.toFloat(), null)

            faceCropped.recycle()
            tiny.recycle()
            blurred.recycle()
        } catch (_: Exception) {
            // Fallback to mosaic if memory allocation fails
            applyMosaicPixelation(bitmap, left, top, right, bottom, max(8, w / 8))
        }
    }
}
