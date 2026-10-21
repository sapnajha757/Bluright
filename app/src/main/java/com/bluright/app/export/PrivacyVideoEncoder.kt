package com.bluright.app.export

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Encodes masked video frames into an MP4 container using Android's native MediaCodec & MediaMuxer.
 *
 * PRIVACY GUARANTEE:
 * Only pre-masked video frames are delivered to this encoder. Raw, unmasked frames are
 * never written to disk or temporary cache files. The exported MP4 contains exclusively
 * privacy-masked frames.
 */
class PrivacyVideoEncoder(
    private val outputFile: File,
    val targetWidth: Int = 480,
    val targetHeight: Int = 640,
    val frameRate: Int = 24,
    val bitRate: Int = 2_000_000
) {
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false

    private val bufferInfo = MediaCodec.BufferInfo()
    private val nv12Buffer = ByteArray(targetWidth * targetHeight * 3 / 2)
    private var isClosed = false

    init {
        // Enforce storage availability check before recording starts (> 30 MB free required)
        val freeBytes = outputFile.parentFile?.freeSpace ?: 0L
        if (freeBytes in 1 until (30L * 1024L * 1024L)) {
            throw IllegalStateException("Low storage space on device: only ${freeBytes / (1024 * 1024)} MB available.")
        }

        initializeEncoder()
    }

    private fun initializeEncoder() {
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                targetWidth,
                targetHeight
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second keyframe interval
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            encoder = codec

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            Log.i(TAG, "PrivacyVideoEncoder initialized: ${targetWidth}x${targetHeight} @ ${frameRate}fps -> ${outputFile.name}")
        } catch (e: Exception) {
            cleanup()
            throw IllegalStateException("Failed to initialize video encoder: ${e.message}", e)
        }
    }

    /**
     * Encodes a masked frame bitmap into the video stream.
     */
    @Synchronized
    fun encodeFrame(bitmap: Bitmap, presentationTimeUs: Long) {
        if (isClosed) return
        val codec = encoder ?: return

        // 1. Rescale bitmap if it does not match target encoder dimensions
        val preparedBitmap = if (bitmap.width != targetWidth || bitmap.height != targetHeight) {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        } else {
            bitmap
        }

        // 2. Convert ARGB Bitmap to NV12 YUV format
        convertBitmapToNv12(preparedBitmap, targetWidth, targetHeight, nv12Buffer)

        if (preparedBitmap != bitmap) {
            preparedBitmap.recycle()
        }

        // 3. Queue input buffer to hardware encoder
        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex >= 0) {
            val inputBuffer: ByteBuffer? = codec.getInputBuffer(inputIndex)
            inputBuffer?.clear()
            inputBuffer?.put(nv12Buffer)
            codec.queueInputBuffer(inputIndex, 0, nv12Buffer.size, presentationTimeUs, 0)
        }

        // 4. Drain encoded packets into MP4 muxer
        drainEncoder(endOfStream = false)
    }

    /**
     * Drains pending output buffers from the encoder and writes them to the MP4 file.
     */
    private fun drainEncoder(endOfStream: Boolean) {
        val codec = encoder ?: return
        val m = muxer ?: return

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    Log.w(TAG, "Format changed twice during encoding")
                } else {
                    val newFormat = codec.outputFormat
                    videoTrackIndex = m.addTrack(newFormat)
                    m.start()
                    muxerStarted = true
                    Log.i(TAG, "MediaMuxer started with video track $videoTrackIndex")
                }
            } else if (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)
                if (outputBuffer != null && muxerStarted && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    if (bufferInfo.size != 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        m.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                    }
                }

                codec.releaseOutputBuffer(outputIndex, false)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }
    }

    /**
     * Signals End of Stream, flushes remaining frames, and closes the MP4 file.
     */
    @Synchronized
    fun finish(): File {
        if (isClosed) return outputFile
        isClosed = true

        try {
            val codec = encoder
            if (codec != null) {
                // Signal End of Stream (EOS)
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drainEncoder(endOfStream = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception during EOS drain: ${e.message}")
        } finally {
            cleanup()
        }

        Log.i(TAG, "Privacy video export finalized: ${outputFile.name} (${outputFile.length()} bytes)")
        return outputFile
    }

    fun cleanup() {
        try {
            encoder?.stop()
            encoder?.release()
        } catch (_: Exception) {}
        encoder = null

        try {
            if (muxerStarted) {
                muxer?.stop()
            }
            muxer?.release()
        } catch (_: Exception) {}
        muxer = null
        muxerStarted = false
    }

    companion object {
        private const val TAG = "PrivacyVideoEncoder"
        private const val TIMEOUT_US = 10_000L

        /**
         * Converts an ARGB Bitmap into an NV12 byte array (ITU-R BT.601 color space).
         */
        fun convertBitmapToNv12(bitmap: Bitmap, width: Int, height: Int, outNv12: ByteArray) {
            val argb = IntArray(width * height)
            bitmap.getPixels(argb, 0, width, 0, 0, width, height)

            val ySize = width * height
            var yIndex = 0
            var uvIndex = ySize

            for (j in 0 until height) {
                for (i in 0 until width) {
                    val c = argb[j * width + i]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF

                    // Standard ITU-R BT.601 luminance
                    val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    outNv12[yIndex++] = y.coerceIn(0, 255).toByte()

                    // Interleaved UV (NV12: U then V) sampled on even coordinates
                    if (j % 2 == 0 && i % 2 == 0) {
                        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        outNv12[uvIndex++] = u.coerceIn(0, 255).toByte()
                        outNv12[uvIndex++] = v.coerceIn(0, 255).toByte()
                    }
                }
            }
        }
    }
}
