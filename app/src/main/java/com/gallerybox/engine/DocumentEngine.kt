@file:Suppress("BlockingMethodInNonBlockingContext", "UNUSED_PARAMETER", "unused", "FunctionName", "MemberVisibilityCanBePrivate", "UnsafeOptInUsageError", "Deprecation")

package com.gallerybox.engine

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.room.*
import com.gallerybox.data.DocumentFile
import com.gallerybox.data.DocumentType
import com.gallerybox.data.EngineResult
import com.gallerybox.data.SlideElement
import com.gallerybox.data.SlidePage
import com.gallerybox.data.TableCell
import com.gallerybox.data.TableRow
import com.gallerybox.data.TextReadResult
import com.gallerybox.data.VirtualCell
import com.gallerybox.data.VirtualRow
import com.gallerybox.data.VirtualSheet
import com.gallerybox.data.WordBlock
import com.gallerybox.data.WordRun
import com.gallerybox.data.PdfLoadResult
import com.gallerybox.data.CellStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.sl.usermodel.PlaceableShape
import org.apache.poi.sl.usermodel.SlideShowFactory
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xwpf.usermodel.*
import java.io.*
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

@Singleton
class DocumentDispatcherProvider @Inject constructor() {
    private val executor = Executors.newFixedThreadPool(min(Runtime.getRuntime().availableProcessors(), 4))
    val ioDispatcher = executor.asCoroutineDispatcher()
    val defaultDispatcher = Dispatchers.Default

    fun shutdown() {
        executor.shutdown()
    }
}

@Singleton
class FileDetectionEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun detect(uri: Uri, name: String, fallbackMime: String?): DocumentType {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        val safeMime = fallbackMime?.lowercase(Locale.US) ?: "*/*"

        return when {
            ext == "pdf" || safeMime.contains("pdf") -> DocumentType.PDF
            ext in setOf("doc", "docx") || safeMime.contains("word") -> DocumentType.WORD
            ext in setOf("xls", "xlsx") || safeMime.contains("spreadsheet") || safeMime.contains("excel") -> DocumentType.EXCEL
            ext in setOf("ppt", "pptx") || safeMime.contains("presentation") || safeMime.contains("powerpoint") -> DocumentType.SLIDE
            ext == "txt" || safeMime.startsWith("text/plain") -> DocumentType.TXT
            else -> DocumentType.UNKNOWN
        }
    }
}

@Singleton
class ValidationEngine @Inject constructor(
    @ApplicationContext private val context: Context,
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
        val path = uri.toString().lowercase(Locale.US)
        path.endsWith(".doc") || path.endsWith(".docx")
    }

    suspend fun validateXlsx(uri: Uri): Boolean = withContext(provider.ioDispatcher) {
        val path = uri.toString().lowercase(Locale.US)
        path.endsWith(".xls") || path.endsWith(".xlsx")
    }

    suspend fun validatePptx(uri: Uri): Boolean = withContext(provider.ioDispatcher) {
        val path = uri.toString().lowercase(Locale.US)
        path.endsWith(".ppt") || path.endsWith(".pptx")
    }
}

class PdfRenderSession(
    private val pdfRenderer: PdfRenderer,
    private val fileDescriptor: ParcelFileDescriptor,
    private val tempFile: File?,
    private val provider: DocumentDispatcherProvider
) {
    val pageCount: Int = pdfRenderer.pageCount
    private val renderMutex = Mutex()
    private var isClosed = false
    private val dimensionCache = java.util.concurrent.ConcurrentHashMap<Int, Pair<Int, Int>>()

    suspend fun getPageDimensions(index: Int): Pair<Int, Int>? = withContext(provider.ioDispatcher) {
        if (isClosed || index < 0 || index >= pageCount) return@withContext null
        dimensionCache[index]?.let { return@withContext it }
        try {
            renderMutex.withLock {
                if (isClosed) return@withContext null
                val page = pdfRenderer.openPage(index)
                val dimensions = Pair(page.width, page.height)
                page.close()
                dimensionCache[index] = dimensions
                dimensions
            }
        } catch (e: Exception) {
            Log.e("PdfRenderSession", "Error getting page dimensions", e)
            null
        }
    }

    suspend fun renderPage(index: Int, scale: Float = 1.0f, isDarkMode: Boolean = false): Bitmap? = withContext(provider.ioDispatcher) {
        if (isClosed || index < 0 || index >= pageCount) {
            return@withContext null
        }

        try {
            renderMutex.withLock {
                if (isClosed) return@withContext null
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
                ensureActive()
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
    private val provider: DocumentDispatcherProvider
) {
    private val thumbDir = File(context.cacheDir, "doc_thumbs").apply { mkdirs() }

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

            PdfLoadResult.Success(renderer.pageCount)

        } catch (e: SecurityException) {
            fileDescriptor?.close()
            tempFile?.delete()
            PdfLoadResult.Encrypted("Password-protected or encrypted PDF")
        } catch (e: Exception) {
            fileDescriptor?.close()
            tempFile?.delete()
            PdfLoadResult.Error(e.localizedMessage ?: "Failed to open PDF", e)
        }
    }

    suspend fun createSession(uri: Uri): PdfRenderSession? = withContext(provider.ioDispatcher) {
        try {
            val fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            if (fileDescriptor != null) {
                return@withContext PdfRenderSession(PdfRenderer(fileDescriptor), fileDescriptor, null, provider)
            } else {
                val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
                val tempFile = File(context.cacheDir, "viewer_temp_${System.currentTimeMillis()}.pdf")
                input.use { i ->
                    tempFile.outputStream().use { o ->
                        i.copyTo(o)
                    }
                }
                val fd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                return@withContext PdfRenderSession(PdfRenderer(fd), fd, tempFile, provider)
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun generateThumbnail(uri: Uri, id: Long): String? = withContext(provider.ioDispatcher) {
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
            Log.e("PdfEngine", "Failed to generate thumbnail", e)
            null
        }
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
                                    runs.add(WordRun(text, bold = p.getCharacterRun(j).isBold, italic = p.getCharacterRun(j).isItalic, underline = p.getCharacterRun(j).getUnderlineCode() != 0, fontSize = p.getCharacterRun(j).fontSize / 2))
                                }
                            }
                            if (runs.isNotEmpty()) {
                                blocks.add(WordBlock.Paragraph(runs))
                            }
                            i++
                        }
                    }
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
            Log.e("XlsxEngine", "Excel parsing failed", e)
        }
        if (sheets.isEmpty()) {
            sheets.add(VirtualSheet("Sheet 1", emptyList(), 0, 0))
        }
        return@withContext sheets
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
            } catch (e: Exception) {}
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
class RawTextEngine @Inject constructor(
    @ApplicationContext private val context: Context,
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

                val charset = Charset.forName("UTF-8")
                val syntaxMode = "TXT"

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
class ThumbnailEngine @Inject constructor(
    private val pdfEngine: PdfEngine,
    @ApplicationContext private val context: Context,
    private val provider: DocumentDispatcherProvider
) {
    suspend fun generatePdfThumbnail(uri: Uri, id: Long) = pdfEngine.generateThumbnail(uri, id)

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
        DocumentType.TXT -> "res://ic_document"
        else -> "res://ic_document"
    }
}

@Singleton
class DocumentCoreEngine @Inject constructor(
    val fileDetection: FileDetectionEngine,
    val validation: ValidationEngine,
    val thumbnail: ThumbnailEngine,
    val pdfEngine: PdfEngine,
    val docxEngine: DocxEngine,
    val xlsxEngine: XlsxEngine,
    val pptxEngine: PptxEngine,
    val rawTextEngine: RawTextEngine,
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
                DocumentType.TXT -> {
                    when (val textRes = rawTextEngine.read(doc.uri)) {
                        is TextReadResult.Success -> EngineResult.OpenText(textRes.text, doc.type)
                        is TextReadResult.Preview -> EngineResult.OpenText(textRes.text, doc.type)
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
}