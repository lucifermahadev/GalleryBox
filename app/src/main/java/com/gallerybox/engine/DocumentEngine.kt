@file:Suppress("BlockingMethodInNonBlockingContext", "UNUSED_PARAMETER", "unused", "FunctionName", "MemberVisibilityCanBePrivate", "UnsafeOptInUsageError", "Deprecation")
package com.gallerybox.engine

import android.app.ActivityManager
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.util.Xml
import android.util.Log
import androidx.room.*
import com.gallerybox.data.DocumentMetadata
import com.gallerybox.data.DocumentMetadataDao
import com.opencsv.CSVParserBuilder
import com.opencsv.CSVReaderBuilder
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.sl.usermodel.PlaceableShape
import org.apache.poi.sl.usermodel.SlideShowFactory
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xwpf.usermodel.*
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.*
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource
import kotlin.math.max
import kotlin.math.min

// ==========================================
// CORE DATA MODELS & ROOM ENTITIES
// ==========================================
enum class DocumentType(val priority: Int) {
    PDF(0), WORD(1), EXCEL(2), SLIDE(3), JSON(4), XML(5), HTML(6), CODE(7), TXT(8), CSV(9), EPUB(10), MARKDOWN(11), ZIP(12), RTF(13), SYSTEM(14), UNKNOWN(15)
}

enum class DocumentSortType {
    DATE, SIZE, NAME, RECENT
}

data class SearchOptions(
    val query: String,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val isRegex: Boolean = false
)

data class WordRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val fontSize: Int? = 16,
    val colorHex: String? = "#000000",
    val isSubscript: Boolean = false,
    val isSuperscript: Boolean = false,
    val fontFamily: String? = null,
    val isHighlighted: Boolean = false
)

data class PageMargins(
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float,
    val isLandscape: Boolean
)

data class TableRow(val cells: List<TableCell>)
data class TableCell(val blocks: List<WordBlock>)

sealed class WordBlock {
    data class Paragraph(
        val runs: List<WordRun>,
        val alignment: String? = null,
        val headingLevel: Int? = null,
        val isListItem: Boolean = false
    ) : WordBlock()

    data class Table(val rows: List<TableRow>) : WordBlock()

    data class Image(
        val byteArray: ByteArray,
        val extension: String,
        val fileName: String,
        val inline: Boolean = false
    ) : WordBlock() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Image
            if (!byteArray.contentEquals(other.byteArray)) return false
            return true
        }

        override fun hashCode(): Int {
            return byteArray.contentHashCode()
        }
    }

    data class Header(val blocks: List<WordBlock>) : WordBlock()

    data class Footer(val blocks: List<WordBlock>) : WordBlock()
}

data class VirtualSheet(
    val name: String,
    val rows: List<VirtualRow>,
    val rowCount: Int,
    val columnCount: Int,
    val mergedRegions: List<String> = emptyList()
)

data class VirtualRow(
    val rowIndex: Int,
    val cells: List<VirtualCell>,
    val isHidden: Boolean
)

data class VirtualCell(
    val columnIndex: Int,
    val displayValue: String,
    val rawValue: String,
    val isFormula: Boolean,
    val formulaString: String? = null,
    val comment: String? = null,
    val style: CellStyle? = null,
    val isHidden: Boolean = false
)

data class CellStyle(
    val bgColor: String?,
    val textColor: String?,
    val bold: Boolean,
    val italic: Boolean,
    val alignment: String,
    val hasBorders: Boolean
)

sealed class TextReadResult {
    data class Success(val text: String, val syntaxMode: String, val lineOffsets: List<Long>) : TextReadResult()
    data class Preview(val text: String, val syntaxMode: String, val lineOffsets: List<Long>, val totalBytes: Long) : TextReadResult()
    data class Paged(val uri: Uri, val encoding: Charset, val totalBytes: Long) : TextReadResult()
    data class BinaryFile(val confidence: Float) : TextReadResult()
    data class Error(val message: String, val exception: Throwable? = null) : TextReadResult()
}

data class ZipEntryItem(
    val name: String,
    val size: Long,
    val compressedSize: Long,
    val time: Long,
    val crc: Long,
    val isDirectory: Boolean
)

data class EpubChapter(
    val id: String,
    val title: String,
    val contentHtml: String,
    val isCover: Boolean = false
)

sealed class PdfLoadResult {
    data class Success(val pageCount: Int) : PdfLoadResult()
    data class Encrypted(val message: String) : PdfLoadResult()
    data class Error(val message: String, val exception: Throwable? = null) : PdfLoadResult()
}

sealed class EngineResult {
    data class OpenPdf(val uri: Uri, val session: PdfRenderSession) : EngineResult()
    data class OpenWord(val blocks: List<WordBlock>) : EngineResult()
    data class OpenExcel(val sheets: List<VirtualSheet>) : EngineResult()
    data class OpenCsv(val sheet: VirtualSheet) : EngineResult()
    data class OpenSlide(val pages: List<SlidePage>) : EngineResult()
    data class OpenEpub(val chapters: List<EpubChapter>) : EngineResult()
    data class OpenZip(val contents: List<ZipEntryItem>) : EngineResult()
    data class OpenText(val content: String, val type: DocumentType) : EngineResult()
    data class Unsupported(val format: String, val mimeType: String) : EngineResult()
    data class Error(val message: String) : EngineResult()
}

data class SlidePage(
    val width: Float,
    val height: Float,
    val elements: List<SlideElement>
)

sealed class SlideElement {
    abstract val x: Float
    abstract val y: Float
    abstract val width: Float
    abstract val height: Float

    data class Text(
        val text: String,
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val fontSize: Float? = null
    ) : SlideElement()

    data class Image(
        val byteArray: ByteArray,
        val extension: String,
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float
    ) : SlideElement() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Image
            if (!byteArray.contentEquals(other.byteArray)) return false
            return true
        }

        override fun hashCode(): Int {
            return byteArray.contentHashCode()
        }
    }

    data class Table(
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float,
        val rows: Int,
        val cols: Int
    ) : SlideElement()

    data class Shape(
        val shapeType: String,
        val text: String?,
        override val x: Float,
        override val y: Float,
        override val width: Float,
        override val height: Float
    ) : SlideElement()
}

sealed class CacheEntry {
    data class PdfPage(val bitmap: Bitmap, val timestamp: Long = System.currentTimeMillis()) : CacheEntry()
}

data class DocumentFile(
    val id: Long,
    val name: String,
    val uri: Uri,
    val size: Long,
    val dateModified: Long,
    val mimeType: String?,
    val type: DocumentType
)



// ==========================================
// THREADING & DISPATCHERS
// ==========================================
@Singleton
class DocumentDispatcherProvider @Inject constructor() {
    private val executor = Executors.newFixedThreadPool(min(Runtime.getRuntime().availableProcessors(), 4))
    val ioDispatcher = executor.asCoroutineDispatcher()
    val defaultDispatcher = Dispatchers.Default

    fun shutdown() {
        executor.shutdown()
    }
}

// ==========================================
// SEARCH UTILITIES
// ==========================================
fun String.matchesOptions(options: SearchOptions): Boolean {
    if (options.isRegex) {
        return Regex(
            options.query,
            if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        ).containsMatchIn(this)
    }

    if (options.wholeWord) {
        return Regex(
            "\\b${Regex.escape(options.query)}\\b",
            if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        ).containsMatchIn(this)
    }

    return this.contains(options.query, ignoreCase = !options.caseSensitive)
}

// ==========================================
// DETECTION & VALIDATION ENGINES
// ==========================================
@Singleton
class ContainerValidationEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun validateOOXML(uri: Uri, requiredFile: String): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zis ->
                    var hasContentTypes = false
                    var hasRequired = false
                    var entry = zis.nextEntry

                    while (entry != null) {
                        if (entry.name == "[Content_Types].xml") {
                            hasContentTypes = true
                        }
                        if (entry.name.contains(requiredFile)) {
                            hasRequired = true
                        }
                        if (hasContentTypes && hasRequired) {
                            return true
                        }
                        entry = zis.nextEntry
                    }
                    false
                }
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun isEncryptedOOXML(uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(8)
                val read = stream.read(buffer)
                if (read >= 8) {
                    val hex = buffer.take(8).joinToString("") { "%02X".format(it) }
                    hex.startsWith("D0CF11E0")
                } else {
                    false
                }
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
}

@Singleton
class FileDetectionEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val containerValidation: ContainerValidationEngine
) {
    private val codeExts = setOf("kt", "java", "cpp", "c", "py", "js", "ts", "gradle", "sh", "bat", "cs", "swift", "go", "rs", "yaml", "xml", "json", "html", "htm")
    private val legacyExts = setOf("doc", "xls", "ppt", "rtf", "odt", "ods", "odp", "epub")

    fun detect(uri: Uri, name: String, fallbackMime: String?): DocumentType {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)

        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val h = ByteArray(8)
                val bytesRead = stream.read(h, 0, 8)
                val hex = h.take(bytesRead).joinToString("") { "%02X".format(it) }

                when {
                    hex.startsWith("25504446") -> return DocumentType.PDF
                    hex.startsWith("504B0304") -> return when {
                        ext == "epub" -> DocumentType.EPUB
                        ext == "zip" -> DocumentType.ZIP
                        containerValidation.validateOOXML(uri, "word/document.xml") || ext == "odt" -> DocumentType.WORD
                        containerValidation.validateOOXML(uri, "xl/workbook.xml") || ext == "ods" -> DocumentType.EXCEL
                        containerValidation.validateOOXML(uri, "ppt/presentation.xml") || ext == "odp" -> DocumentType.SLIDE
                        else -> DocumentType.ZIP
                    }
                    hex.startsWith("D0CF11E0") -> return when (ext) {
                        "xls" -> DocumentType.EXCEL
                        "ppt" -> DocumentType.SLIDE
                        else -> DocumentType.WORD
                    }
                    hex.startsWith("7B5C7274") -> return DocumentType.RTF
                }
            }
        } catch (e: Exception) {

        }

        val safeMime = fallbackMime?.lowercase(Locale.US) ?: "*/*"

        return when {
            ext == "csv" || safeMime.contains("csv") -> DocumentType.CSV
            ext == "json" || safeMime.contains("json") -> DocumentType.JSON
            ext == "xml" || safeMime.contains("xml") -> DocumentType.XML
            ext in listOf("html", "htm") || safeMime.contains("html") -> DocumentType.HTML
            ext == "md" || safeMime.contains("markdown") -> DocumentType.MARKDOWN
            ext in codeExts -> DocumentType.CODE
            ext == "epub" || safeMime.contains("epub") -> DocumentType.EPUB
            ext == "zip" || safeMime.contains("zip") -> DocumentType.ZIP
            ext == "txt" || safeMime.startsWith("text/") -> DocumentType.TXT
            ext in legacyExts -> when(ext) {
                "xls", "ods" -> DocumentType.EXCEL
                "ppt", "odp" -> DocumentType.SLIDE
                "rtf" -> DocumentType.RTF
                else -> DocumentType.WORD
            }
            else -> DocumentType.UNKNOWN
        }
    }
}

@Singleton
class ValidationEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val containerValidation: ContainerValidationEngine,
    private val provider: DocumentDispatcherProvider
) {
    suspend fun validatePdf(uri: Uri): Boolean = withContext(provider.ioDispatcher) {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                val r = PdfRenderer(fd)
                val valid = r.pageCount > 0
                r.close()
                valid
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun validateDocx(uri: Uri): Boolean = withContext(provider.ioDispatcher) {
        containerValidation.validateOOXML(uri, "word/document.xml") || uri.toString().endsWith(".doc") || uri.toString().endsWith(".rtf")
    }

    suspend fun validateXlsx(uri: Uri): Boolean = withContext(provider.ioDispatcher) {
        containerValidation.validateOOXML(uri, "xl/workbook.xml") || uri.toString().endsWith(".xls") || uri.toString().endsWith(".csv")
    }

    suspend fun validatePptx(uri: Uri): Boolean = withContext(provider.ioDispatcher) {
        containerValidation.validateOOXML(uri, "ppt/presentation.xml") || uri.toString().endsWith(".ppt")
    }
}

@Singleton
class EncodingDetectionEngine @Inject constructor() {
    fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Charset.forName("UTF-8")
        }
        if (bytes.size >= 4 && bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() && bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte()) {
            return Charset.forName("UTF-32BE")
        }
        if (bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() && bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()) {
            return Charset.forName("UTF-32LE")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Charset.forName("UTF-16BE")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Charset.forName("UTF-16LE")
        }
        return Charset.defaultCharset()
    }

    fun detectCharset(context: Context, uri: Uri): Charset {
        return try {
            context.contentResolver.openInputStream(uri)?.use { i ->
                val b = ByteArray(4)
                i.read(b)
                detectCharset(b)
            } ?: Charset.defaultCharset()
        } catch (e: Exception) {
            Charset.defaultCharset()
        }
    }
}

@Singleton
class DelimiterDetectionEngine @Inject constructor() {
    fun detect(context: Context, uri: Uri, charset: Charset, fallback: Char): Char {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream, charset)).use { r ->
                    val sample = (1..5).mapNotNull { r.readLine() }.joinToString("\n")
                    val counts = mapOf(
                        ',' to sample.count { it == ',' },
                        ';' to sample.count { it == ';' },
                        '\t' to sample.count { it == '\t' },
                        '|' to sample.count { it == '|' }
                    )
                    counts.maxByOrNull { it.value }?.key ?: fallback
                }
            } ?: fallback
        } catch (e: Exception) {
            fallback
        }
    }
}

// ==========================================
// CACHE & METADATA
// ==========================================
@Singleton
class MetadataEngine @Inject constructor(
    private val metadataDao: DocumentMetadataDao,
    private val provider: DocumentDispatcherProvider
) {
    suspend fun saveMetadata(metadata: DocumentMetadata) =
        withContext(provider.ioDispatcher) {
            metadataDao.insert(metadata)
        }

    suspend fun getMetadata(id: Long): DocumentMetadata? =
        withContext(provider.ioDispatcher) {
            metadataDao.getById(id)
        }
}

@Singleton
class SmartCacheEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val memCache: LruCache<String, CacheEntry>
    private val diskCacheDir = File(context.cacheDir, "smart_docs").apply { mkdirs() }

    init {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val maxMemory = min((am.memoryClass * 1024 * 1024 / 8), 128 * 1024 * 1024)

        memCache = object : LruCache<String, CacheEntry>(maxMemory) {
            override fun sizeOf(key: String, value: CacheEntry): Int {
                return if (value is CacheEntry.PdfPage) value.bitmap.byteCount else 1
            }

            override fun entryRemoved(evicted: Boolean, key: String?, oldValue: CacheEntry?, newValue: CacheEntry?) {
                super.entryRemoved(evicted, key, oldValue, newValue)
                if (evicted && oldValue is CacheEntry.PdfPage) {
                    oldValue.bitmap.recycle()
                }
            }
        }
    }

    fun put(key: String, entry: CacheEntry) {
        memCache.put(key, entry)
    }

    fun get(key: String): CacheEntry? {
        return memCache.get(key)
    }

    fun clear() {
        memCache.evictAll()
        diskCacheDir.listFiles()?.forEach { it.delete() }
    }
}

@Singleton
class RecentDocumentsEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("DocRecent", Context.MODE_PRIVATE)

    fun addRecent(uri: Uri) {
        prefs.edit().putString(
            "list",
            (listOf(uri.toString()) + getRecents().map { it.toString() }).distinct().take(100).joinToString(";")
        ).apply()
    }

    fun getRecents(): List<Uri> {
        return prefs.getString("list", "")?.split(";")?.filter { it.isNotBlank() }?.map { Uri.parse(it) } ?: emptyList()
    }
}

@Singleton
class SearchEngine @Inject constructor(
    private val provider: DocumentDispatcherProvider
) {
    suspend fun searchWord(blocks: List<WordBlock>, options: SearchOptions): List<WordBlock> = withContext(provider.defaultDispatcher) {
        blocks.filter { b ->
            currentCoroutineContext().ensureActive()
            when (b) {
                is WordBlock.Paragraph -> b.runs.any { it.text.matchesOptions(options) }
                else -> false
            }
        }
    }

    suspend fun searchExcel(sheets: List<VirtualSheet>, options: SearchOptions): List<String> = withContext(provider.defaultDispatcher) {
        val res = mutableListOf<String>()
        sheets.forEach { s ->
            s.rows.forEach { r ->
                r.cells.forEach { c ->
                    currentCoroutineContext().ensureActive()
                    if (c.displayValue.matchesOptions(options)) {
                        res.add("${s.name}: R${r.rowIndex} C${c.columnIndex}")
                    }
                }
            }
        }
        res
    }

    suspend fun searchText(content: String, options: SearchOptions): List<Int> = withContext(provider.defaultDispatcher) {
        val lines = content.lines()
        val res = mutableListOf<Int>()
        lines.forEachIndexed { i, l ->
            currentCoroutineContext().ensureActive()
            if (l.matchesOptions(options)) {
                res.add(i)
            }
        }
        res
    }
}

// ==========================================
// FORMAT ENGINES
// ==========================================

class PdfRenderSession(
    private val pdfRenderer: PdfRenderer,
    private val fileDescriptor: ParcelFileDescriptor,
    private val tempFile: File?,
    private val smartCache: SmartCacheEngine,
    private val provider: DocumentDispatcherProvider
) {
    val pageCount: Int = pdfRenderer.pageCount
    private val renderMutex = Mutex()
    private var isClosed = false

    suspend fun getPageDimensions(index: Int): Pair<Int, Int>? = withContext(provider.ioDispatcher) {
        if (isClosed || index < 0 || index >= pageCount) return@withContext null
        try {
            renderMutex.withLock {
                val page = pdfRenderer.openPage(index)
                val dimensions = Pair(page.width, page.height)
                page.close()
                dimensions
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun renderPage(index: Int, scale: Float = 1.0f, isDarkMode: Boolean = false): Bitmap? = withContext(provider.ioDispatcher) {
        if (isClosed || index < 0 || index >= pageCount) {
            return@withContext null
        }

        val cacheKey = "pdf_${fileDescriptor.statSize}_${index}_${scale}_$isDarkMode"
        (smartCache.get(cacheKey) as? CacheEntry.PdfPage)?.bitmap?.let {
            if (!it.isRecycled) {
                return@withContext it
            }
        }

        try {
            renderMutex.withLock {
                currentCoroutineContext().ensureActive()
                val page = pdfRenderer.openPage(index)
                val width = max(1, (page.width * scale).toInt())
                val height = max(1, (page.height * scale).toInt())
                val cfg = if (scale > 2.0f) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
                val bmp = Bitmap.createBitmap(width, height, cfg)

                val canvas = Canvas(bmp)
                canvas.drawColor(if (isDarkMode) android.graphics.Color.BLACK else android.graphics.Color.WHITE)

                if (isDarkMode) {
                    val colorMatrix = ColorMatrix(floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                    val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(colorMatrix) }
                    val tempBmp = Bitmap.createBitmap(width, height, cfg)
                    Canvas(tempBmp).apply {
                        drawColor(android.graphics.Color.WHITE)
                        page.render(tempBmp, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                    canvas.drawBitmap(tempBmp, 0f, 0f, paint)
                    tempBmp.recycle()
                } else {
                    page.render(bmp, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }

                page.close()
                smartCache.put(cacheKey, CacheEntry.PdfPage(bmp))
                bmp
            }
        } catch (e: Exception) {
            Log.e("PdfRenderSession", "Failed to render page $index", e)
            null
        }
    }

    suspend fun prefetch(currentIndex: Int) = withContext(provider.ioDispatcher) {
        val range = max(0, currentIndex - 1)..min(pageCount - 1, currentIndex + 2)
        range.forEach {
            if (it != currentIndex) {
                renderPage(it, 1.0f)
            }
        }
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        try {
            pdfRenderer.close()
            fileDescriptor.close()
            tempFile?.delete()
        } catch (e: Exception) {
            Log.e("PdfRenderSession", "Error closing resources", e)
        }
    }
}

@Singleton
class PdfEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smartCache: SmartCacheEngine,
    private val provider: DocumentDispatcherProvider,
    val thumbnail: PdfThumbnailEngine,
    val search: PdfSearchEngine
) {
    suspend fun load(uri: Uri): PdfLoadResult = withContext(provider.ioDispatcher) {
        var fileDescriptor: ParcelFileDescriptor? = null
        var tempFile: File? = null

        try {
            fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            val renderer = if (fileDescriptor != null) {
                PdfRenderer(fileDescriptor)
            } else {
                val input = context.contentResolver.openInputStream(uri) ?: throw Exception("Failed to open stream")
                tempFile = File(context.cacheDir, "viewer_temp_${System.currentTimeMillis()}.pdf")
                input.use { i ->
                    tempFile.outputStream().use { o ->
                        i.copyTo(o)
                    }
                }
                fileDescriptor = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                PdfRenderer(fileDescriptor)
            }

            // Note: Returning success instead of throwing error if page count is 0
            PdfLoadResult.Success(renderer.pageCount)

        } catch (e: SecurityException) {
            // Cleanup on failure
            fileDescriptor?.close()
            tempFile?.delete()
            PdfLoadResult.Encrypted("Password-protected or encrypted PDF")
        } catch (e: Exception) {
            // Cleanup on failure
            fileDescriptor?.close()
            tempFile?.delete()
            PdfLoadResult.Error(e.localizedMessage ?: "Failed to open PDF", e)
        }
    }

    suspend fun createSession(uri: Uri): PdfRenderSession? = withContext(provider.ioDispatcher) {
        try {
            val fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            if (fileDescriptor != null) {
                return@withContext PdfRenderSession(PdfRenderer(fileDescriptor), fileDescriptor, null, smartCache, provider)
            } else {
                val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
                val tempFile = File(context.cacheDir, "viewer_temp_${System.currentTimeMillis()}.pdf")
                input.use { i ->
                    tempFile.outputStream().use { o ->
                        i.copyTo(o)
                    }
                }
                val fd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                return@withContext PdfRenderSession(PdfRenderer(fd), fd, tempFile, smartCache, provider)
            }
        } catch (e: Exception) {
            null
        }
    }
}

@Singleton
class PdfThumbnailEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val provider: DocumentDispatcherProvider
) {
    private val thumbDir = File(context.cacheDir, "doc_thumbs").apply { mkdirs() }

    suspend fun generate(uri: Uri, id: Long): String? = withContext(provider.ioDispatcher) {
        try {
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
            val r = PdfRenderer(fd)
            val p = r.openPage(0)
            val scale = 300f / p.width
            val width = max(1, (p.width * scale).toInt())
            val height = max(1, (p.height * scale).toInt())
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            Canvas(bmp).apply { drawColor(android.graphics.Color.WHITE) }
            p.render(bmp, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            p.close()
            r.close()
            fd.close()
            val f = File(thumbDir, "thumb_$id.jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bmp.recycle()
            f.absolutePath
        } catch (e: Exception) {
            Log.e("PdfThumbnailEngine", "Failed to generate thumbnail", e)
            null
        }
    }
}

@Singleton
class PdfSearchEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val provider: DocumentDispatcherProvider
) {
    private val textIndexCache = LruCache<String, Map<Int, String>>(3)

    init {
        PDFBoxResourceLoader.init(context)
    }

    suspend fun buildIndex(doc: DocumentFile): Map<Int, String> = withContext(provider.ioDispatcher) {
        val cacheKey = "${doc.id}_${doc.size}_${doc.dateModified}"
        textIndexCache.get(cacheKey)?.let { return@withContext it }
        val index = mutableMapOf<Int, String>()
        var pdDocument: PDDocument? = null
        try {
            pdDocument = PDDocument.load(context.contentResolver.openInputStream(doc.uri))
            if (pdDocument.isEncrypted) {
                return@withContext emptyMap()
            }
            val stripper = PDFTextStripper()
            for (i in 1..pdDocument.numberOfPages) {
                currentCoroutineContext().ensureActive()
                stripper.startPage = i
                stripper.endPage = i
                index[i - 1] = stripper.getText(pdDocument)
            }
            textIndexCache.put(cacheKey, index)
        } catch (e: Exception) {
            Log.e("PdfSearchEngine", "Search indexing failed", e)
        } finally {
            pdDocument?.close()
        }
        index
    }

    suspend fun search(doc: DocumentFile, options: SearchOptions): List<Int> = withContext(provider.defaultDispatcher) {
        val index = buildIndex(doc)
        index.filterValues { it.matchesOptions(options) }.keys.toList().sorted()
    }
}

@Singleton
class DocxEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val provider: DocumentDispatcherProvider
) {
    suspend fun parse(uri: Uri): List<WordBlock> = withContext(provider.ioDispatcher) {
        val blocks = mutableListOf<WordBlock>()
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val path = uri.toString().lowercase()

                if (path.endsWith(".doc")) {
                    val document = HWPFDocument(stream)
                    val range = document.range
                    var i = 0

                    while (i < range.numParagraphs()) {
                        currentCoroutineContext().ensureActive()
                        val p = range.getParagraph(i)
                        if (p.isInTable) {
                            val table = range.getTable(p)
                            val rows = mutableListOf<TableRow>()
                            for (rIdx in 0 until table.numRows()) {
                                val row = table.getRow(rIdx)
                                val cells = mutableListOf<TableCell>()
                                for (cIdx in 0 until row.numCells()) {
                                    val cell = row.getCell(cIdx)
                                    val cellBlocks = mutableListOf<WordBlock>()
                                    for (cpIdx in 0 until cell.numParagraphs()) {
                                        val cp = cell.getParagraph(cpIdx)
                                        val runs = mutableListOf<WordRun>()
                                        for (j in 0 until cp.numCharacterRuns()) {
                                            val cr = cp.getCharacterRun(j)
                                            val text = cr.text()?.replace("\u0007", "")?.replace("\r", "\n") ?: ""
                                            if (text.isNotBlank()) {
                                                runs.add(WordRun(text, bold = cr.isBold, italic = cr.isItalic, underline = cr.getUnderlineCode() != 0, fontSize = cr.fontSize / 2))
                                            }
                                        }
                                        if (runs.isNotEmpty()) {
                                            cellBlocks.add(WordBlock.Paragraph(runs))
                                        }
                                    }
                                    cells.add(TableCell(cellBlocks))
                                }
                                rows.add(TableRow(cells))
                            }
                            blocks.add(WordBlock.Table(rows))
                            i += table.numParagraphs()
                        } else {
                            val runs = mutableListOf<WordRun>()
                            for (j in 0 until p.numCharacterRuns()) {
                                val cr = p.getCharacterRun(j)
                                val text = cr.text()?.replace("\u0007", "")?.replace("\r", "\n") ?: ""
                                if (text.isNotBlank()) {
                                    runs.add(WordRun(text, bold = cr.isBold, italic = cr.isItalic, underline = cr.getUnderlineCode() != 0, fontSize = cr.fontSize / 2))
                                }
                            }
                            if (runs.isNotEmpty()) {
                                blocks.add(WordBlock.Paragraph(runs))
                            }
                            i++
                        }
                    }
                } else if (path.endsWith(".rtf")) {
                    blocks.add(WordBlock.Paragraph(listOf(WordRun("Native RTF rendering is experimental. Loading as plain text fallback...", italic = true))))
                } else {
                    val document = XWPFDocument(stream)
                    document.headerList.forEach { header ->
                        val headerBlocks = header.bodyElements.flatMap { element ->
                            currentCoroutineContext().ensureActive()
                            parseElement(element)
                        }
                        if (headerBlocks.isNotEmpty()) {
                            blocks.add(WordBlock.Header(headerBlocks))
                        }
                    }
                    for (element in document.bodyElements) {
                        currentCoroutineContext().ensureActive()
                        blocks.addAll(parseElement(element))
                    }
                    document.footerList.forEach { footer ->
                        val footerBlocks = footer.bodyElements.flatMap { element ->
                            currentCoroutineContext().ensureActive()
                            parseElement(element)
                        }
                        if (footerBlocks.isNotEmpty()) {
                            blocks.add(WordBlock.Footer(footerBlocks))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DocxEngine", "Word parsing failed", e)
        }
        if (blocks.isEmpty()) {
            blocks.add(WordBlock.Paragraph(listOf(WordRun("Empty or Corrupted Document"))))
        }
        blocks
    }

    private fun parseElement(element: IBodyElement): List<WordBlock> = when (element) {
        is XWPFParagraph -> parseParagraph(element)
        is XWPFTable -> listOf(parseTable(element))
        else -> emptyList()
    }

    private fun parseParagraph(paragraph: XWPFParagraph): List<WordBlock> {
        val blocks = mutableListOf<WordBlock>()
        val runs = mutableListOf<WordRun>()
        for (run in paragraph.runs) {
            val text = run.text() ?: ""
            if (text.isNotEmpty()) {
                runs.add(WordRun(text = text, bold = run.isBold, italic = run.isItalic, underline = run.underline != UnderlinePatterns.NONE, colorHex = run.color, fontFamily = run.fontFamily, fontSize = run.fontSize.takeIf { it > 0 }))
            }
            for (picture in run.embeddedPictures) {
                val p = picture.pictureData
                blocks.add(WordBlock.Image(p.data, p.suggestFileExtension(), p.fileName, inline = true))
            }
        }
        if (runs.isNotEmpty()) {
            val style = paragraph.style
            val headingLevel = if (style != null && style.startsWith("Heading")) {
                style.replace("Heading", "").toIntOrNull()
            } else {
                null
            }
            blocks.add(0, WordBlock.Paragraph(runs = runs, alignment = paragraph.alignment.name, headingLevel = headingLevel, isListItem = paragraph.numID != null))
        }
        return blocks
    }

    private fun parseTable(table: XWPFTable): WordBlock.Table {
        return WordBlock.Table(table.rows.map { row -> TableRow(row.tableCells.map { cell -> TableCell(cell.bodyElements.flatMap { parseElement(it) }) }) })
    }
}

@Singleton
class XlsxEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val provider: DocumentDispatcherProvider
) {

    suspend fun parse(uri: Uri): List<VirtualSheet> = withContext(provider.ioDispatcher) {
        val path = uri.toString().lowercase()
        if (path.endsWith(".xls")) {
            return@withContext parseLegacy(uri)
        }
        return@withContext parseStreaming(uri)
    }

    private suspend fun parseLegacy(uri: Uri): List<VirtualSheet> {
        val sheets = mutableListOf<VirtualSheet>()
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val workbook = WorkbookFactory.create(stream)
                val dataFormatter = DataFormatter()
                val formulaEvaluator = workbook.creationHelper.createFormulaEvaluator()

                for (i in 0 until workbook.numberOfSheets) {
                    val sheet: org.apache.poi.ss.usermodel.Sheet = workbook.getSheetAt(i)
                    currentCoroutineContext().ensureActive()
                    val virtualRows = mutableListOf<VirtualRow>()
                    var maxColumns = 0
                    val mergedRegions = sheet.mergedRegions.map { "${it.firstRow}:${it.firstColumn}-${it.lastRow}:${it.lastColumn}" }

                    for (row in sheet) {
                        currentCoroutineContext().ensureActive()
                        val virtualCells = mutableListOf<VirtualCell>()
                        val lastCol = row.lastCellNum.toInt()
                        if (lastCol > maxColumns) {
                            maxColumns = lastCol
                        }
                        for (colIndex in 0 until lastCol) {
                            val cell: org.apache.poi.ss.usermodel.Cell? = row.getCell(colIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)
                            if (cell == null) {
                                virtualCells.add(VirtualCell(colIndex, "", "", false))
                                continue
                            }

                            val isFormula = cell.cellType == CellType.FORMULA
                            val formulaString = if (isFormula) cell.cellFormula else null
                            val style = CellStyle(null, null, workbook.getFontAt(cell.cellStyle.fontIndexAsInt).bold, workbook.getFontAt(cell.cellStyle.fontIndexAsInt).italic, cell.cellStyle.alignment.name, false)

                            val rawValue = when (cell.cellType) {
                                CellType.STRING -> cell.stringCellValue
                                CellType.NUMERIC -> cell.numericCellValue.toString()
                                CellType.BOOLEAN -> cell.booleanCellValue.toString()
                                CellType.FORMULA -> when (cell.cachedFormulaResultType) {
                                    CellType.NUMERIC -> cell.numericCellValue.toString()
                                    CellType.STRING -> cell.stringCellValue
                                    else -> ""
                                }
                                else -> ""
                            }
                            virtualCells.add(VirtualCell(colIndex, dataFormatter.formatCellValue(cell, formulaEvaluator).trim(), rawValue.trim(), isFormula, formulaString, null, style, sheet.isColumnHidden(colIndex)))
                        }
                        virtualRows.add(VirtualRow(rowIndex = row.rowNum, cells = virtualCells, isHidden = row.zeroHeight))
                    }
                    sheets.add(VirtualSheet(name = sheet.sheetName, rows = virtualRows, rowCount = virtualRows.size, columnCount = maxColumns, mergedRegions = mergedRegions))
                }
            }
        } catch (e: Exception) {
            Log.e("XlsxEngine", "Legacy Excel parsing failed", e)
        }
        if (sheets.isEmpty()) {
            sheets.add(VirtualSheet("Sheet 1", emptyList(), 0, 0))
        }
        return sheets
    }

    private suspend fun parseStreaming(uri: Uri): List<VirtualSheet> {
        val sheets = mutableListOf<VirtualSheet>()
        try {
            val sharedStrings = mutableListOf<String>()
            val sheetMap = mutableMapOf<String, String>()

            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == "xl/sharedStrings.xml") {
                            val parser = Xml.newPullParser().apply { setInput(zis, "UTF-8") }
                            var event = parser.eventType
                            while (event != XmlPullParser.END_DOCUMENT) {
                                if (event == XmlPullParser.START_TAG && parser.name == "t") {
                                    event = parser.next()
                                    if (event == XmlPullParser.TEXT) {
                                        sharedStrings.add(parser.text)
                                    }
                                }
                                event = parser.next()
                            }
                        } else if (entry.name == "xl/workbook.xml") {
                            val parser = Xml.newPullParser().apply { setInput(zis, "UTF-8") }
                            var event = parser.eventType
                            while (event != XmlPullParser.END_DOCUMENT) {
                                if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
                                    val name = parser.getAttributeValue(null, "name")
                                    val rId = parser.getAttributeValue(null, "id") ?: parser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                                    if (name != null && rId != null) {
                                        sheetMap[rId] = name
                                    }
                                }
                                event = parser.next()
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
            }

            var sheetIndex = 1
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name.startsWith("xl/worksheets/sheet") && entry.name.endsWith(".xml")) {
                            currentCoroutineContext().ensureActive()
                            val sheetName = sheetMap["rId$sheetIndex"] ?: "Sheet $sheetIndex"
                            val virtualRows = mutableListOf<VirtualRow>()
                            var maxColumns = 0
                            val parser = Xml.newPullParser().apply { setInput(zis, "UTF-8") }

                            var event = parser.eventType
                            var currentRow = mutableListOf<VirtualCell>()
                            var rowIndex = 0
                            var colIndex = 0
                            var isShared = false
                            var cellValue = ""
                            val style = CellStyle(null, null, false, false, "LEFT", false)

                            while (event != XmlPullParser.END_DOCUMENT) {
                                when (event) {
                                    XmlPullParser.START_TAG -> {
                                        when (parser.name) {
                                            "row" -> {
                                                currentRow = mutableListOf()
                                                rowIndex = parser.getAttributeValue(null, "r")?.toIntOrNull()?.minus(1) ?: rowIndex
                                                colIndex = 0
                                            }
                                            "c" -> {
                                                isShared = parser.getAttributeValue(null, "t") == "s"
                                                val rRef = parser.getAttributeValue(null, "r")
                                                colIndex = if (rRef != null) {
                                                    var c = 0
                                                    rRef.takeWhile { it.isLetter() }.forEach { c = c * 26 + (it - 'A' + 1) }
                                                    c - 1
                                                } else {
                                                    colIndex
                                                }
                                            }
                                            "v", "t" -> {
                                                event = parser.next()
                                                if (event == XmlPullParser.TEXT) {
                                                    cellValue = parser.text
                                                }
                                            }
                                        }
                                    }
                                    XmlPullParser.END_TAG -> {
                                        when (parser.name) {
                                            "c" -> {
                                                val finalVal = if (isShared) sharedStrings.getOrNull(cellValue.toIntOrNull() ?: -1) ?: cellValue else cellValue
                                                if (colIndex > maxColumns) {
                                                    maxColumns = colIndex
                                                }
                                                currentRow.add(VirtualCell(colIndex, finalVal, finalVal, false, null, null, style, false))
                                                colIndex++
                                                cellValue = ""
                                                isShared = false
                                            }
                                            "row" -> {
                                                virtualRows.add(VirtualRow(rowIndex, currentRow, false))
                                                rowIndex++
                                            }
                                        }
                                    }
                                }
                                event = parser.next()
                            }
                            sheets.add(VirtualSheet(sheetName, virtualRows, virtualRows.size, maxColumns, emptyList()))
                            sheetIndex++
                        }
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("XlsxEngine", "Streaming Excel parsing failed", e)
        }
        if (sheets.isEmpty()) {
            sheets.add(VirtualSheet("Sheet 1", emptyList(), 0, 0))
        }
        return sheets
    }
}

@Singleton
class PptxEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val provider: DocumentDispatcherProvider
) {
    suspend fun parse(uri: Uri): List<SlidePage> = withContext(provider.ioDispatcher) {
        val slides = mutableListOf<SlidePage>()
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val slideShow = SlideShowFactory.create(stream)

                val pageSize = slideShow.javaClass.getMethod("getPageSize").invoke(slideShow)
                val pageWidth = (pageSize.javaClass.getMethod("getWidth").invoke(pageSize) as? Double ?: 1920.0).toFloat()
                val pageHeight = (pageSize.javaClass.getMethod("getHeight").invoke(pageSize) as? Double ?: 1080.0).toFloat()

                for (slide in slideShow.slides) {
                    currentCoroutineContext().ensureActive()
                    val elements = mutableListOf<SlideElement>()
                    for (shape in slide.shapes) {
                        processShape(shape, elements)
                    }
                    slides.add(SlidePage(pageWidth, pageHeight, elements))
                }
            }
        } catch (e: Exception) {
            Log.e("PptxEngine", "Slide parsing failed", e)
        }
        if (slides.isEmpty()) {
            slides.add(SlidePage(1920f, 1080f, listOf(SlideElement.Text("Empty Presentation", 0f, 0f, 800f, 100f, 24f))))
        }
        slides
    }

    private fun processShape(shape: org.apache.poi.sl.usermodel.Shape<*, *>, elements: MutableList<SlideElement>) {
        var x = 0f
        var y = 0f
        var w = 0f
        var h = 0f

        if (shape is PlaceableShape<*, *>) {
            try {
                val anchor = shape.javaClass.getMethod("getAnchor").invoke(shape)
                if (anchor != null) {
                    x = (anchor.javaClass.getMethod("getX").invoke(anchor) as? Double ?: 0.0).toFloat()
                    y = (anchor.javaClass.getMethod("getY").invoke(anchor) as? Double ?: 0.0).toFloat()
                    w = (anchor.javaClass.getMethod("getWidth").invoke(anchor) as? Double ?: 0.0).toFloat()
                    h = (anchor.javaClass.getMethod("getHeight").invoke(anchor) as? Double ?: 0.0).toFloat()
                }
            } catch (e: Exception) {

            }
        }

        when (shape) {
            is org.apache.poi.sl.usermodel.TextShape<*, *> -> {
                val fontSize = try {
                    shape.textParagraphs.firstOrNull()?.textRuns?.firstOrNull()?.fontSize ?: 24.0
                } catch(e: Exception){
                    24.0
                }
                if (shape.text.isNotBlank()) {
                    elements.add(SlideElement.Text(shape.text.trim(), x, y, w, h, fontSize.toFloat()))
                }
            }
            is org.apache.poi.sl.usermodel.PictureShape<*, *> -> {
                val ext = try {
                    shape.pictureData.type.extension ?: "png"
                } catch (e: Exception) {
                    "png"
                }
                elements.add(SlideElement.Image(shape.pictureData.data, ext, x, y, w, h))
            }
            is org.apache.poi.sl.usermodel.TableShape<*, *> -> {
                elements.add(SlideElement.Table(x, y, w, h, shape.numberOfRows, shape.numberOfColumns))
            }
            is org.apache.poi.sl.usermodel.AutoShape<*, *> -> {
                val shapeType = shape.shapeType?.name ?: "UNKNOWN"
                val text = if (shape.text.isNotBlank()) shape.text.trim() else null
                elements.add(SlideElement.Shape(shapeType, text, x, y, w, h))
            }
            is org.apache.poi.sl.usermodel.GroupShape<*, *> -> {
                shape.shapes.forEach {
                    processShape(it as org.apache.poi.sl.usermodel.Shape<*, *>, elements)
                }
            }
        }
    }
}

@Singleton
class CsvEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encodingEngine: EncodingDetectionEngine,
    private val delimiterDetection: DelimiterDetectionEngine,
    private val provider: DocumentDispatcherProvider
) {
    suspend fun parse(uri: Uri): VirtualSheet = withContext(provider.ioDispatcher) {
        val rows = mutableListOf<List<String>>()
        var maxColumns = 0
        try {
            val charset = encodingEngine.detectCharset(context, uri)
            val delimiter = delimiterDetection.detect(context, uri, charset, ',')

            context.contentResolver.openInputStream(uri)?.use { stream ->
                InputStreamReader(stream, charset).use { reader ->
                    val parser = CSVParserBuilder().withSeparator(delimiter).build()
                    val csvReader = CSVReaderBuilder(reader).withCSVParser(parser).build()

                    var record: Array<String>? = csvReader.readNext()
                    while (record != null) {
                        currentCoroutineContext().ensureActive()
                        val rowList = record.toList()
                        maxColumns = max(maxColumns, rowList.size)
                        rows.add(rowList)
                        record = csvReader.readNext()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CsvEngine", "CSV parsing failed", e)
        }

        val normalizedRows = if (maxColumns > 0) {
            rows.map { r ->
                if (r.size < maxColumns) {
                    r.toMutableList().apply {
                        while (size < maxColumns) {
                            add("")
                        }
                    }
                } else {
                    r
                }
            }
        } else {
            rows
        }

        VirtualSheet(
            "CSV Data",
            normalizedRows.mapIndexed { i, r ->
                VirtualRow(i, r.mapIndexed { ci, cv ->
                    VirtualCell(ci, cv, cv, false)
                }, false)
            },
            normalizedRows.size,
            maxColumns
        )
    }
}

@Singleton
class RawTextEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encodingEngine: EncodingDetectionEngine,
    private val provider: DocumentDispatcherProvider
) {
    companion object {
        private const val MAX_FULL_LOAD = 50L * 1024 * 1024
        private const val MAX_PREVIEW = 200L * 1024 * 1024
        private const val PEEK_SIZE = 4096
    }

    suspend fun read(uri: Uri): TextReadResult = withContext(provider.ioDispatcher) {
        try {
            val fileSize = context.contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    it.getLong(it.getColumnIndexOrThrow(android.provider.OpenableColumns.SIZE))
                } else {
                    0L
                }
            } ?: 0L

            if (fileSize == 0L) return@withContext TextReadResult.Error("File is empty or unreadable.")

            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bufferedStream = BufferedInputStream(stream, PEEK_SIZE)
                bufferedStream.mark(PEEK_SIZE)

                val peekBytes = ByteArray(PEEK_SIZE)
                val bytesRead = bufferedStream.read(peekBytes)

                if ((peekBytes.take(bytesRead).count { b ->
                        val i = b.toInt()
                        (i in 0..31 && i != 9 && i != 10 && i != 13) || i == 127
                    }.toFloat() / bytesRead) > 0.10f) {
                    return@withContext TextReadResult.BinaryFile(0.99f)
                }

                val charset = encodingEngine.detectCharset(peekBytes.copyOf(bytesRead))
                val peekText = String(peekBytes, 0, bytesRead, charset).trimStart()

                val syntaxMode = when {
                    peekText.startsWith("{") || peekText.startsWith("[") -> "JSON"
                    peekText.startsWith("<?xml", true) -> "XML"
                    peekText.startsWith("<!DOCTYPE", true) || peekText.startsWith("<html", true) -> "HTML"
                    else -> "TXT"
                }

                bufferedStream.reset()

                return@withContext when {
                    fileSize > MAX_PREVIEW -> TextReadResult.Paged(uri, charset, fileSize)
                    fileSize > MAX_FULL_LOAD -> {
                        val buffer = CharArray(MAX_FULL_LOAD.toInt() / 2)
                        val charsRead = bufferedStream.bufferedReader(charset).read(buffer)
                        val t = String(buffer, 0, charsRead)
                        TextReadResult.Preview(t, syntaxMode, buildLineOffsets(t), fileSize)
                    }
                    else -> {
                        val t = bufferedStream.bufferedReader(charset).readText()
                        TextReadResult.Success(t, syntaxMode, buildLineOffsets(t))
                    }
                }
            } ?: TextReadResult.Error("Failed to open file stream.")
        } catch (e: Exception) {
            TextReadResult.Error("Error reading file.", e)
        }
    }

    private suspend fun buildLineOffsets(text: String): List<Long> = withContext(provider.defaultDispatcher) {
        val o = mutableListOf(0L)
        for (i in text.indices) {
            currentCoroutineContext().ensureActive()
            if (text[i] == '\n') {
                o.add((i + 1).toLong())
            }
        }
        return@withContext o
    }
}

@Singleton
class EpubEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val provider: DocumentDispatcherProvider
) {
    suspend fun parse(uri: Uri): List<EpubChapter> = withContext(provider.ioDispatcher) {
        val chapters = mutableListOf<EpubChapter>()
        try {
            var opfPath: String? = null

            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == "META-INF/container.xml") {
                            val parser = Xml.newPullParser().apply { setInput(zis, "UTF-8") }
                            var event = parser.eventType
                            while (event != XmlPullParser.END_DOCUMENT) {
                                if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                                    opfPath = parser.getAttributeValue(null, "full-path")
                                }
                                event = parser.next()
                            }
                            break
                        }
                        entry = zis.nextEntry
                    }
                }
            }

            if (opfPath != null) {
                val manifestMap = mutableMapOf<String, String>()
                val spineRefs = mutableListOf<String>()
                val basePath = if (opfPath!!.contains("/")) opfPath!!.substringBeforeLast("/") + "/" else ""

                context.contentResolver.openInputStream(uri)?.use { stream ->
                    ZipInputStream(stream).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (entry.name == opfPath) {
                                val parser = Xml.newPullParser().apply { setInput(zis, "UTF-8") }
                                var event = parser.eventType
                                while (event != XmlPullParser.END_DOCUMENT) {
                                    if (event == XmlPullParser.START_TAG) {
                                        if (parser.name == "item") {
                                            manifestMap[parser.getAttributeValue(null, "id")] = basePath + parser.getAttributeValue(null, "href")
                                        }
                                        if (parser.name == "itemref") {
                                            spineRefs.add(parser.getAttributeValue(null, "idref"))
                                        }
                                    }
                                    event = parser.next()
                                }
                                break
                            }
                            entry = zis.nextEntry
                        }
                    }
                }

                val spinePaths = spineRefs.mapNotNull { manifestMap[it] }
                val contentMap = mutableMapOf<String, String>()

                context.contentResolver.openInputStream(uri)?.use { stream ->
                    ZipInputStream(stream).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (spinePaths.contains(entry.name)) {
                                contentMap[entry.name] = String(zis.readBytes(), Charset.defaultCharset())
                            }
                            entry = zis.nextEntry
                        }
                    }
                }

                spinePaths.forEach { path ->
                    contentMap[path]?.let {
                        chapters.add(EpubChapter(path, path.substringAfterLast('/'), it))
                    }
                }
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    ZipInputStream(stream).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (entry.name.endsWith(".html") || entry.name.endsWith(".xhtml")) {
                                chapters.add(EpubChapter(entry.name, entry.name.substringAfterLast('/'), String(zis.readBytes(), Charset.defaultCharset())))
                            }
                            entry = zis.nextEntry
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("EpubEngine", "Epub parsing failed", e)
        }
        chapters
    }
}

@Singleton
class ZipPreviewEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val provider: DocumentDispatcherProvider
) {
    suspend fun parse(uri: Uri): List<ZipEntryItem> = withContext(provider.ioDispatcher) {
        val items = mutableListOf<ZipEntryItem>()
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        currentCoroutineContext().ensureActive()
                        items.add(ZipEntryItem(entry.name, entry.size, entry.compressedSize, entry.time, entry.crc, entry.isDirectory))
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ZipEngine", "Zip parsing failed", e)
        }
        items.sortedBy { !it.isDirectory }
    }
}

@Singleton
class CodeFormatterEngine @Inject constructor() {
    fun format(rawCode: String, language: String = "javascript"): String {
        val escapedCode = rawCode.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=0\"><link href=\"file:///android_asset/prism.css\" rel=\"stylesheet\" /><style>body { margin: 0; padding: 0; background-color: #2d2d2d; } pre { margin: 0 !important; border-radius: 0 !important; font-size: 14px; }</style></head><body class=\"line-numbers\"><pre><code class=\"language-$language\">$escapedCode</code></pre><script src=\"file:///android_asset/prism.js\"></script></body></html>"
    }
}

@Singleton
class XmlFormatterEngine @Inject constructor(private val codeFormatter: CodeFormatterEngine) {
    fun format(raw: String): String {
        return try {
            if (raw.isBlank()) {
                raw
            } else {
                val t = TransformerFactory.newInstance().newTransformer().apply {
                    setOutputProperty(OutputKeys.INDENT, "yes")
                    setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
                    setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
                }
                val w = StringWriter()
                t.transform(StreamSource(StringReader(raw.trim())), StreamResult(w))
                codeFormatter.format(w.toString(), "xml")
            }
        } catch (e: Exception) {
            codeFormatter.format(raw.replace("><", ">\n<"), "xml")
        }
    }
}

@Singleton
class JsonFormatterEngine @Inject constructor(private val codeFormatter: CodeFormatterEngine) {
    fun format(raw: String): String {
        return codeFormatter.format(
            try {
                val t = raw.trim()
                when {
                    t.startsWith("[") -> JSONArray(t).toString(4)
                    t.startsWith("{") -> JSONObject(t).toString(4)
                    else -> raw
                }
            } catch (e: Exception) {
                raw
            },
            "json"
        )
    }
}

@Singleton
class HtmlFormatterEngine @Inject constructor() {
    fun format(raw: String): String {
        if (raw.trim().let { it.startsWith("<!DOCTYPE html>", true) || it.startsWith("<html", true) }) {
            return raw
        }
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=0\"><style>body{font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Helvetica,Arial,sans-serif;font-size:16px;line-height:1.6;padding:16px;margin:0;color:#1e1e1e;background-color:#ffffff;word-wrap:break-word;}img{max-width:100%;height:auto;border-radius:4px;}@media(prefers-color-scheme:dark){body{color:#e3e3e3;background-color:#121212;}}</style></head><body>$raw</body></html>"
    }
}

@Singleton
class MarkdownFormatterEngine @Inject constructor() {
    fun format(raw: String): String {
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=0\"><script src=\"file:///android_asset/marked.min.js\"></script><style>body{font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Helvetica,Arial,sans-serif;font-size:16px;line-height:1.6;padding:16px;margin:0;color:#1e1e1e;background-color:#ffffff;word-wrap:break-word;}pre{background:#f6f8fa;padding:16px;border-radius:8px;overflow-x:auto;}code{background:#f6f8fa;padding:2px 4px;border-radius:4px;font-family:monospace;}img{max-width:100%;height:auto;}@media(prefers-color-scheme:dark){body{color:#e3e3e3;background-color:#121212;}pre,code{background:#2d2d2d;}}</style></head><body><div id=\"content\"></div><script>document.getElementById('content').innerHTML = marked.parse(`${raw.replace("`", "\\`").replace("$", "\\$")}`);</script></body></html>"
    }
}

@Singleton
class ThumbnailEngine @Inject constructor(
    private val pdfEngine: PdfEngine,
    @ApplicationContext private val context: Context,
    private val provider: DocumentDispatcherProvider
) {
    suspend fun generatePdfThumbnail(uri: Uri, id: Long) = pdfEngine.thumbnail.generate(uri, id)

    suspend fun getOoxmlThumbnail(uri: Uri): Bitmap? = withContext(provider.ioDispatcher) {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == "docProps/thumbnail.jpeg" || entry.name == "docProps/thumbnail.wmf") {
                            return@withContext BitmapFactory.decodeStream(zis)
                        }
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ThumbnailEngine", "OOXML Thumbnail gen failed", e)
        }
        null
    }

    suspend fun generateFallbackTextThumbnail(text: String): Bitmap? = withContext(provider.ioDispatcher) {
        val bmp = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp).apply { drawColor(android.graphics.Color.WHITE) }
        val paint = Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 40f
            isAntiAlias = true
        }
        canvas.drawText(text.take(40), 40f, 100f, paint)
        bmp
    }

    fun getIcon(type: DocumentType): String = when(type) {
        DocumentType.WORD -> "res://ic_word"
        DocumentType.EXCEL -> "res://ic_excel"
        DocumentType.SLIDE -> "res://ic_slide"
        DocumentType.EPUB -> "res://ic_epub"
        DocumentType.MARKDOWN -> "res://ic_markdown"
        DocumentType.ZIP -> "res://ic_zip"
        DocumentType.HTML -> "res://ic_html"
        DocumentType.RTF -> "res://ic_rtf"
        DocumentType.CSV -> "res://ic_csv"
        DocumentType.CODE, DocumentType.JSON, DocumentType.XML -> "res://ic_code"
        else -> "res://ic_document"
    }
}

// ==========================================
// MASTER ENGINE FACADE
// ==========================================
@Singleton
class DocumentCoreEngine @Inject constructor(
    val fileDetection: FileDetectionEngine,
    val validation: ValidationEngine,
    val metadata: MetadataEngine,
    val thumbnail: ThumbnailEngine,
    val smartCache: SmartCacheEngine,
    val search: SearchEngine,
    val recent: RecentDocumentsEngine,
    val pdfEngine: PdfEngine,
    val docxEngine: DocxEngine,
    val xlsxEngine: XlsxEngine,
    val pptxEngine: PptxEngine,
    val rawTextEngine: RawTextEngine,
    val csvEngine: CsvEngine,
    val epubEngine: EpubEngine,
    val zipEngine: ZipPreviewEngine,
    val jsonEngine: JsonFormatterEngine,
    val xmlEngine: XmlFormatterEngine,
    val htmlEngine: HtmlFormatterEngine,
    val codeEngine: CodeFormatterEngine,
    val mdEngine: MarkdownFormatterEngine,
    private val provider: DocumentDispatcherProvider
) {
    suspend fun open(doc: DocumentFile): EngineResult = withContext(provider.ioDispatcher) {
        try {
            when (doc.type) {
                DocumentType.PDF -> {
                    val session = pdfEngine.createSession(doc.uri)
                    if (session != null) {
                        EngineResult.OpenPdf(doc.uri, session)
                    } else {
                        EngineResult.Error("Failed to initialize PDF renderer. File may be corrupted.")
                    }
                }
                DocumentType.WORD -> {
                    if (validation.validateDocx(doc.uri)) {
                        EngineResult.OpenWord(docxEngine.parse(doc.uri))
                    } else {
                        EngineResult.Unsupported("WORD", doc.mimeType ?: "*/*")
                    }
                }
                DocumentType.EXCEL -> {
                    if (validation.validateXlsx(doc.uri)) {
                        EngineResult.OpenExcel(xlsxEngine.parse(doc.uri))
                    } else {
                        EngineResult.Unsupported("EXCEL", doc.mimeType ?: "*/*")
                    }
                }
                DocumentType.SLIDE -> {
                    if (validation.validatePptx(doc.uri)) {
                        EngineResult.OpenSlide(pptxEngine.parse(doc.uri))
                    } else {
                        EngineResult.Unsupported("SLIDE", doc.mimeType ?: "*/*")
                    }
                }
                DocumentType.CSV -> EngineResult.OpenCsv(csvEngine.parse(doc.uri))
                DocumentType.EPUB -> EngineResult.OpenEpub(epubEngine.parse(doc.uri))
                DocumentType.ZIP -> EngineResult.OpenZip(zipEngine.parse(doc.uri))
                DocumentType.RTF -> EngineResult.OpenWord(docxEngine.parse(doc.uri))
                DocumentType.TXT, DocumentType.CODE, DocumentType.JSON, DocumentType.XML, DocumentType.HTML, DocumentType.MARKDOWN -> {
                    when (val textRes = rawTextEngine.read(doc.uri)) {
                        is TextReadResult.Success -> formatTextBasedOnType(textRes.text, doc.type)
                        is TextReadResult.Preview -> formatTextBasedOnType(textRes.text, doc.type)
                        is TextReadResult.Paged -> EngineResult.Error("File too large for immediate processing. Pagination required.")
                        is TextReadResult.BinaryFile -> EngineResult.Error("Binary file detected. Cannot parse as text.")
                        is TextReadResult.Error -> EngineResult.Error(textRes.message)
                    }
                }
                else -> EngineResult.Unsupported(doc.name.substringAfterLast('.', "Unknown").uppercase(), doc.mimeType ?: "*/*")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            EngineResult.Error("Failed to parse document: ${e.localizedMessage}")
        }
    }

    private fun formatTextBasedOnType(rawText: String, type: DocumentType): EngineResult {
        return when (type) {
            DocumentType.TXT -> EngineResult.OpenText(rawText, type)
            DocumentType.CODE -> EngineResult.OpenText(codeEngine.format(rawText), type)
            DocumentType.JSON -> EngineResult.OpenText(jsonEngine.format(rawText), type)
            DocumentType.XML -> EngineResult.OpenText(xmlEngine.format(rawText), type)
            DocumentType.HTML -> EngineResult.OpenText(htmlEngine.format(rawText), type)
            DocumentType.MARKDOWN -> EngineResult.OpenText(mdEngine.format(rawText), type)
            else -> EngineResult.Error("Unsupported text type")
        }
    }
}