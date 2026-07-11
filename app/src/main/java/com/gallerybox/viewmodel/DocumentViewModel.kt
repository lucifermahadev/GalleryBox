@file:Suppress("BlockingMethodInNonBlockingContext", "UNUSED_PARAMETER", "unused", "FunctionName", "MemberVisibilityCanBePrivate")
package com.gallerybox.viewmodel

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gallerybox.engine.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import java.util.Locale

data class ReadingState(
    val uri: Uri,
    val page: Int,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class DocumentUiEvent {
    data class LaunchIntent(val intent: Intent) : DocumentUiEvent()
    data class ShowToast(val message: String) : DocumentUiEvent()
    data class DocumentError(val message: String) : DocumentUiEvent()
    data class OpenPdf(val uri: Uri) : DocumentUiEvent()
    data class OpenWord(val blocks: List<WordBlock>) : DocumentUiEvent()
    data class OpenExcel(val sheets: List<VirtualSheet>) : DocumentUiEvent()
    data class OpenSlide(val pages: List<SlidePage>) : DocumentUiEvent()
    data class OpenText(val content: String) : DocumentUiEvent()
    data class OpenHtml(val uri: Uri) : DocumentUiEvent()
    data class OpenEpub(val chapters: List<EpubChapter>) : DocumentUiEvent()
    data class OpenZip(val contents: List<ZipEntryItem>) : DocumentUiEvent()
}

@OptIn(FlowPreview::class)
@HiltViewModel
class DocumentViewModel @Inject constructor(
    private val documentEngine: DocumentCoreEngine,
    private val recentEngine: RecentDocumentsEngine,
    private val provider: DocumentDispatcherProvider,
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context
) : ViewModel() {
    val pdfRendererEngine: PdfRendererEngine get() = documentEngine.pdfEngine.renderer
    val docxEngine: DocxEngine get() = documentEngine.docxEngine
    val xlsxEngine: XlsxEngine get() = documentEngine.xlsxEngine
    val pptxEngine: PptxEngine get() = documentEngine.pptxEngine
    val rawTextEngine: RawTextEngine get() = documentEngine.rawTextEngine

    private val _allDocs = MutableStateFlow<List<DocumentFile>>(emptyList())
    private val _docSearchQuery = MutableStateFlow(savedStateHandle.get<String>("search") ?: "")
    val docSearchQuery = _docSearchQuery.asStateFlow()

    private val _docCategory = MutableStateFlow<DocumentType?>(
        savedStateHandle.get<String>("category")?.let { enumValueOf<DocumentType>(it) }
    )
    val docCategory = _docCategory.asStateFlow()

    private val _docSortType = MutableStateFlow(
        savedStateHandle.get<String>("sort")?.let { enumValueOf<DocumentSortType>(it) } ?: DocumentSortType.DATE
    )
    val docSortType = _docSortType.asStateFlow()

    val documents = combine(_allDocs, _docSearchQuery.debounce(300), _docCategory, _docSortType) { docs, query, category, sort ->
        var seq = docs.asSequence()
        if (category != null) seq = seq.filter { it.type == category }
        if (query.isNotBlank()) seq = seq.filter { it.name.contains(query, ignoreCase = true) }

        val recents = recentEngine.getRecents()
        seq.sortedWith(
            when (sort) {
                DocumentSortType.DATE -> compareByDescending { it.dateModified }
                DocumentSortType.SIZE -> compareByDescending { it.size }
                DocumentSortType.NAME -> compareBy { it.name.lowercase(Locale.ROOT) }
                DocumentSortType.RECENT -> compareBy { val idx = recents.indexOf(it.uri); if (idx == -1) Int.MAX_VALUE else idx }
            }
        ).toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _readingHistory = MutableStateFlow<List<ReadingState>>(emptyList())
    val readingHistory = _readingHistory.asStateFlow()

    private val _isListLoading = MutableStateFlow(false)
    val isListLoading = _isListLoading.asStateFlow()

    private val _isOpeningDoc = MutableStateFlow(false)
    val isOpeningDoc = _isOpeningDoc.asStateFlow()

    private val _events = Channel<DocumentUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val prefs = context.getSharedPreferences("DocHistory", Context.MODE_PRIVATE)
    init {
        loadHistory()
        loadAllDocuments()
    }

    fun loadAllDocuments() {
        viewModelScope.launch(provider.ioDispatcher) {
            _isListLoading.value = true
            val fetched = mutableListOf<DocumentFile>()
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.MIME_TYPE
            )
            val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} NOT LIKE 'image/%' AND ${MediaStore.Files.FileColumns.MIME_TYPE} NOT LIKE 'video/%' AND ${MediaStore.Files.FileColumns.MIME_TYPE} NOT LIKE 'audio/%'"

            try {
                context.contentResolver.query(MediaStore.Files.getContentUri("external"), projection, selection, null, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)

                    val allowedExtensions = setOf(
                        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "json", "xml", "html", "htm",
                        "epub", "md", "markdown", "zip", "rtf",
                        "kt", "java", "cpp", "c", "h", "hpp", "py", "js", "ts", "css", "php", "sql", "sh"
                    )

                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()

                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: ""
                        val extension = name.substringAfterLast('.', "").lowercase(Locale.US)

                        if (extension !in allowedExtensions) continue

                        val mime = cursor.getString(mimeCol) ?: ""
                        val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)

                        val type = fastDetectType(extension)

                        fetched.add(
                            DocumentFile(
                                id = id,
                                name = name,
                                uri = uri,
                                size = cursor.getLong(sizeCol),
                                dateModified = cursor.getLong(dateCol) * 1000L,
                                mimeType = mime,
                                type = type
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("DocumentViewModel", "Failed to load documents: ${e.localizedMessage}")
            } finally {
                withContext(Dispatchers.Main) {
                    _allDocs.value = fetched
                    _isListLoading.value = false
                }
            }
        }
    }

    private fun fastDetectType(extension: String): DocumentType {
        return when (extension) {
            "pdf" -> DocumentType.PDF
            "doc", "docx", "odt" -> DocumentType.WORD
            "rtf" -> DocumentType.RTF
            "xls", "xlsx", "ods" -> DocumentType.EXCEL
            "ppt", "pptx", "odp" -> DocumentType.SLIDE
            "csv" -> DocumentType.CSV
            "json" -> DocumentType.JSON
            "xml" -> DocumentType.XML
            "html", "htm" -> DocumentType.HTML
            "md", "markdown" -> DocumentType.MARKDOWN
            "epub" -> DocumentType.EPUB
            "zip" -> DocumentType.ZIP
            "txt" -> DocumentType.TXT
            "kt", "java", "cpp", "c", "h", "hpp", "py", "js", "ts", "css", "php", "sql", "sh" -> DocumentType.CODE
            else -> DocumentType.UNKNOWN
        }
    }

    fun updateDocSearchQuery(query: String) {
        _docSearchQuery.value = query
        savedStateHandle["search"] = query
    }
    fun updateDocCategory(type: DocumentType?) {
        _docCategory.value = type
        savedStateHandle["category"] = type?.name
    }
    fun updateDocSortType(sort: DocumentSortType) {
        _docSortType.value = sort
        savedStateHandle["sort"] = sort.name
    }

    fun getDocumentById(id: Long): DocumentFile? = _allDocs.value.find { it.id == id }

    fun openDocument(doc: DocumentFile) {
        if (_isOpeningDoc.value) return
        viewModelScope.launch {
            _isOpeningDoc.value = true
            try {
                val res = withTimeoutOrNull(30000L) { documentEngine.open(doc) }

                if (res == null) {
                    _events.send(DocumentUiEvent.DocumentError("Document parsing timed out."))
                    return@launch
                }

                when (res) {
                    is EngineResult.OpenPdf -> _events.send(DocumentUiEvent.OpenPdf(res.uri))
                    is EngineResult.OpenWord -> _events.send(DocumentUiEvent.OpenWord(res.blocks))
                    is EngineResult.OpenExcel -> _events.send(DocumentUiEvent.OpenExcel(res.sheets))
                    is EngineResult.OpenSlide -> _events.send(DocumentUiEvent.OpenSlide(res.pages))
                    is EngineResult.OpenCsv -> _events.send(DocumentUiEvent.OpenExcel(listOf(res.sheet)))
                    is EngineResult.OpenEpub -> _events.send(DocumentUiEvent.OpenEpub(res.chapters))
                    is EngineResult.OpenZip -> _events.send(DocumentUiEvent.OpenZip(res.contents))
                    is EngineResult.OpenText -> _events.send(DocumentUiEvent.OpenText(res.content))
                    is EngineResult.Unsupported, is EngineResult.Error -> {
                        val isEncrypted = (res as? EngineResult.Error)?.message?.let { it.contains("password", true) || it.contains("encrypt", true) } ?: false
                        if (isEncrypted) {
                            _events.send(DocumentUiEvent.DocumentError("Password protected document."))
                        } else {
                            val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(doc.uri, doc.mimeType ?: "*/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                            _events.send(DocumentUiEvent.LaunchIntent(Intent.createChooser(intent, "Open with")))
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _events.send(DocumentUiEvent.DocumentError(e.message ?: "Failed to open document"))
                Log.e("DocumentViewModel", "Error opening document: ${e.localizedMessage}")
            } finally {
                _isOpeningDoc.value = false
            }
        }
    }

    private fun loadHistory() {
        val historyJson = prefs.getString("history", "[]") ?: "[]"
        try {
            val arr = JSONArray(historyJson)
            val loaded = mutableListOf<ReadingState>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                loaded.add(ReadingState(Uri.parse(obj.getString("uri")), obj.getInt("page"), obj.getDouble("scale").toFloat(), obj.getDouble("offsetX").toFloat(), obj.getDouble("offsetY").toFloat(), obj.getLong("timestamp")))
            }
            _readingHistory.value = loaded
        } catch (e: Exception) {
            Log.e("DocumentViewModel", "Failed to load history: ${e.localizedMessage}")
        }
    }

    fun saveReadingState(state: ReadingState) {
        val currentHistory = _readingHistory.value.toMutableList()
        currentHistory.removeAll { it.uri == state.uri }
        currentHistory.add(0, state)
        val limited = currentHistory.take(20)
        _readingHistory.value = limited

        viewModelScope.launch(provider.ioDispatcher) {
            try {
                val arr = JSONArray()
                limited.forEach { s ->
                    val obj = JSONObject().apply {
                        put("uri", s.uri.toString())
                        put("page", s.page)
                        put("scale", s.scale.toDouble())
                        put("offsetX", s.offsetX.toDouble())
                        put("offsetY", s.offsetY.toDouble())
                        put("timestamp", s.timestamp)
                    }
                    arr.put(obj)
                }
                prefs.edit().putString("history", arr.toString()).apply()
            } catch (e: Exception) {
                Log.e("DocumentViewModel", "Failed to save history: ${e.localizedMessage}")
            }
        }
    }
}