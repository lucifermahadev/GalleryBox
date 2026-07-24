@file:Suppress(
    "unused",
    "OPT_IN_USAGE",
    "UNCHECKED_CAST",
    "ObsoleteSdkInt",
    "DEPRECATION",
    "UnsafeOptInUsageError",
    "SpellCheckingInspection"
)
@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.gallerybox.engine

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.*
import androidx.media3.transformer.*
import com.caverock.androidsvg.SVG
import com.gallerybox.data.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*

@Singleton
class EditStateManager @Inject constructor() {
    private val undoStack = ArrayDeque<EditState>()
    private val redoStack = ArrayDeque<EditState>()

    var currentState: EditState = EditState()
        private set

    var selectedLayerIds = mutableSetOf<String>()

    fun pushState(newState: EditState) {
        undoStack.addLast(currentState)
        if (undoStack.size > 50) {
            undoStack.removeFirst()
        }
        redoStack.clear()
        currentState = newState
    }

    fun undo(): EditState? {
        if (undoStack.isEmpty()) {
            return null
        }
        redoStack.addLast(currentState)
        currentState = undoStack.removeLast()
        return currentState
    }

    fun redo(): EditState? {
        if (redoStack.isEmpty()) {
            return null
        }
        undoStack.addLast(currentState)
        currentState = redoStack.removeLast()
        return currentState
    }

    fun isPointInLayer(
        x: Float,
        y: Float,
        layerX: Float,
        layerY: Float,
        width: Float,
        height: Float,
        rotation: Float,
        scale: Float = 1f
    ): Boolean {
        val dx = x - layerX
        val dy = y - layerY
        val rad = Math.toRadians((-rotation).toDouble())
        val rx = dx * cos(rad) - dy * sin(rad)
        val ry = dx * sin(rad) + dy * cos(rad)
        val halfW = (width * scale) / 2f
        val halfH = (height * scale) / 2f
        return rx in -halfW..halfW && ry in -halfH..halfH
    }
}

@UnstableApi
@Singleton
class EditingEngine @Inject constructor(
    private val photoEngine: PhotoEditorEngine,
    private val videoEngine: VideoEditorEngine,
    private val lutEngine: LutEngine,
    private val exportEngine: ExportEngine
) {
    suspend fun createPreview(bitmap: Bitmap, state: EditState, renderOverlays: Boolean = false): Bitmap {
        return photoEngine.createPreview(bitmap, state, renderOverlays)
    }

    suspend fun extractFrame(uri: Uri, posMs: Long): File? {
        return videoEngine.extractFrame(uri, posMs)
    }

    suspend fun loadLut(path: String): CubeLut? {
        return lutEngine.loadLut(path)
    }

    suspend fun saveMedia(
        uri: Uri,
        state: EditState,
        targetWidth: Int = 1920,
        targetHeight: Int = 1080,
        targetFps: Int = 30,
        videoBitrate: Int = 15000000,
        isVideo: Boolean,
        asSticker: Boolean,
        useH265: Boolean = false,
        isPreviewExport: Boolean = false,
        onProgress: (Float) -> Unit
    ): File? {
        return exportEngine.saveMedia(
            uri,
            state,
            targetWidth,
            targetHeight,
            targetFps,
            videoBitrate,
            isVideo,
            asSticker,
            useH265,
            isPreviewExport,
            onProgress
        )
    }

    fun cancelExport() {
        videoEngine.cancel()
        exportEngine.cancel()
    }
}

@Singleton
class LutEngine @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun loadLut(path: String): CubeLut? {
        return withContext(Dispatchers.IO) {
            try {
                context.assets.open(path).bufferedReader().use { reader ->
                    var size = 0
                    val data = mutableListOf<Float>()

                    reader.forEachLine { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("LUT_3D_SIZE")) {
                            val parts = trimmed.split(Regex("\\s+"))
                            size = parts.last().toIntOrNull() ?: 0
                        } else if (!trimmed.startsWith("#") && !trimmed.startsWith("TITLE") && !trimmed.startsWith("DOMAIN_") && trimmed.isNotBlank()) {
                            val rgb = trimmed.split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
                            if (rgb.size >= 3) {
                                data.addAll(rgb.take(3))
                            }
                        }
                    }
                    if (size > 0 && data.isNotEmpty()) {
                        CubeLut(size, data.toFloatArray())
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e("LutEngine", "Load LUT failed", e)
                null
            }
        }
    }

    fun applyCpuLut(src: Bitmap, lut: CubeLut, intensity: Float): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val s = lut.size
        val d = lut.data

        fun lerp(v0: Float, v1: Float, t: Float): Float = v0 + (v1 - v0) * t

        for (i in pixels.indices) {
            val c = pixels[i]
            val a = (c shr 24) and 0xff
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff

            val rF = (r / 255f) * (s - 1)
            val gF = (g / 255f) * (s - 1)
            val bF = (b / 255f) * (s - 1)

            val r0 = rF.toInt().coerceIn(0, s - 1)
            val g0 = gF.toInt().coerceIn(0, s - 1)
            val b0 = bF.toInt().coerceIn(0, s - 1)

            val r1 = (r0 + 1).coerceIn(0, s - 1)
            val g1 = (g0 + 1).coerceIn(0, s - 1)
            val b1 = (b0 + 1).coerceIn(0, s - 1)

            val fr = rF - r0
            val fg = gF - g0
            val fb = bF - b0

            var nr = 0f
            var ng = 0f
            var nb = 0f

            for (ch in 0..2) {
                val c000 = d[((r0 + g0 * s + b0 * s * s) * 3) + ch]
                val c100 = d[((r1 + g0 * s + b0 * s * s) * 3) + ch]
                val c010 = d[((r0 + g1 * s + b0 * s * s) * 3) + ch]
                val c110 = d[((r1 + g1 * s + b0 * s * s) * 3) + ch]
                val c001 = d[((r0 + g0 * s + b1 * s * s) * 3) + ch]
                val c101 = d[((r1 + g0 * s + b1 * s * s) * 3) + ch]
                val c011 = d[((r0 + g1 * s + b1 * s * s) * 3) + ch]
                val c111 = d[((r1 + g1 * s + b1 * s * s) * 3) + ch]

                val cx00 = lerp(c000, c100, fr)
                val cx10 = lerp(c010, c110, fr)
                val cx01 = lerp(c001, c101, fr)
                val cx11 = lerp(c011, c111, fr)

                val cxy0 = lerp(cx00, cx10, fg)
                val cxy1 = lerp(cx01, cx11, fg)

                val fVal = lerp(cxy0, cxy1, fb)

                when (ch) {
                    0 -> nr = fVal * 255f
                    1 -> ng = fVal * 255f
                    2 -> nb = fVal * 255f
                }
            }

            val finalR = (r + (nr - r) * intensity).toInt().coerceIn(0, 255)
            val finalG = (g + (ng - g) * intensity).toInt().coerceIn(0, 255)
            val finalB = (b + (nb - b) * intensity).toInt().coerceIn(0, 255)

            pixels[i] = (a shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
        }

        return Bitmap.createBitmap(pixels, w, h, src.config ?: Bitmap.Config.ARGB_8888)
    }
}

@Singleton
class StickerEngine @Inject constructor(@ApplicationContext private val context: Context) {
    private val stickerCache = LruCache<String, Bitmap>(80)

    private fun getDiskCacheFile(key: String): File {
        return File(context.cacheDir, "sticker_$key.png")
    }

    fun getStickerBitmap(assetPath: String, targetBaseResolution: Int = 1080): Bitmap? {
        val cacheKey = "${assetPath.replace("/", "_")}_$targetBaseResolution"

        val cachedBitmap = stickerCache.get(cacheKey)
        if (cachedBitmap != null) {
            return cachedBitmap
        }

        val diskFile = getDiskCacheFile(cacheKey)
        if (diskFile.exists()) {
            try {
                val diskBitmap = BitmapFactory.decodeFile(diskFile.absolutePath)
                if (diskBitmap != null) {
                    stickerCache.put(cacheKey, diskBitmap)
                    return diskBitmap
                }
            } catch (e: Exception) {
                Log.e("StickerEngine", "Disk cache read failed", e)
            }
        }

        return try {
            val svg = SVG.getFromAsset(context.assets, assetPath)

            val docWidth = if (svg.documentWidth > 0) svg.documentWidth else 500f
            val docHeight = if (svg.documentHeight > 0) svg.documentHeight else 500f
            val aspect = docWidth / docHeight

            val w = (targetBaseResolution * 0.25f).toInt()
            val h = (w / aspect).toInt()

            svg.documentWidth = w.toFloat()
            svg.documentHeight = h.toFloat()

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            svg.renderToCanvas(canvas)

            stickerCache.put(cacheKey, bmp)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    FileOutputStream(diskFile).use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (e: Exception) {
                    Log.e("StickerEngine", "Disk cache write failed", e)
                }
            }

            bmp
        } catch (e: Exception) {
            Log.e("StickerEngine", "Load SVG failed", e)
            null
        }
    }
}

@Singleton
class TextEngine @Inject constructor() {
    private val textBitmapCache = LruCache<String, Bitmap>(20)

    fun getTextBitmap(layer: TextLayer, targetBaseResolution: Int = 1080): Bitmap? {
        val cacheKey = "${layer.id}_${layer.text}_${layer.color}_${layer.size}_${layer.opacity}_$targetBaseResolution"

        val cachedBitmap = textBitmapCache.get(cacheKey)
        if (cachedBitmap != null) {
            return cachedBitmap
        }

        if (layer.text.isBlank()) {
            return null
        }

        return try {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
                color = layer.color
                textSize = (targetBaseResolution * (layer.size / 100f)).coerceAtLeast(12f)
                typeface = Typeface.DEFAULT_BOLD
                isSubpixelText = true
                isLinearText = true
                isDither = true
            }

            val maxWidth = (targetBaseResolution * 0.85f).toInt()

            val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(layer.text, 0, layer.text.length, paint, maxWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                StaticLayout(layer.text, paint, maxWidth, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
            }

            if (layout.width <= 0 || layout.height <= 0) {
                return null
            }

            val bmp = Bitmap.createBitmap((layout.width + 24).toInt(), (layout.height + 24).toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.translate(12f, 12f)
            layout.draw(canvas)

            textBitmapCache.put(cacheKey, bmp)
            bmp
        } catch (e: Exception) {
            Log.e("TextEngine", "Render Text failed", e)
            null
        }
    }
}

@Singleton
class PhotoEditorEngine @Inject constructor(
    private val lutEngine: LutEngine,
    private val stickerEngine: StickerEngine,
    private val textEngine: TextEngine
) {
    suspend fun createPreview(bitmap: Bitmap, state: EditState, renderOverlays: Boolean): Bitmap {
        return withContext(Dispatchers.Default) {
            var res = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return@withContext bitmap

            // 1. Geometry: Crop
            val cropRect = state.cropRect
            if (cropRect != null) {
                if (cropRect.left > 0f || cropRect.top > 0f || cropRect.right < 1f || cropRect.bottom < 1f) {
                    val x = (cropRect.left * res.width).toInt().coerceAtLeast(0)
                    val y = (cropRect.top * res.height).toInt().coerceAtLeast(0)
                    val w = (cropRect.width() * res.width).toInt().coerceAtMost(res.width - x)
                    val h = (cropRect.height() * res.height).toInt().coerceAtMost(res.height - y)
                    if (w > 0 && h > 0) {
                        val newBmp = Bitmap.createBitmap(res, x, y, w, h)
                        if (newBmp !== res) {
                            if (res !== bitmap) res.recycle()
                            res = newBmp
                        }
                    }
                }
            }

            // 2. Adjustments
            val cm = ColorMatrix()

            val sc = (1f + state.brightness) * (2.0f.pow(state.exposure))
            val scaleMatrix = ColorMatrix()
            scaleMatrix.setScale(sc, sc, sc, 1f)
            cm.postConcat(scaleMatrix)

            val c = state.contrast
            val t = (-.5f * c + .5f) * 255f
            val contrastMatrix = ColorMatrix(
                floatArrayOf(
                    c,  0f, 0f, 0f, t,
                    0f, c,  0f, 0f, t,
                    0f, 0f, c,  0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(contrastMatrix)

            val saturationMatrix = ColorMatrix()
            saturationMatrix.setSaturation(state.saturation)
            cm.postConcat(saturationMatrix)

            val tempTintMatrix = ColorMatrix(
                floatArrayOf(
                    1f + state.temperature * 0.1f, 0f,                            0f, 0f, 0f,
                    0f,                            1f + state.tint * 0.1f,        0f, 0f, 0f,
                    0f,                            1f - state.temperature * 0.1f, 0f, 0f, 0f,
                    0f,                            0f,                            0f, 1f, 0f
                )
            )
            cm.postConcat(tempTintMatrix)

            var out = Bitmap.createBitmap(res.width, res.height, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            paint.colorFilter = ColorMatrixColorFilter(cm)
            val canvas = Canvas(out)
            canvas.drawBitmap(res, 0f, 0f, paint)

            if (res !== bitmap) {
                res.recycle()
            }

            if (state.highlights != 0f || state.shadows != 0f) {
                val pixels = IntArray(out.width * out.height)
                out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
                for (i in pixels.indices) {
                    val col = pixels[i]
                    val a = (col shr 24) and 0xff
                    var r = (col shr 16) and 0xff
                    var g = (col shr 8) and 0xff
                    var b = col and 0xff

                    val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                    val sMsk = (1f - lum) * (1f - lum)
                    val hMsk = lum * lum

                    r = (r + (state.shadows * sMsk * 128f) - (state.highlights * hMsk * 128f)).toInt()
                    g = (g + (state.shadows * sMsk * 128f) - (state.highlights * hMsk * 128f)).toInt()
                    b = (b + (state.shadows * sMsk * 128f) - (state.highlights * hMsk * 128f)).toInt()

                    val safeR = r.coerceIn(0, 255)
                    val safeG = g.coerceIn(0, 255)
                    val safeB = b.coerceIn(0, 255)

                    pixels[i] = (a shl 24) or (safeR shl 16) or (safeG shl 8) or safeB
                }
                out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
            }

            // 3. LUT
            val currentLut = state.lutData
            if (currentLut != null && state.lutIntensity > 0.001f) {
                val lutBmp = lutEngine.applyCpuLut(out, currentLut, state.lutIntensity)
                if (out !== bitmap) {
                    out.recycle()
                }
                out = lutBmp
            }

            // 4. Overlays
            if (renderOverlays) {
                val sCan = Canvas(out)
                val resV = max(out.width, out.height)

                val matrix = Matrix()
                val overlayPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

                val activeStickers = state.stickers.filter { it.isVisible }.sortedBy { it.zIndex }
                for (s in activeStickers) {
                    val bmp = stickerEngine.getStickerBitmap(s.assetPath, resV)
                    if (bmp != null) {
                        matrix.reset()
                        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                        matrix.postScale(s.scale, s.scale)
                        matrix.postRotate(s.rotation)
                        matrix.postTranslate(s.x * out.width, s.y * out.height)

                        overlayPaint.alpha = (s.opacity * 255).toInt().coerceIn(0, 255)
                        sCan.drawBitmap(bmp, matrix, overlayPaint)
                    }
                }

                val activeTextLayers = state.textLayers.filter { it.isVisible }.sortedBy { it.zIndex }
                for (t in activeTextLayers) {
                    val bmp = textEngine.getTextBitmap(t, resV)
                    if (bmp != null) {
                        matrix.reset()
                        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                        matrix.postRotate(t.rotation)
                        matrix.postTranslate(t.x * out.width, t.y * out.height)

                        overlayPaint.alpha = (t.opacity * 255).toInt().coerceIn(0, 255)
                        sCan.drawBitmap(bmp, matrix, overlayPaint)
                    }
                }
            }

            // 5. Geometry Finalization
            val sX = if (state.flipHorizontal) -1f else 1f
            val sY = if (state.flipVertical) -1f else 1f

            if (state.rotationDegrees != 0f || state.straightenDegrees != 0f || sX != 1f || sY != 1f) {
                val finalMatrix = Matrix()
                finalMatrix.postRotate(
                    state.rotationDegrees + state.straightenDegrees,
                    out.width / 2f,
                    out.height / 2f
                )
                finalMatrix.postScale(sX, sY, out.width / 2f, out.height / 2f)

                val rotated = Bitmap.createBitmap(out, 0, 0, out.width, out.height, finalMatrix, true)
                if (rotated !== out && out !== bitmap) {
                    out.recycle()
                }
                return@withContext rotated
            }

            out
        }
    }
}

@UnstableApi
@Singleton
class VideoEditorEngine @Inject constructor(@ApplicationContext private val context: Context) {
    private var activeTransformer: Transformer? = null

    suspend fun extractFrame(uri: Uri, posMs: Long): File? {
        return withContext(Dispatchers.IO) {
            try {
                val galleryDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "GalleryBox")
                galleryDir.mkdirs()

                val f = File(galleryDir, "Frame_${System.currentTimeMillis()}.jpg")
                val retriever = android.media.MediaMetadataRetriever()

                retriever.setDataSource(context, uri)
                val frameBitmap = retriever.getFrameAtTime(posMs * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

                if (frameBitmap != null) {
                    val fos = FileOutputStream(f)
                    frameBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
                    fos.close()
                }

                retriever.release()
                f
            } catch (e: Exception) {
                Log.e("VideoEngine", "Extract frame failed", e)
                null
            }
        }
    }

    fun cancel() {
        activeTransformer?.cancel()
        activeTransformer = null
    }
}

@UnstableApi
@Singleton
class ExportEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoEngine: PhotoEditorEngine
) {
    private var activeTransformer: Transformer? = null

    suspend fun saveMedia(
        uri: Uri,
        state: EditState,
        targetWidth: Int = 1920,
        targetHeight: Int = 1080,
        targetFps: Int = 30,
        videoBitrate: Int = 15000000,
        isVideo: Boolean,
        asSticker: Boolean,
        useH265: Boolean = false,
        isPreviewExport: Boolean = false,
        onProgress: (Float) -> Unit
    ): File? {
        return withContext(Dispatchers.IO) {
            try {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val galleryDir = File(picturesDir, "GalleryBox")
                galleryDir.mkdirs()

                val extension = if (isVideo) {
                    "mp4"
                } else if (asSticker) {
                    "png"
                } else {
                    "jpg"
                }

                val file = File(galleryDir, "Saved_${System.currentTimeMillis()}_${UUID.randomUUID()}.$extension")

                if (!isVideo) {
                    onProgress(0.1f)
                    val sampleSize = if (isPreviewExport) 2 else 1

                    val src = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val source = ImageDecoder.createSource(context.contentResolver, uri)
                        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            decoder.isMutableRequired = true
                            decoder.setTargetSampleSize(sampleSize)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val options = BitmapFactory.Options()
                        options.inSampleSize = sampleSize
                        options.inMutable = true

                        val inputStream = context.contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            val decoded = BitmapFactory.decodeStream(inputStream, null, options)
                            inputStream.close()
                            decoded ?: MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                        } else {
                            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                        }
                    }

                    onProgress(0.4f)
                    val out = photoEngine.createPreview(src, state, renderOverlays = true)

                    onProgress(0.8f)

                    val fos = FileOutputStream(file)
                    val format = if (asSticker) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    out.compress(format, 95, fos)
                    fos.close()

                    if (src !== out) {
                        src.recycle()
                    }
                    out.recycle()

                    onProgress(1f)
                    return@withContext file
                }

                // --- VIDEO EXPORT PIPELINE ---
                val bldr = Media3Item.Builder()
                bldr.setUri(uri)

                val ts = runCatching { state.trimStartMs }.getOrDefault(0L)
                val te = runCatching { state.trimEndMs }.getOrDefault(0L)

                if (ts > 0L || te > 0L) {
                    val clipConfig = Media3Item.ClippingConfiguration.Builder()
                    clipConfig.setStartPositionMs(ts)
                    if (te > ts) {
                        clipConfig.setEndPositionMs(te)
                    }
                    bldr.setClippingConfiguration(clipConfig.build())
                }

                val effs = mutableListOf<androidx.media3.common.Effect>()
                val auds = mutableListOf<AudioProcessor>()

                if (state.videoVolume != 1f) {
                    auds.add(VolumeAudioProcessor(state.videoVolume))
                }

                if (targetWidth > 0 && targetHeight > 0) {
                    effs.add(Presentation.createForWidthAndHeight(targetWidth, targetHeight, Presentation.LAYOUT_SCALE_TO_FIT))
                }

                val cropRect = state.cropRect
                if (cropRect != null) {
                    if (cropRect.left > 0f || cropRect.top > 0f || cropRect.right < 1f || cropRect.bottom < 1f) {
                        effs.add(Crop(cropRect.left * 2f - 1f, cropRect.right * 2f - 1f, 1f - cropRect.bottom * 2f, 1f - cropRect.top * 2f))
                    }
                }

                val sx = if (state.flipHorizontal) -1f else 1f
                val sy = if (state.flipVertical) -1f else 1f

                if (state.rotationDegrees != 0f || sx != 1f || sy != 1f) {
                    val transformBuilder = ScaleAndRotateTransformation.Builder()
                    transformBuilder.setRotationDegrees(state.rotationDegrees)
                    transformBuilder.setScale(sx, sy)
                    effs.add(transformBuilder.build())
                }

                val transformerBuilder = Transformer.Builder(context)
                val mimeType = if (useH265) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
                transformerBuilder.setVideoMimeType(mimeType)
                activeTransformer = transformerBuilder.build()

                val editedMediaItemBuilder = EditedMediaItem.Builder(bldr.build())
                editedMediaItemBuilder.setEffects(Effects(auds, effs))
                editedMediaItemBuilder.setRemoveAudio(state.isMuted)
                editedMediaItemBuilder.setFrameRate(targetFps)

                val editedMediaItem = editedMediaItemBuilder.build()
                val sequence = EditedMediaItemSequence(editedMediaItem)
                val seqs = mutableListOf(sequence)

                val p = ProgressHolder()

                suspendCancellableCoroutine<Unit> { cont ->
                    val listener = object : Transformer.Listener {
                        override fun onCompleted(c: Composition, r: ExportResult) {
                            activeTransformer = null
                            onProgress(1f)
                            if (cont.isActive) {
                                cont.resume(Unit)
                            }
                        }

                        override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                            activeTransformer = null
                            if (cont.isActive) {
                                cont.resumeWithException(e)
                            }
                        }
                    }

                    activeTransformer?.addListener(listener)

                    val composition = Composition.Builder(seqs).build()
                    activeTransformer?.start(composition, file.absolutePath)

                    CoroutineScope(Dispatchers.Main).launch {
                        while (cont.isActive) {
                            val stateProgress = activeTransformer?.getProgress(p)
                            if (stateProgress == Transformer.PROGRESS_STATE_AVAILABLE) {
                                onProgress(p.progress / 100f)
                            }
                            delay(100)
                        }
                    }

                    cont.invokeOnCancellation {
                        activeTransformer?.cancel()
                        activeTransformer = null
                    }
                }

                file
            } catch (e: Exception) {
                Log.e("ExportEngine", "Save exception", e)
                null
            }
        }
    }

    fun cancel() {
        activeTransformer?.cancel()
        activeTransformer = null
    }
}

@UnstableApi
class VolumeAudioProcessor(private val volume: Float) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            return inputAudioFormat
        } else {
            return AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            return
        }

        val buffer = replaceOutputBuffer(inputBuffer.remaining())

        while (inputBuffer.hasRemaining()) {
            val originalValue = inputBuffer.getShort()
            val adjustedValue = (originalValue * volume).toInt()
            val clampedValue = adjustedValue.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer.putShort(clampedValue.toShort())
        }

        buffer.flip()
    }
}