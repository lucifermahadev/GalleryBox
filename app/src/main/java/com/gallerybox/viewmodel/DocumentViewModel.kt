@file:Suppress("BlockingMethodInNonBlockingContext", "UNUSED_PARAMETER", "unused", "FunctionName", "MemberVisibilityCanBePrivate", "OPT_IN_USAGE")

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
import java.util.Locale
import javax.inject.Inject
import com.gallerybox.data.*



@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class DocumentViewModel @Inject constructor(
    private val documentEngine: DocumentCoreEngine,
    private val recentEngine: RecentDocumentsEngine,
    private val provider: DocumentDispatcherProvider,
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // Expose specific engines for UI usage
    val pdfEngine: PdfEngine get() = documentEngine.pdfEngine
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

    private val _recentUpdateTrigger = MutableStateFlow(0)

    val documents = combine(
        _allDocs,
        _docSearchQuery.debounce(300),
        _docCategory,
        _docSortType,
        _recentUpdateTrigger
    ) { docs, query, category, sort, _ ->
        var seq = docs.asSequence()
        if (category != null) seq = seq.filter { it.type == category }
        if (query.isNotBlank()) seq = seq.filter { it.name.contains(query, ignoreCase = true) }

        val recents = recentEngine.getRecents()
        seq.sortedWith(
            when (sort) {
                DocumentSortType.DATE -> compareByDescending { it.dateModified }
                DocumentSortType.SIZE -> compareByDescending { it.size }
                DocumentSortType.NAME -> compareBy { it.name.lowercase(Locale.ROOT) }
                DocumentSortType.RECENT -> compareBy {
                    val idx = recents.indexOf(it.uri)
                    if (idx == -1) Int.MAX_VALUE else idx
                }
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
        loadAllDocuments()

        viewModelScope.launch(provider.ioDispatcher) {
            readingStateFlow
                .debounce(1500L)
                .collectLatest { state ->
                    persistReadingState(state)
                }
        }
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

            val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} NOT LIKE 'image/%' AND " +
                    "${MediaStore.Files.FileColumns.MIME_TYPE} NOT LIKE 'video/%' AND " +
                    "${MediaStore.Files.FileColumns.MIME_TYPE} NOT LIKE 'audio/%'"

            try {
                context.contentResolver.query(
                    MediaStore.Files.getContentUri("external"),
                    projection,
                    selection,
                    null,
                    "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)

                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()

                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: ""
                        val mime = cursor.getString(mimeCol) ?: ""
                        val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)

                        val type = documentEngine.fileDetection.detect(uri, name, mime)

                        if (type != DocumentType.UNKNOWN) {
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
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("DocumentViewModel", "Failed to load documents", e)
            } finally {
                withContext(Dispatchers.Main) {
                    _allDocs.value = fetched
                    _isListLoading.value = false
                }
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

    /**
     * Primary entry point for opening documents externally or generically.
     * Inside the app, navigation handles routing and viewers invoke engines directly to avoid double-loading.
     */
    fun openDocument(doc: DocumentFile) {
        if (_isOpeningDoc.value) return

        openingJob?.cancel()
        openingJob = viewModelScope.launch(provider.ioDispatcher) {
            _isOpeningDoc.value = true
            try {
                // Dynamic timeout based on file size
                val sizeInMb = doc.size / (1024 * 1024)
                val dynamicTimeout = (10000L + (sizeInMb * 1000L)).coerceAtMost(45000L)

                val res = withTimeoutOrNull(dynamicTimeout) {
                    documentEngine.open(doc)
                }

                if (res == null) {
                    _events.send(DocumentUiEvent.DocumentError(DocumentErrorType.Timeout))
                    return@launch
                }

                recentEngine.addRecent(doc.uri)
                _recentUpdateTrigger.value += 1

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
                    is EngineResult.OpenEpub -> _events.send(DocumentUiEvent.OpenEpub(res.chapters))
                    is EngineResult.OpenZip -> _events.send(DocumentUiEvent.OpenZip(res.contents))
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