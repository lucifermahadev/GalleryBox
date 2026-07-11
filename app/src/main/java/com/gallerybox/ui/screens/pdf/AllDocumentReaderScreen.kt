@file:Suppress("unused", "OPT_IN_USAGE", "DEPRECATION")
package com.gallerybox.ui.screens.pdf

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gallerybox.engine.*
import com.gallerybox.viewmodel.*
import kotlinx.coroutines.*
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

val DocumentType.icon: ImageVector get() = when(this) { DocumentType.PDF -> Icons.Rounded.PictureAsPdf; DocumentType.WORD -> Icons.AutoMirrored.Rounded.Article; DocumentType.EXCEL -> Icons.Rounded.TableChart; DocumentType.SLIDE -> Icons.Rounded.Slideshow; DocumentType.TXT, DocumentType.CSV, DocumentType.JSON, DocumentType.XML, DocumentType.HTML -> Icons.Rounded.TextSnippet; DocumentType.CODE -> Icons.Rounded.Code; else -> Icons.AutoMirrored.Rounded.InsertDriveFile }
val DocumentType.color: Color get() = when(this) { DocumentType.PDF -> Color(0xFFE53935); DocumentType.WORD -> Color(0xFF1E88E5); DocumentType.EXCEL -> Color(0xFF43A047); DocumentType.SLIDE -> Color(0xFFFB8C00); DocumentType.TXT, DocumentType.CSV, DocumentType.JSON, DocumentType.XML, DocumentType.HTML -> Color(0xFF757575); DocumentType.CODE -> Color(0xFF00897B); else -> Color(0xFF9E9E9E) }
val DocumentFile.formattedSize: String get() { if (size <= 0) return "0 B"; val u = arrayOf("B", "KB", "MB", "GB", "TB"); val d = (log10(size.toDouble()) / log10(1024.0)).toInt(); return DecimalFormat("#,##0.#").format(size / 1024.0.pow(d.toDouble())) + " " + u[d] }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllDocumentReaderScreen(onBack: () -> Unit, onOpenDocument: (Long) -> Unit, viewModel: DocumentViewModel = hiltViewModel()) {
    val documents by viewModel.documents.collectAsState(); val history by viewModel.readingHistory.collectAsState(); val isListLoading by viewModel.isListLoading.collectAsState(); val searchQuery by viewModel.docSearchQuery.collectAsState(); val currentSort by viewModel.docSortType.collectAsState()
    val context = LocalContext.current; val lifecycleOwner = LocalLifecycleOwner.current; val colors = MaterialTheme.colorScheme
    LaunchedEffect(viewModel.events, lifecycleOwner.lifecycle) { lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.events.collect { e -> when (e) { is DocumentUiEvent.LaunchIntent -> try { context.startActivity(e.intent) } catch (_: Exception) {}; is DocumentUiEvent.ShowToast -> Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show(); is DocumentUiEvent.DocumentError -> Toast.makeText(context, e.message, Toast.LENGTH_LONG).show(); else -> Unit } } } }
    Box(Modifier.fillMaxSize()) {
        Scaffold(modifier = Modifier.fillMaxSize(), topBar = { DocReaderListTopBar(onBack, documents.size, currentSort) { viewModel.updateDocSortType(it) } }) { p ->
            DocReaderListContent(padding = p, docs = documents, history = history, isLoading = isListLoading, query = searchQuery, onQueryChange = { viewModel.updateDocSearchQuery(it) }, onOpen = { onOpenDocument(it.id) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocReaderListTopBar(onBack: () -> Unit, count: Int, currentSort: DocumentSortType, onSortChange: (DocumentSortType) -> Unit) {
    TopAppBar(title = { Column { Text("Documents", fontWeight = FontWeight.Bold, fontSize = 20.sp); Text("$count files", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }, actions = { IconButton(onClick = { onSortChange(when(currentSort) { DocumentSortType.DATE -> DocumentSortType.SIZE; DocumentSortType.SIZE -> DocumentSortType.NAME; DocumentSortType.NAME -> DocumentSortType.DATE; else -> DocumentSortType.DATE }) }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DocReaderListContent(padding: PaddingValues, docs: List<DocumentFile>, history: List<ReadingState>, isLoading: Boolean, query: String, onQueryChange: (String) -> Unit, onOpen: (DocumentFile) -> Unit) {
    val df = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }; var selectedCategory by remember { mutableStateOf<String?>(null) }
    val validDocExts = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "json", "xml", "html", "md"); val validCodeExts = setOf("kt", "java", "cpp", "c", "h", "py", "js", "ts", "css", "php", "sql", "sh")
    val groupedDocs = remember(docs, selectedCategory) { val map = mutableMapOf<String, List<DocumentFile>>(); val docFiles = docs.filter { it.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in validDocExts }; val codeFiles = docs.filter { it.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in validCodeExts }; if (docFiles.isNotEmpty() && (selectedCategory == null || selectedCategory == "Documents")) map["Documents"] = docFiles; if (codeFiles.isNotEmpty() && (selectedCategory == null || selectedCategory == "Code Files")) map["Code Files"] = codeFiles; map }
    LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize().padding(padding)) {
        item {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                OutlinedTextField(value = query, onValueChange = onQueryChange, placeholder = { Text("Search files...") }, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(12.dp), singleLine = true)
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { item { FilterChip(selected = selectedCategory == null, onClick = { selectedCategory = null }, label = { Text("All") }) }; item { FilterChip(selected = selectedCategory == "Documents", onClick = { selectedCategory = "Documents" }, label = { Text("Documents") }) }; item { FilterChip(selected = selectedCategory == "Code Files", onClick = { selectedCategory = "Code Files" }, label = { Text("Code Files") }) } }
                if (history.isNotEmpty() && query.isBlank()) { Text("Continue Reading", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(history.size) { i -> val h = history[i]; val d = docs.find { it.uri == h.uri }; if (d != null) Card(onClick = { onOpen(d) }, modifier = Modifier.width(160.dp)) { Column(Modifier.padding(12.dp)) { Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold); Text("Page ${h.page}", style = MaterialTheme.typography.bodySmall) } } } } }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (isLoading && docs.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } } else if (groupedDocs.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("No matching documents found", color = MaterialTheme.colorScheme.onSurfaceVariant) } } else {
            listOf("Documents", "Code Files").forEach { sec ->
                val sectionDocs = groupedDocs[sec] ?: return@forEach; if (sectionDocs.isEmpty()) return@forEach
                stickyHeader { Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 8.dp)) { Text("$sec (${sectionDocs.size})", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                items(items = sectionDocs, key = { it.id }) { doc -> Row(Modifier.fillMaxWidth().clickable { onOpen(doc) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(doc.type.color.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) { Icon(doc.type.icon, null, tint = doc.type.color) }; Spacer(Modifier.width(16.dp)); Column(Modifier.weight(1f)) { Text(doc.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${doc.formattedSize} • ${df.format(Date(doc.dateModified))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }; HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)) }
            }
        }
    }
}

@Composable
fun DocumentViewerScreen(fileId: Long, viewModel: DocumentViewModel = hiltViewModel(), onBack: () -> Unit) {
    val allDocs by viewModel.documents.collectAsState(); val isListLoading by viewModel.isListLoading.collectAsState(); val doc = remember(allDocs, fileId) { allDocs.find { it.id == fileId } ?: viewModel.getDocumentById(fileId) }
    LaunchedEffect(fileId) { if (allDocs.isEmpty()) viewModel.loadAllDocuments() }
    if (doc == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (isListLoading) CircularProgressIndicator() else Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Document not found"); Spacer(Modifier.height(12.dp)); Button(onClick = { viewModel.loadAllDocuments() }) { Text("Reload") } } }; return }
    val timeoutDuration = when { doc.size < 10*1024*1024 -> 10000L; doc.size < 50*1024*1024 -> 20000L; else -> 40000L }
    when (doc.type) {
        DocumentType.PDF -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) PdfViewerScreen(doc, viewModel, onBack) else Toast.makeText(LocalContext.current, "PDF viewing requires Android 10+", Toast.LENGTH_SHORT).show()
        DocumentType.WORD -> WordViewerScreen(doc, viewModel, timeoutDuration, onBack)
        DocumentType.EXCEL, DocumentType.CSV -> ExcelViewerScreen(doc, viewModel, timeoutDuration, onBack)
        DocumentType.SLIDE -> SlideViewerScreen(doc, viewModel, timeoutDuration, onBack)
        DocumentType.TXT, DocumentType.JSON, DocumentType.XML, DocumentType.CODE -> PlainTextViewerScreen(doc, viewModel, onBack)
        else -> HtmlWebViewerScreen(doc, onBack)
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(doc: DocumentFile, viewModel: DocumentViewModel, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme; val density = LocalDensity.current.density; var pageCount by remember { mutableIntStateOf(0) }; var isRendering by remember { mutableStateOf(true) }; var hasError by remember { mutableStateOf(false) }; var retryTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(doc.uri, retryTrigger) {
        isRendering = true
        hasError = false
        // Use the public pdfRendererEngine property instead of accessing documentEngine directly
        val count = withTimeoutOrNull(15000) {
            viewModel.pdfRendererEngine.load(doc.uri)
        }
        if (count != null && count > 0) {
            pageCount = count
        } else {
            hasError = true
        }
        isRendering = false
    }
    DisposableEffect(doc.uri) {
        onDispose { viewModel.pdfRendererEngine.close() }
    }
    Scaffold(containerColor = colors.surfaceVariant, topBar = { TopAppBar(title = { Column { Text(doc.name, maxLines = 1, fontWeight = FontWeight.Bold, fontSize = 16.sp); Text("$pageCount Pages", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) } }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface)) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (isRendering) CircularProgressIndicator(Modifier.align(Alignment.Center)) else if (hasError) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.ErrorOutline, null, tint = colors.error, modifier = Modifier.size(48.dp)); Spacer(Modifier.height(8.dp)); Text("Failed to load PDF", color = colors.error); Button(onClick = { retryTrigger++ }) { Text("Retry") } } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(pageCount) { pIdx ->
                        var scale by remember { mutableFloatStateOf(1f) }; var offset by remember { mutableStateOf(Offset.Zero) }
                        val ts = rememberTransformableState { z, p, _ -> scale = (scale * z).coerceIn(1f, 5f); if (scale > 1f) offset += p else offset = Offset.Zero }
                        Box(Modifier.fillMaxWidth().aspectRatio(1f/1.414f).shadow(4.dp).background(Color.White).clipToBounds().pointerInput(Unit) { detectTapGestures(onDoubleTap = { if(scale > 1f) { scale=1f; offset=Offset.Zero } else scale=2.5f }) }.transformable(ts).pointerInput(Unit) { detectDragGestures { c, d -> if (scale > 1f) { c.consume(); val mx = (size.width*(scale-1f))/2f; val my = (size.height*(scale-1f))/2f; offset = Offset((offset.x+d.x).coerceIn(-mx, mx), (offset.y+d.y).coerceIn(-my, my)) } } }) {
                            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }) {
                                var bmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }; DisposableEffect(pIdx) { onDispose { bmp = null } }
                                LaunchedEffect(pIdx) {
                                    if (bmp == null || bmp?.isRecycled == true) {
                                        // Use the public pdfRendererEngine property
                                        bmp = viewModel.pdfRendererEngine.renderPage(pIdx, density.coerceIn(1f, 2f))

                                        viewModel.saveReadingState(
                                            ReadingState(doc.uri, pIdx, scale, offset.x, offset.y, System.currentTimeMillis())
                                        )
                                    }
                                }
                                if (bmp != null && !bmp!!.isRecycled) Image(bitmap = bmp!!.asImageBitmap(), contentDescription = "Page ${pIdx+1}", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillWidth) else CircularProgressIndicator(Modifier.align(Alignment.Center))
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
        is WordBlock.Paragraph -> Row(Modifier.padding(bottom = 8.dp, top = if (b.headingLevel != null) 8.dp else 0.dp)) { if (b.isListItem) Text("• ", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp)); Text(text = b.runs.joinToString("") { it.text }, fontWeight = if (b.headingLevel != null) FontWeight.Bold else FontWeight.Normal, fontSize = if (b.headingLevel != null) (24 - b.headingLevel).sp else 16.sp, color = MaterialTheme.colorScheme.onSurface) }
        is WordBlock.Table -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).border(1.dp, Color.Gray)) { b.rows.forEach { row -> Row(Modifier.fillMaxWidth().border(0.5.dp, Color.LightGray)) { row.cells.forEach { cell -> Box(Modifier.weight(1f).padding(6.dp)) { Column { cell.blocks.forEach { WordBlockItem(it) } } } } } } }
        is WordBlock.Image -> BitmapFactory.decodeByteArray(b.byteArray, 0, b.byteArray.size)?.asImageBitmap()?.let { bmp -> Image(bitmap = bmp, contentDescription = "Document Image", modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentScale = ContentScale.Inside) }
        is WordBlock.Header -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).alpha(0.6f)) { b.blocks.forEach { WordBlockItem(it) }; HorizontalDivider(Modifier.padding(top = 4.dp)) }
        is WordBlock.Footer -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).alpha(0.6f)) { HorizontalDivider(Modifier.padding(bottom = 4.dp)); b.blocks.forEach { WordBlockItem(it) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordViewerScreen(doc: DocumentFile, viewModel: DocumentViewModel, timeoutMs: Long, onBack: () -> Unit) {
    var blocks by remember { mutableStateOf<List<WordBlock>?>(null) }; var isLoading by remember { mutableStateOf(true) }
    LaunchedEffect(doc.uri) {
        isLoading = true
        // Use the public docxEngine property instead of accessing documentEngine directly
        blocks = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                viewModel.docxEngine.parse(doc.uri)
            }
        }
        isLoading = false
    }
    Scaffold(topBar = { TopAppBar(title = { Text(doc.name, maxLines = 1) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { p ->
        Box(Modifier.fillMaxSize().padding(p)) { if (isLoading) CircularProgressIndicator(Modifier.align(Alignment.Center)) else if (blocks.isNullOrEmpty()) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { Text("Document Parsing Failed", fontWeight = FontWeight.Bold); Text("Could not render the document contents.", style = MaterialTheme.typography.bodySmall) } else SelectionContainer { LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) { items(blocks!!.size) { i -> WordBlockItem(blocks!![i]) } } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExcelViewerScreen(doc: DocumentFile, viewModel: DocumentViewModel, timeoutMs: Long, onBack: () -> Unit) {
    var sheets by remember { mutableStateOf<List<VirtualSheet>?>(null) }; var selected by remember { mutableIntStateOf(0) }; var isLoading by remember { mutableStateOf(true) }
    LaunchedEffect(doc.uri) {
        isLoading = true
        // Use the public xlsxEngine property instead of accessing documentEngine directly
        sheets = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                viewModel.xlsxEngine.parse(doc.uri)
            }
        }
        isLoading = false
    }
    Scaffold(topBar = { TopAppBar(title = { Text(doc.name, maxLines = 1) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { p ->
        Column(Modifier.padding(p).fillMaxSize()) {
            if (isLoading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } else if (sheets.isNullOrEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Document could not be parsed.", textAlign = TextAlign.Center) } else {
                if (sheets!!.size > 1) ScrollableTabRow(selectedTabIndex = selected) { sheets!!.forEachIndexed { i, s -> Tab(selected == i, { selected = i }, text = { Text(s.name) }) } }
                val act = sheets!![selected]
                SelectionContainer { LazyColumn(Modifier.fillMaxSize()) { items(minOf(act.rowCount, 2000)) { r -> val row = act.rows.getOrNull(r)?.cells ?: emptyList(); LazyRow(Modifier.fillMaxWidth()) { items(row.size) { c -> Box(Modifier.widthIn(min = 100.dp).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant).padding(8.dp)) { Text(row[c].displayValue, style = MaterialTheme.typography.bodyMedium) } } } } } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlideViewerScreen(doc: DocumentFile, viewModel: DocumentViewModel, timeoutMs: Long, onBack: () -> Unit) {
    var pages by remember { mutableStateOf<List<SlidePage>?>(null) }; var isLoading by remember { mutableStateOf(true) }
    LaunchedEffect(doc.uri) {
        isLoading = true
        // Use the public pptxEngine property instead of accessing documentEngine directly
        pages = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                viewModel.pptxEngine.parse(doc.uri)
            }
        }
        isLoading = false
    }
    Scaffold(topBar = { TopAppBar(title = { Text(doc.name, maxLines = 1) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { p ->
        if (isLoading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } else if (pages.isNullOrEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Failed to parse presentation.", textAlign = TextAlign.Center) } else LazyColumn(Modifier.fillMaxSize().padding(p).background(Color.DarkGray), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            items(pages!!.size) { i -> val pg = pages!![i]
                Card(Modifier.fillMaxWidth().aspectRatio(max(1f, pg.width) / max(1f, pg.height)), elevation = CardDefaults.cardElevation(6.dp)) {
                    BoxWithConstraints(Modifier.fillMaxSize().background(Color.White)) { val sx = maxWidth.value / max(1f, pg.width); val sy = maxHeight.value / max(1f, pg.height)
                        pg.elements.forEach { el -> when (el) {
                            is SlideElement.Text -> Text(el.text, fontSize = ((el.fontSize ?: 24f) * sy).sp, modifier = Modifier.offset((el.x * sx).dp, (el.y * sy).dp).size((el.width * sx).dp, (el.height * sy).dp))
                            is SlideElement.Image -> BitmapFactory.decodeByteArray(el.byteArray, 0, el.byteArray.size)?.asImageBitmap()?.let { bmp -> Image(bitmap = bmp, contentDescription = null, modifier = Modifier.offset((el.x * sx).dp, (el.y * sy).dp).size((el.width * sx).dp, (el.height * sy).dp), contentScale = ContentScale.FillBounds) }
                            is SlideElement.Table -> Box(Modifier.offset((el.x * sx).dp, (el.y * sy).dp).size((el.width * sx).dp, (el.height * sy).dp).border(1.dp, Color.Black)) { Canvas(Modifier.fillMaxSize()) { val rowHeight = size.height / max(1, el.rows); val colWidth = size.width / max(1, el.cols); for (r in 1 until el.rows) drawLine(Color.Black, Offset(0f, r * rowHeight), Offset(size.width, r * rowHeight), 1f); for (c in 1 until el.cols) drawLine(Color.Black, Offset(c * colWidth, 0f), Offset(c * colWidth, size.height), 1f) } }
                            is SlideElement.Shape -> Box(Modifier.offset((el.x * sx).dp, (el.y * sy).dp).size((el.width * sx).dp, (el.height * sy).dp).background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(4.dp)).border(1.dp, Color.Black.copy(alpha = 0.2f), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) { if (!el.text.isNullOrBlank()) Text(el.text, fontSize = ((14f * sy).coerceAtLeast(8f)).sp, textAlign = TextAlign.Center, color = Color.Black) }
                        } }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlainTextViewerScreen(doc: DocumentFile, viewModel: DocumentViewModel, onBack: () -> Unit) {
    var txt by remember { mutableStateOf("") }; var isLoading by remember { mutableStateOf(true) }
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
    Scaffold(topBar = { TopAppBar(title = { Text(doc.name, maxLines = 1) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { p ->
        if (isLoading) Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) { CircularProgressIndicator() } else SelectionContainer { Text(text = txt, modifier = Modifier.fillMaxSize().padding(p).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HtmlWebViewerScreen(doc: DocumentFile, onBack: () -> Unit) {
    var wvRef by remember { mutableStateOf<WebView?>(null) }; var htmlContent by remember { mutableStateOf<String?>(null) }; val context = LocalContext.current
    LaunchedEffect(doc.uri) { withContext(Dispatchers.IO) { try { context.contentResolver.openInputStream(doc.uri)?.use { stream -> htmlContent = stream.bufferedReader().readText() } } catch (e: Exception) { htmlContent = "<html><body>Error loading content</body></html>" } } }
    DisposableEffect(Unit) { onDispose { wvRef?.apply { clearHistory(); clearCache(true); loadUrl("about:blank"); removeAllViews(); destroy() }; wvRef = null } }
    Scaffold(topBar = { TopAppBar(title = { Text(doc.name, maxLines = 1) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { p ->
        if (htmlContent != null) AndroidView(factory = { ctx -> WebView(ctx).apply { wvRef = this; layoutParams = ViewGroup.LayoutParams(-1, -1); settings.builtInZoomControls = true; settings.displayZoomControls = false; settings.useWideViewPort = true; webChromeClient = WebChromeClient(); webViewClient = WebViewClient() } }, update = { webView -> webView.loadDataWithBaseURL(null, htmlContent!!, "text/html", "UTF-8", null) }, modifier = Modifier.padding(p).fillMaxSize()) else Box(Modifier.padding(p).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}