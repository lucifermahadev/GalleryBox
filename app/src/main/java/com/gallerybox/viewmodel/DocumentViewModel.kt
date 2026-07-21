@file:Suppress("BlockingMethodInNonBlockingContext", "UNUSED_PARAMETER", "unused", "FunctionName", "MemberVisibilityCanBePrivate", "OPT_IN_USAGE")

package com.gallerybox.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gallerybox.data.*
import com.gallerybox.engine.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class DocumentViewModel @Inject constructor(
    private val documentEngine: DocumentCoreEngine,
    private val provider: DocumentDispatcherProvider,
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val pdfEngine: PdfEngine get() = documentEngine.pdfEngine
    val docxEngine: DocxEngine get() = documentEngine.docxEngine
    val xlsxEngine: XlsxEngine get() = documentEngine.xlsxEngine
    val pptxEngine: PptxEngine get() = documentEngine.pptxEngine
    val rawTextEngine: RawTextEngine get() = documentEngine.rawTextEngine

    private val supportedDocExtensions = setOf(
        "pdf",
        "doc",
        "docx",
        "xls",
        "xlsx",
        "ppt",
        "pptx",
        "txt"
    )

    private val supportedMimeTypes = setOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/plain"
    )

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

    val documents = combine(
        _allDocs,
        _docSearchQuery.debounce(300),
        _docCategory,
        _docSortType
    ) { docs, query, category, sort ->
        var seq = docs.asSequence()

        if (category != null) {
            seq = seq.filter { it.type == category }
        }

        if (query.isNotBlank()) {
            seq = seq.filter { it.name.contains(query, ignoreCase = true) }
        }

        seq.sortedWith(
            when (sort) {
                DocumentSortType.DATE -> compareByDescending { it.dateModified }
                DocumentSortType.SIZE -> compareByDescending { it.size }
                DocumentSortType.NAME -> compareBy { it.name.lowercase(Locale.ROOT) }
                DocumentSortType.RECENT -> compareByDescending { it.dateModified }
            }
        ).toList()
    }.flowOn(provider.defaultDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _readingHistory = MutableStateFlow<List<ReadingState>>(emptyList())
    val readingHistory = _readingHistory.asStateFlow()

    private val _isListLoading = MutableStateFlow(false)
    val isListLoading = _isListLoading.asStateFlow()

    private val _isOpeningDoc = MutableStateFlow(false)
    val isOpeningDoc = _isOpeningDoc.asStateFlow()

    private val _events = Channel<DocumentUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _activePdfSession = MutableStateFlow<PdfRenderSession?>(null)
    val activePdfSession = _activePdfSession.asStateFlow()

    private val prefs = context.getSharedPreferences("DocHistory", Context.MODE_PRIVATE)
    private var openingJob: Job? = null
    private val readingStateFlow = MutableSharedFlow<ReadingState>(extraBufferCapacity = 10)

    init {
        loadHistory()
        // Note: loadAllDocuments() is intentionally NOT called here anymore.
        // It must be triggered from the UI once the user grants SAF folder access.

        viewModelScope.launch(provider.ioDispatcher) {
            readingStateFlow
                .debounce(1500L)
                .collectLatest { state ->
                    persistReadingState(state)
                }
        }
    }

    // Pass the SAF Tree URI granted from the UI (DocumentFolderGuard)
    fun loadAllDocuments(treeUri: Uri) {
        viewModelScope.launch(provider.ioDispatcher) {
            _isListLoading.value = true
            val fetched = mutableListOf<DocumentFile>()

            try {
                // Convert the raw treeUri into an AndroidX DocumentFile root
                val rootDoc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)

                if (rootDoc != null && rootDoc.isDirectory) {
                    // Scan the folder recursively
                    traverseDirectory(rootDoc, fetched)
                }
            } catch (e: Exception) {
                Log.e("DocumentViewModel", "Failed to load documents via SAF", e)
            } finally {
                withContext(Dispatchers.Main) {
                    _allDocs.value = fetched
                    _isListLoading.value = false
                }
            }
        }
    }

    // Recursive helper to traverse SAF directories
    private suspend fun traverseDirectory(
        dir: androidx.documentfile.provider.DocumentFile,
        fetched: MutableList<DocumentFile>
    ) {
        val files = dir.listFiles()
        for (file in files) {
            // Stop scanning immediately if the ViewModel job is cancelled
            currentCoroutineContext().ensureActive()

            if (file.isDirectory) {
                traverseDirectory(file, fetched)
            } else {
                val name = file.name ?: continue
                if (name.startsWith(".")) continue

                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val mime = file.type ?: ""

                // Filter strictly by supported extensions and MIME types
                if (ext !in supportedDocExtensions && mime !in supportedMimeTypes) continue

                val fallbackType = when {
                    mime == "application/pdf" || ext == "pdf" -> DocumentType.PDF
                    ext in setOf("doc", "docx") || mime.contains("word") -> DocumentType.WORD
                    ext in setOf("xls", "xlsx") || mime.contains("spreadsheet") -> DocumentType.EXCEL
                    ext in setOf("ppt", "pptx") || mime.contains("presentation") -> DocumentType.SLIDE
                    ext == "txt" || mime == "text/plain" -> DocumentType.TXT
                    else -> DocumentType.UNKNOWN
                }

                var type = try {
                    documentEngine.fileDetection.detect(file.uri, name, mime)
                } catch (_: Exception) {
                    DocumentType.UNKNOWN
                }

                if (type == DocumentType.UNKNOWN) type = fallbackType

                // Map to your custom data class securely
                fetched.add(
                    DocumentFile(
                        id = file.uri.toString().hashCode().toLong(), // Stable hash for ID
                        name = name,
                        uri = file.uri,
                        size = file.length(),
                        dateModified = file.lastModified(),
                        mimeType = mime,
                        type = type
                    )
                )
            }
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

    fun cancelOpening() {
        openingJob?.cancel()
        _isOpeningDoc.value = false
    }

    fun openDocument(doc: DocumentFile) {
        if (_isOpeningDoc.value) return

        openingJob?.cancel()
        openingJob = viewModelScope.launch(provider.ioDispatcher) {
            _isOpeningDoc.value = true
            try {
                val sizeInMb = doc.size / (1024 * 1024)
                val dynamicTimeout = (10000L + (sizeInMb * 1000L)).coerceAtMost(45000L)

                val res = withTimeoutOrNull(dynamicTimeout) {
                    documentEngine.open(doc)
                }

                if (res == null) {
                    _events.send(DocumentUiEvent.DocumentError(DocumentErrorType.Timeout))
                    return@launch
                }

                when (res) {
                    is EngineResult.OpenPdf -> {
                        _activePdfSession.value?.close()
                        _activePdfSession.value = res.session
                        _events.send(DocumentUiEvent.OpenPdf(res.uri, res.session.pageCount))
                    }
                    is EngineResult.OpenWord -> _events.send(DocumentUiEvent.OpenWord(res.blocks))
                    is EngineResult.OpenExcel -> _events.send(DocumentUiEvent.OpenExcel(res.sheets))
                    is EngineResult.OpenSlide -> _events.send(DocumentUiEvent.OpenSlide(res.pages))
                    is EngineResult.OpenCsv -> _events.send(DocumentUiEvent.OpenExcel(listOf(res.sheet)))
                    is EngineResult.OpenText -> _events.send(DocumentUiEvent.OpenText(res.content))
                    is EngineResult.Unsupported, is EngineResult.Error -> {
                        val errorMessage = (res as? EngineResult.Error)?.message ?: ""
                        val isEncrypted = errorMessage.contains("password", true) || errorMessage.contains("encrypt", true)

                        if (isEncrypted) {
                            _events.send(DocumentUiEvent.DocumentError(DocumentErrorType.PasswordProtected))
                        } else {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(doc.uri, doc.mimeType ?: "*/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            _events.send(DocumentUiEvent.LaunchIntent(Intent.createChooser(intent, "Open with")))
                        }
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _events.send(DocumentUiEvent.DocumentError(DocumentErrorType.Generic(e.localizedMessage ?: "Failed to open document")))
                Log.e("DocumentViewModel", "Error opening document", e)
            } finally {
                _isOpeningDoc.value = false
            }
        }
    }

    fun clearActivePdfSession() {
        _activePdfSession.value = null
    }

    private fun loadHistory() {
        viewModelScope.launch(provider.ioDispatcher) {
            val historyJson = prefs.getString("history", "[]") ?: "[]"
            try {
                val arr = JSONArray(historyJson)
                val loaded = mutableListOf<ReadingState>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    loaded.add(
                        ReadingState(
                            uri = Uri.parse(obj.getString("uri")),
                            page = obj.getInt("page"),
                            scale = obj.getDouble("scale").toFloat(),
                            offsetX = obj.getDouble("offsetX").toFloat(),
                            offsetY = obj.getDouble("offsetY").toFloat(),
                            timestamp = obj.getLong("timestamp")
                        )
                    )
                }
                _readingHistory.value = loaded
            } catch (e: Exception) {
                Log.e("DocumentViewModel", "Failed to load history", e)
            }
        }
    }

    fun saveReadingState(state: ReadingState) {
        viewModelScope.launch {
            readingStateFlow.emit(state)
        }
    }

    private suspend fun persistReadingState(state: ReadingState) = withContext(provider.ioDispatcher) {
        val currentHistory = _readingHistory.value.toMutableList()
        currentHistory.removeAll { it.uri == state.uri }
        currentHistory.add(0, state)
        val limited = currentHistory.take(20)
        _readingHistory.value = limited

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
            Log.e("DocumentViewModel", "Failed to save history", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        openingJob?.cancel()
        _activePdfSession.value?.close()
    }
}