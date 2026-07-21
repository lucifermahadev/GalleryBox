@file:Suppress("unused", "OPT_IN_USAGE", "DEPRECATION")

package com.gallerybox.ui.screens.pdf

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gallerybox.data.*
import com.gallerybox.engine.*
import com.gallerybox.viewmodel.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

// --- Extension Properties ---
val DocumentType.icon: ImageVector
    get() = when(this) {
        DocumentType.PDF -> Icons.Rounded.PictureAsPdf
        DocumentType.WORD -> Icons.AutoMirrored.Filled.Article
        DocumentType.EXCEL -> Icons.Rounded.TableChart
        DocumentType.SLIDE -> Icons.Rounded.Slideshow
        DocumentType.TXT, DocumentType.CSV, DocumentType.JSON, DocumentType.XML, DocumentType.HTML -> Icons.Rounded.TextSnippet
        DocumentType.CODE -> Icons.Rounded.Code
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

val DocumentType.color: Color
    get() = when(this) {
        DocumentType.PDF -> Color(0xFFE53935)
        DocumentType.WORD -> Color(0xFF1E88E5)
        DocumentType.EXCEL -> Color(0xFF43A047)
        DocumentType.SLIDE -> Color(0xFFFB8C00)
        DocumentType.TXT, DocumentType.CSV, DocumentType.JSON, DocumentType.XML, DocumentType.HTML -> Color(0xFF757575)
        DocumentType.CODE -> Color(0xFF00897B)
        else -> Color(0xFF9E9E9E)
    }

val DocumentFile.formattedSize: String
    get() {
        if (size <= 0) return "0 B"
        val u = arrayOf("B", "KB", "MB", "GB", "TB")
        val d = (log10(size.toDouble()) / log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(size / 1024.0.pow(d.toDouble())) + " " + u[d]
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllDocumentReaderScreen(
    onBack: () -> Unit,
    onOpenDocument: (Long) -> Unit,
    viewModel: DocumentViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences("DocPrefs", Context.MODE_PRIVATE) }

    // SAF State
    var grantedUri by remember { mutableStateOf<Uri?>(null) }
    var isCheckingPermission by remember { mutableStateOf(true) }

    // Check for existing persisted SAF permissions
    LaunchedEffect(Unit) {
        val savedUriStr = prefs.getString("document_tree_uri", null)
        if (savedUriStr != null) {
            val uri = Uri.parse(savedUriStr)
            val persistedPermissions = context.contentResolver.persistedUriPermissions
            val hasPermission = persistedPermissions.any { it.uri == uri && it.isReadPermission }

            if (hasPermission) {
                grantedUri = uri
            } else {
                prefs.edit().remove("document_tree_uri").apply()
            }
        }
        isCheckingPermission = false
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString("document_tree_uri", uri.toString()).apply()
            grantedUri = uri
        }
    }

    // Handle Toast and Errors
    LaunchedEffect(viewModel.events, lifecycleOwner.lifecycle) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { e ->
                when (e) {
                    is DocumentUiEvent.LaunchIntent -> try { context.startActivity(e.intent) } catch (_: Exception) {}
                    is DocumentUiEvent.ShowToast -> Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                    is DocumentUiEvent.DocumentError -> {
                        val errorMsg = when (e.error) {
                            is DocumentErrorType.PasswordProtected -> "Document is password protected"
                            is DocumentErrorType.Corrupted -> "Document appears corrupted"
                            is DocumentErrorType.Unsupported -> "Format not supported"
                            is DocumentErrorType.Timeout -> "Loading timed out"
                            is DocumentErrorType.Generic -> (e.error as DocumentErrorType.Generic).message
                        }
                        Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                    }
                    else -> Unit
                }
            }
        }
    }

    if (isCheckingPermission) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    // IF NO SAF PERMISSION GRANTED, SHOW THE GUARD UI
    if (grantedUri == null) {
        DocumentFolderGuardUI(
            onBack = onBack,
            onGrant = { launcher.launch(null) }
        )
        return
    }

    // ONCE PERMISSION GRANTED, LOAD DOCUMENTS & SHOW LIST
    val documents by viewModel.documents.collectAsState()
    val history by viewModel.readingHistory.collectAsState()
    val isListLoading by viewModel.isListLoading.collectAsState()
    val searchQuery by viewModel.docSearchQuery.collectAsState()
    val currentSort by viewModel.docSortType.collectAsState()

    LaunchedEffect(grantedUri) {
        if (documents.isEmpty()) {
            viewModel.loadAllDocuments(grantedUri!!)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                DocReaderListTopBar(onBack, documents.size, currentSort) { viewModel.updateDocSortType(it) }
            }
        ) { paddingValues ->
            DocReaderListContent(
                padding = paddingValues,
                docs = documents,
                history = history,
                isLoading = isListLoading,
                query = searchQuery,
                onQueryChange = { viewModel.updateDocSearchQuery(it) },
                onOpen = { onOpenDocument(it.id) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentFolderGuardUI(onBack: () -> Unit, onGrant: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Documents", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }
            )
        }
    ) { p ->
        Box(
            modifier = Modifier
                .padding(p)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Folder Access Required",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "To view your documents, please select a folder (like Documents or Downloads) where your files are stored. You only need to do this once.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onGrant, modifier = Modifier.height(56.dp)) {
                    Text("Select Folder", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 24.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocReaderListTopBar(
    onBack: () -> Unit,
    count: Int,
    currentSort: DocumentSortType,
    onSortChange: (DocumentSortType) -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text("Documents", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("$count files", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
        },
        actions = {
            IconButton(onClick = {
                onSortChange(
                    when(currentSort) {
                        DocumentSortType.DATE -> DocumentSortType.SIZE
                        DocumentSortType.SIZE -> DocumentSortType.NAME
                        DocumentSortType.NAME -> DocumentSortType.DATE
                        else -> DocumentSortType.DATE
                    }
                )
            }) {
                Icon(Icons.AutoMirrored.Filled.Sort, "Sort")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DocReaderListContent(
    padding: PaddingValues,
    docs: List<DocumentFile>,
    history: List<ReadingState>,
    isLoading: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpen: (DocumentFile) -> Unit
) {
    val df = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    val validDocExts = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "json", "xml", "html", "md")
    val validCodeExts = setOf("kt", "java", "cpp", "c", "h", "py", "js", "ts", "css", "php", "sql", "sh")

    val groupedDocs = remember(docs, selectedCategory) {
        val map = mutableMapOf<String, List<DocumentFile>>()
        val docFiles = docs.filter { it.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in validDocExts }
        val codeFiles = docs.filter { it.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in validCodeExts }

        if (docFiles.isNotEmpty() && (selectedCategory == null || selectedCategory == "Documents")) map["Documents"] = docFiles
        if (codeFiles.isNotEmpty() && (selectedCategory == null || selectedCategory == "Code Files")) map["Code Files"] = codeFiles
        map
    }

    LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize().padding(padding)) {
        item {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Search files...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { FilterChip(selected = selectedCategory == null, onClick = { selectedCategory = null }, label = { Text("All") }) }
                    item { FilterChip(selected = selectedCategory == "Documents", onClick = { selectedCategory = "Documents" }, label = { Text("Documents") }) }
                    item { FilterChip(selected = selectedCategory == "Code Files", onClick = { selectedCategory = "Code Files" }, label = { Text("Code Files") }) }
                }

                AnimatedVisibility(visible = history.isNotEmpty() && query.isBlank()) {
                    Column {
                        Text("Continue Reading", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(count = history.size) { i ->
                                val h = history[i]
                                val d = docs.find { docFile -> docFile.uri == h.uri }
                                if (d != null) {
                                    Card(onClick = { onOpen(d) }, modifier = Modifier.width(160.dp)) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                            Text("Page ${h.page + 1}", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        if (isLoading && docs.isEmpty()) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else if (groupedDocs.isEmpty()) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("No matching documents found", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        } else {
            listOf("Documents", "Code Files").forEach { sec ->
                val sectionDocs = groupedDocs[sec] ?: return@forEach
                if (sectionDocs.isEmpty()) return@forEach

                stickyHeader {
                    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("$sec (${sectionDocs.size})", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                items(items = sectionDocs, key = { it.id }) { doc ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(doc) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(doc.type.color.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                            Icon(doc.type.icon, null, tint = doc.type.color)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(doc.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${doc.formattedSize} • ${df.format(Date(doc.dateModified))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
fun DocumentViewerScreen(fileId: Long, viewModel: DocumentViewModel = hiltViewModel(), onBack: () -> Unit) {
    val allDocs by viewModel.documents.collectAsState()
    val isListLoading by viewModel.isListLoading.collectAsState()

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("DocPrefs", Context.MODE_PRIVATE) }

    val doc: DocumentFile? = remember(allDocs, fileId) {
        allDocs.find { it.id == fileId } ?: viewModel.getDocumentById(fileId)
    }

    LaunchedEffect(fileId) {
        if (allDocs.isEmpty()) {
            val savedUriStr = prefs.getString("document_tree_uri", null)
            if (savedUriStr != null) {
                viewModel.loadAllDocuments(Uri.parse(savedUriStr))
            }
        }
    }

    if (doc == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isListLoading) {
                CircularProgressIndicator()
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Document not found")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        val savedUriStr = prefs.getString("document_tree_uri", null)
                        if (savedUriStr != null) {
                            viewModel.loadAllDocuments(Uri.parse(savedUriStr))
                        }
                    }) { Text("Reload") }
                }
            }
        }
        return
    }

    val timeoutDuration = when {
        doc.size < 10 * 1024 * 1024 -> 10000L
        doc.size < 50 * 1024 * 1024 -> 20000L
        else -> 40000L
    }

    when (doc.type) {
        DocumentType.PDF -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) PdfViewerScreen(doc, viewModel, onBack) else Toast.makeText(LocalContext.current, "PDF viewing requires Android 10+", Toast.LENGTH_SHORT).show()
        DocumentType.WORD -> WordViewerScreen(doc, viewModel, timeoutDuration, onBack)
        DocumentType.EXCEL, DocumentType.CSV -> ExcelViewerScreen(doc, viewModel, timeoutDuration, onBack)
        DocumentType.SLIDE -> SlideViewerScreen(doc, viewModel, timeoutDuration, onBack)
        DocumentType.TXT, DocumentType.JSON, DocumentType.XML, DocumentType.CODE -> PlainTextViewerScreen(doc, viewModel, onBack)
        else -> HtmlWebViewerScreen(doc, onBack)
    }
}

// ==========================================
// FULLY OPTIMIZED PDF VIEWER
// ==========================================
@RequiresApi(Build.VERSION_CODES.Q)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(doc: DocumentFile, viewModel: DocumentViewModel, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current.density

    // Core PDF Session State
    var session by remember { mutableStateOf<PdfRenderSession?>(null) }
    var isInitializing by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var retryTrigger by remember { mutableIntStateOf(0) }

    val listState = rememberLazyListState()

    // Global Zoom & Pan state to mimic authentic continuous PDF scrolling
    var globalScale by remember { mutableFloatStateOf(1f) }
    var globalOffset by remember { mutableStateOf(Offset.Zero) }

    val history by viewModel.readingHistory.collectAsState()
    val lastState = remember(history) { history.find { it.uri == doc.uri } }
    val activeSession by viewModel.activePdfSession.collectAsState()

    // Session Initialization: Uses existing if triggered externally, creates safely if opened inside the app
    LaunchedEffect(doc.uri, retryTrigger) {
        isInitializing = true
        hasError = false

        val loadedSession = withContext(Dispatchers.IO) {
            if (activeSession != null && activeSession?.pageCount ?: 0 > 0) {
                activeSession
            } else {
                viewModel.pdfEngine.createSession(doc.uri)
            }
        }

        if (loadedSession != null && loadedSession.pageCount > 0) {
            session = loadedSession
        } else {
            hasError = true
        }
        isInitializing = false
    }

    // Cleanly close resources and save state ONLY on exit
    DisposableEffect(doc.uri) {
        onDispose {
            if (session != null) {
                val currentVisible = listState.firstVisibleItemIndex
                viewModel.saveReadingState(
                    ReadingState(doc.uri, currentVisible, globalScale, globalOffset.x, globalOffset.y, System.currentTimeMillis())
                )
            }
            session?.close()
            viewModel.clearActivePdfSession()
        }
    }

    // Restore previous reading position
    LaunchedEffect(isInitializing, session) {
        if (!isInitializing && session != null && lastState != null) {
            globalScale = lastState.scale
            globalOffset = Offset(lastState.offsetX, lastState.offsetY)
            if (lastState.page in 0 until session!!.pageCount) {
                listState.scrollToItem(lastState.page)
            }
        }
    }

    // Debounced history save strictly on scrolling pause to prevent disk churn
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && !isInitializing && session != null) {
            val currentVisible = listState.firstVisibleItemIndex
            viewModel.saveReadingState(
                ReadingState(doc.uri, currentVisible, globalScale, globalOffset.x, globalOffset.y, System.currentTimeMillis())
            )
        }
    }

    Scaffold(
        containerColor = colors.surfaceVariant,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(doc.name, maxLines = 1, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        AnimatedVisibility(visible = !isInitializing && session != null) {
                            val current = remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }
                            Text("${current.value} / ${session?.pageCount ?: 0} Pages", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface)
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AnimatedContent(
                targetState = when {
                    isInitializing -> "LOADING"
                    hasError -> "ERROR"
                    else -> "READY"
                },
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                label = "PDF_State"
            ) { state ->
                when (state) {
                    "LOADING" -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    "ERROR" -> {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.ErrorOutline, null, tint = colors.error, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Failed to load PDF / Encrypted file", color = colors.error)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { retryTrigger++ }) { Text("Retry") }
                        }
                    }
                    "READY" -> {
                        val actualSession = session!!

                        // Global zoom applied via a wrapper box.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        globalScale = (globalScale * zoom).coerceIn(1f, 5f)
                                        if (globalScale > 1f) {
                                            val maxPanX = (size.width * (globalScale - 1f)) / 2f
                                            val maxPanY = (size.height * (globalScale - 1f)) / 2f
                                            globalOffset = Offset(
                                                (globalOffset.x + pan.x).coerceIn(-maxPanX, maxPanX),
                                                (globalOffset.y + pan.y).coerceIn(-maxPanY, maxPanY)
                                            )
                                        } else {
                                            globalOffset = Offset.Zero
                                        }
                                    }
                                }
                        ) {
                            LazyColumn(
                                state = listState,
                                // Optimization: Disable native list scrolling if zoomed in, so user can pan horizontally and vertically freely inside the zoomed document
                                userScrollEnabled = globalScale <= 1f,
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = globalScale
                                        scaleY = globalScale
                                        translationX = globalOffset.x
                                        translationY = globalOffset.y
                                    }
                            ) {
                                items(actualSession.pageCount, key = { it }) { pIdx ->
                                    var aspectRatio by remember { mutableFloatStateOf(1f / 1.414f) } // Default A4

                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(aspectRatio)
                                            .shadow(4.dp)
                                            .background(Color.White)
                                            .clipToBounds()
                                    ) {
                                        var bmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

                                        DisposableEffect(pIdx) {
                                            onDispose { bmp = null }
                                        }

                                        LaunchedEffect(pIdx) {
                                            withContext(Dispatchers.IO) {
                                                // Dynamic dimensions fetch accurately determines bounds
                                                val dims = actualSession.getPageDimensions(pIdx)
                                                if (dims != null && dims.second > 0) {
                                                    aspectRatio = dims.first.toFloat() / dims.second.toFloat()
                                                }
                                                // Limit render scale for low end devices (Clamped to prevent 4K textures from blowing memory)
                                                val optimalScale = density.coerceIn(1.5f, 2.5f)
                                                bmp = actualSession.renderPage(pIdx, optimalScale)
                                            }
                                        }

                                        AnimatedContent(
                                            targetState = bmp != null && !bmp!!.isRecycled,
                                            label = "Page_Render"
                                        ) { isRendered ->
                                            if (isRendered) {
                                                Image(
                                                    bitmap = bmp!!.asImageBitmap(),
                                                    contentDescription = "Page ${pIdx + 1}",
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.FillWidth
                                                )
                                            } else {
                                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                    CircularProgressIndicator(strokeWidth = 2.dp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WordBlockItem(b: WordBlock) {
    when (b) {
        is WordBlock.Paragraph -> {
            Row(Modifier.padding(bottom = 8.dp, top = if (b.headingLevel != null) 8.dp else 0.dp)) {
                if (b.isListItem) {
                    Text("• ", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp))
                }
                Text(
                    text = b.runs.joinToString("") { it.text },
                    fontWeight = if (b.headingLevel != null) FontWeight.Bold else FontWeight.Normal,
                    fontSize = if (b.headingLevel != null) (24 - b.headingLevel).sp else 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        is WordBlock.Table -> {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).border(1.dp, Color.Gray)) {
                b.rows.forEach { row ->
                    Row(Modifier.fillMaxWidth().border(0.5.dp, Color.LightGray)) {
                        row.cells.forEach { cell ->
                            Box(Modifier.weight(1f).padding(6.dp)) {
                                Column { cell.blocks.forEach { WordBlockItem(it) } }
                            }
                        }
                    }
                }
            }
        }
        is WordBlock.Image -> {
            BitmapFactory.decodeByteArray(b.byteArray, 0, b.byteArray.size)?.asImageBitmap()?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = "Document Image",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentScale = ContentScale.Inside
                )
            }
        }
        is WordBlock.Header -> {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).alpha(0.6f)) {
                b.blocks.forEach { WordBlockItem(it) }
                HorizontalDivider(Modifier.padding(top = 4.dp))
            }
        }
        is WordBlock.Footer -> {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).alpha(0.6f)) {
                HorizontalDivider(Modifier.padding(bottom = 4.dp))
                b.blocks.forEach { WordBlockItem(it) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordViewerScreen(doc: DocumentFile, viewModel: DocumentViewModel, timeoutMs: Long, onBack: () -> Unit) {
    var blocks by remember { mutableStateOf<List<WordBlock>?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(doc.uri) {
        isLoading = true
        blocks = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) { viewModel.docxEngine.parse(doc.uri) }
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc.name, maxLines = 1) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }
            )
        }
    ) { p ->
        Box(Modifier.fillMaxSize().padding(p)) {
            AnimatedContent(targetState = isLoading, label = "Word_Load") { loading ->
                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (blocks.isNullOrEmpty()) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("Document Parsing Failed", fontWeight = FontWeight.Bold)
                        Text("Could not render the document contents.", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    SelectionContainer {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                            items(blocks!!.size) { i -> WordBlockItem(blocks!![i]) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExcelViewerScreen(doc: DocumentFile, viewModel: DocumentViewModel, timeoutMs: Long, onBack: () -> Unit) {
    var sheets by remember { mutableStateOf<List<VirtualSheet>?>(null) }
    var selected by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(doc.uri) {
        isLoading = true
        sheets = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) { viewModel.xlsxEngine.parse(doc.uri) }
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc.name, maxLines = 1) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }
            )
        }
    ) { p ->
        Column(Modifier.padding(p).fillMaxSize()) {
            AnimatedContent(targetState = isLoading, label = "Excel_Load") { loading ->
                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (sheets.isNullOrEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Document could not be parsed.", textAlign = TextAlign.Center) }
                } else {
                    Column {
                        if (sheets!!.size > 1) {
                            ScrollableTabRow(selectedTabIndex = selected) {
                                sheets!!.forEachIndexed { i, s -> Tab(selected == i, { selected = i }, text = { Text(s.name) }) }
                            }
                        }
                        val act = sheets!![selected]
                        SelectionContainer {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(minOf(act.rowCount, 2000)) { r ->
                                    val row = act.rows.getOrNull(r)?.cells ?: emptyList()
                                    LazyRow(Modifier.fillMaxWidth()) {
                                        items(row.size, key = { it }) { c ->
                                            Box(Modifier.widthIn(min = 100.dp).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant).padding(8.dp)) {
                                                Text(row[c].displayValue, style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlideViewerScreen(doc: DocumentFile, viewModel: DocumentViewModel, timeoutMs: Long, onBack: () -> Unit) {
    var pages by remember { mutableStateOf<List<SlidePage>?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(doc.uri) {
        isLoading = true
        pages = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) { viewModel.pptxEngine.parse(doc.uri) }
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc.name, maxLines = 1) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }
            )
        }
    ) { p ->
        Box(Modifier.padding(p).fillMaxSize()) {
            AnimatedContent(targetState = isLoading, label = "Slide_Load") { loading ->
                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (pages.isNullOrEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Failed to parse presentation.", textAlign = TextAlign.Center) }
                } else {
                    LazyColumn(Modifier.fillMaxSize().background(Color.DarkGray), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        items(pages!!.size) { i ->
                            val pg = pages!![i]
                            Card(Modifier.fillMaxWidth().aspectRatio(max(1f, pg.width) / max(1f, pg.height)), elevation = CardDefaults.cardElevation(6.dp)) {
                                BoxWithConstraints(Modifier.fillMaxSize().background(Color.White)) {
                                    val sx = maxWidth.value / max(1f, pg.width)
                                    val sy = maxHeight.value / max(1f, pg.height)
                                    pg.elements.forEach { el ->
                                        when (el) {
                                            is SlideElement.Text -> Text(el.text, fontSize = ((el.fontSize ?: 24f) * sy).sp, modifier = Modifier.offset((el.x * sx).dp, (el.y * sy).dp).size((el.width * sx).dp, (el.height * sy).dp))
                                            is SlideElement.Image -> BitmapFactory.decodeByteArray(el.byteArray, 0, el.byteArray.size)?.asImageBitmap()?.let { bmp ->
                                                Image(bitmap = bmp, contentDescription = null, modifier = Modifier.offset((el.x * sx).dp, (el.y * sy).dp).size((el.width * sx).dp, (el.height * sy).dp), contentScale = ContentScale.FillBounds)
                                            }
                                            is SlideElement.Table -> Box(Modifier.offset((el.x * sx).dp, (el.y * sy).dp).size((el.width * sx).dp, (el.height * sy).dp).border(1.dp, Color.Black)) {
                                                Canvas(Modifier.fillMaxSize()) {
                                                    val rowHeight = size.height / max(1, el.rows)
                                                    val colWidth = size.width / max(1, el.cols)
                                                    for (r in 1 until el.rows) drawLine(Color.Black, Offset(0f, r * rowHeight), Offset(size.width, r * rowHeight), 1f)
                                                    for (c in 1 until el.cols) drawLine(Color.Black, Offset(c * colWidth, 0f), Offset(c * colWidth, size.height), 1f)
                                                }
                                            }
                                            is SlideElement.Shape -> Box(Modifier.offset((el.x * sx).dp, (el.y * sy).dp).size((el.width * sx).dp, (el.height * sy).dp).background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(4.dp)).border(1.dp, Color.Black.copy(alpha = 0.2f), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                                                if (!el.text.isNullOrBlank()) Text(el.text, fontSize = ((14f * sy).coerceAtLeast(8f)).sp, textAlign = TextAlign.Center, color = Color.Black)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlainTextViewerScreen(doc: DocumentFile, viewModel: DocumentViewModel, onBack: () -> Unit) {
    var txt by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(doc.uri) {
        isLoading = true
        withContext(Dispatchers.IO) {
            txt = when (val result = viewModel.rawTextEngine.read(doc.uri)) {
                is TextReadResult.Success -> result.text
                is TextReadResult.Preview -> result.text
                is TextReadResult.Error -> result.message
                is TextReadResult.BinaryFile -> "Binary file detected. Cannot parse as text."
                is TextReadResult.Paged -> "File too large. Pagination required."
            }
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc.name, maxLines = 1) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }
            )
        }
    ) { p ->
        AnimatedContent(targetState = isLoading, label = "Text_Load") { loading ->
            if (loading) {
                Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                SelectionContainer {
                    Text(
                        text = txt,
                        modifier = Modifier.fillMaxSize().padding(p).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HtmlWebViewerScreen(doc: DocumentFile, onBack: () -> Unit) {
    var wvRef by remember { mutableStateOf<WebView?>(null) }
    var htmlContent by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(doc.uri) {
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(doc.uri)?.use { stream -> htmlContent = stream.bufferedReader().readText() }
            } catch (e: Exception) {
                htmlContent = "<html><body>Error loading content</body></html>"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            wvRef?.apply {
                clearHistory()
                clearCache(true)
                loadUrl("about:blank")
                removeAllViews()
                destroy()
            }
            wvRef = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc.name, maxLines = 1) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }
            )
        }
    ) { p ->
        AnimatedContent(targetState = htmlContent != null, label = "Html_Load") { isLoaded ->
            if (isLoaded) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            wvRef = this
                            layoutParams = ViewGroup.LayoutParams(-1, -1)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.useWideViewPort = true
                            settings.javaScriptEnabled = false

                            webChromeClient = WebChromeClient()
                            webViewClient = WebViewClient()
                        }
                    },
                    update = { webView ->
                        webView.loadDataWithBaseURL(null, htmlContent!!, "text/html", "UTF-8", null)
                    },
                    modifier = Modifier.padding(p).fillMaxSize()
                )
            } else {
                Box(Modifier.padding(p).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}