package com.paperlight.reader.ui

import android.net.Uri
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.paperlight.reader.data.BookArtwork
import com.paperlight.reader.ui.reader.ImagePageReaderScreen
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paperlight.reader.data.BookEntity
import com.paperlight.reader.data.BookRepository
import com.paperlight.reader.data.DiscoverySourceEntity
import com.paperlight.reader.data.PdfEpubMode
import com.paperlight.reader.data.WebNovelJobState
import com.paperlight.reader.discovery.DiscoverySourceConfigCodec
import com.paperlight.reader.ui.reader.EpubReaderScreen
import com.paperlight.reader.webnovel.WebNovelScrapeOptions
import com.paperlight.reader.webnovel.WebNovelEpubUtility
import com.paperlight.reader.webnovel.WebNovelPreview
import com.paperlight.reader.ai.GroqAiClient
import com.paperlight.reader.ai.GroqApiKeyStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperlightApp(viewModel: LibraryViewModel) {
    val context = LocalContext.current
    val accessibilityPreferences = remember(context) { context.getSharedPreferences("accessibility", 0) }
    val readerPreferences = remember(context) { context.getSharedPreferences("reader", 0) }
    var uiTextScale by rememberSaveable {
        mutableFloatStateOf(accessibilityPreferences.getFloat("uiTextScale", 1f).coerceIn(.85f, 1.5f))
    }
    var reduceMotion by rememberSaveable { mutableStateOf(accessibilityPreferences.getBoolean("reduceMotion", false)) }
    var eInkMode by rememberSaveable { mutableStateOf(readerPreferences.getBoolean("eInkMode", false)) }
    var readingTextScale by rememberSaveable {
        mutableFloatStateOf(readerPreferences.getFloat("textSize", 1f).coerceIn(.7f, 2.2f))
    }
    val baseDensity = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(baseDensity.density, baseDensity.fontScale * uiTextScale)) {
        PaperlightAppContent(
            viewModel = viewModel,
            uiTextScale = uiTextScale,
            reduceMotion = reduceMotion,
            eInkMode = eInkMode,
            readingTextScale = readingTextScale,
            onUiTextScaleChange = { value ->
                uiTextScale = value.coerceIn(.85f, 1.5f)
                accessibilityPreferences.edit().putFloat("uiTextScale", uiTextScale).apply()
            },
            onReduceMotionChange = { value ->
                reduceMotion = value
                accessibilityPreferences.edit().putBoolean("reduceMotion", value).apply()
            },
            onEInkModeChange = { value ->
                eInkMode = value
                readerPreferences.edit().putBoolean("eInkMode", value).apply()
            },
            onReadingTextScaleChange = { value ->
                readingTextScale = value.coerceIn(.7f, 2.2f)
                readerPreferences.edit().putFloat("textSize", readingTextScale).apply()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PaperlightAppContent(
    viewModel: LibraryViewModel,
    uiTextScale: Float,
    reduceMotion: Boolean,
    eInkMode: Boolean,
    readingTextScale: Float,
    onUiTextScaleChange: (Float) -> Unit,
    onReduceMotionChange: (Boolean) -> Unit,
    onEInkModeChange: (Boolean) -> Unit,
    onReadingTextScaleChange: (Float) -> Unit,
) {
    val books by viewModel.books.collectAsState()
    val libraries by viewModel.libraries.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val webNovelJobs by viewModel.webNovelJobs.collectAsState()
    val discoverySources by viewModel.discoverySources.collectAsState()
    val conversionProgress by viewModel.conversionProgress.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var bookDetailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var selectedBooks by remember { mutableStateOf(setOf<String>()) }
    var activeLibraryId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val pagerState = rememberPagerState(pageCount = { 2 })
    var fileManagerMode by rememberSaveable { mutableStateOf(FileManagerMode.Browse) }
    var externalBook by remember { mutableStateOf<BookEntity?>(null) }
    var openingExternalFileName by remember { mutableStateOf<String?>(null) }
    val libraryPreferences = remember { context.getSharedPreferences("libraryAppearance", 0) }
    var listView by rememberSaveable { mutableStateOf(libraryPreferences.getBoolean("listView", false)) }
    var keepScreenOn by remember { mutableStateOf(libraryPreferences.getBoolean("keepScreenOn", false)) }
    var shelfColumns by remember { mutableStateOf(libraryPreferences.getInt("shelfColumns", 3).coerceIn(2, 4)) }
    var markRecent by remember { mutableStateOf(libraryPreferences.getBoolean("markRecent", true)) }
    var sortOrder by rememberSaveable { mutableStateOf(libraryPreferences.getString("sortOrder", "Recent") ?: "Recent") }
    var showSort by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("All") }
    var editingLibraryId by remember { mutableStateOf<String?>(null) }
    var deletingLibraryId by remember { mutableStateOf<String?>(null) }
    val libraryBusy by viewModel.libraryBusy.collectAsState()
    val backupRecoveryBlocked by viewModel.backupRecoveryBlocked.collectAsState()
    val backupProgress by viewModel.backupProgress.collectAsState()
    var libraryError by remember { mutableStateOf<String?>(null) }
    var showStatistics by remember { mutableStateOf(false) }
    var showAccessibility by remember { mutableStateOf(false) }
    var showGroqApiKeyScreen by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    var showBackupExportOptions by remember { mutableStateOf(false) }
    var backupIncludeApiKey by remember { mutableStateOf(false) }
    var backupPassword by remember { mutableStateOf("") }
    var backupPasswordConfirmation by remember { mutableStateOf("") }
    var backupPasswordVisible by remember { mutableStateOf(false) }
    var restorePasswordVisible by remember { mutableStateOf(false) }
    var restorePassword by remember { mutableStateOf("") }
    var restoreReplaceSettings by remember { mutableStateOf(false) }
    var restoreReplaceGroqKey by remember { mutableStateOf(false) }
    var exportBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSelectedBookActions by remember { mutableStateOf(false) }
    var showMoreActions by remember { mutableStateOf(false) }
    var showLibraryMenu by remember { mutableStateOf(false) }
    var showCreateLibrary by remember { mutableStateOf(false) }
    var libraryName by remember { mutableStateOf("") }
    var showMoveBooks by remember { mutableStateOf(false) }
    var showDeleteBooks by remember { mutableStateOf(false) }
    var showCreateFromWeb by remember { mutableStateOf(false) }
    var showWebNovelJobs by rememberSaveable { mutableStateOf(false) }
    var showBookDiscovery by rememberSaveable { mutableStateOf(false) }
    var pendingPdfPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPdfTitle by rememberSaveable { mutableStateOf("") }
    var pdfConversionMode by rememberSaveable { mutableStateOf(PdfEpubMode.Text) }
    var novelUrl by remember { mutableStateOf("") }
    var converterSourceId by remember { mutableStateOf<String?>(null) }
    var converterSourceBaseUrl by remember { mutableStateOf<String?>(null) }
    var sampleChapterUrl by rememberSaveable { mutableStateOf("") }
    var renderNovelInBrowser by rememberSaveable { mutableStateOf(false) }
    var chapterPreview by remember { mutableStateOf<WebNovelPreview?>(null) }
    var previewBusy by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var firstChapter by rememberSaveable { mutableStateOf("1") }
    var lastChapter by rememberSaveable { mutableStateOf("") }
    var chapterLinkSelector by remember { mutableStateOf("a[href]") }
    var chapterContentSelector by remember { mutableStateOf(".chapter-content, .chapter-body, #chapter-content, .read-content, .entry-content, article, main, #content") }
    var chapterTitleSelector by remember { mutableStateOf("h1, h2") }
    var removeSelector by remember { mutableStateOf("script, style, noscript, nav, header, footer, aside, .ads, .advertisement, .share, .comments") }
    var requestDelayText by rememberSaveable { mutableStateOf("500") }
    var savingScrapeSettings by remember { mutableStateOf(false) }
    var scrapeSettingsStatus by remember { mutableStateOf<String?>(null) }
    var aiScrapeBusy by remember { mutableStateOf(false) }
    var aiScrapeError by remember { mutableStateOf<String?>(null) }
    var showScrapeOptions by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf<File?>(null) }
    var candidates by remember { mutableStateOf<List<ExternalBookFile>?>(null) }
    var checked by remember { mutableStateOf(setOf<Uri>()) }

    fun applyScrapeOptions(options: WebNovelScrapeOptions) {
        chapterLinkSelector = options.chapterLinkSelector
        chapterContentSelector = options.chapterContentSelector
        chapterTitleSelector = options.chapterTitleSelector
        removeSelector = options.removeSelector
        renderNovelInBrowser = options.renderInBrowser
        requestDelayText = options.requestDelayMillis.toString()
    }

    fun beginManualWebsiteConversion() {
        if (converterSourceId != null) {
            applyScrapeOptions(WebNovelScrapeOptions())
            firstChapter = "1"
            lastChapter = ""
            showScrapeOptions = false
        }
        converterSourceId = null
        converterSourceBaseUrl = null
        savingScrapeSettings = false
        scrapeSettingsStatus = null
        showCreateFromWeb = true
    }

    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { viewModel.backup(it, restore = false, includeApiKey = backupIncludeApiKey, password = backupPassword.takeIf { backupIncludeApiKey }) }
        backupPassword = ""
        backupPasswordConfirmation = ""
        backupPasswordVisible = false
        backupIncludeApiKey = false
    }
    val restoreBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingRestore = uri
        restorePassword = ""
        restoreReplaceSettings = false
        restoreReplaceGroqKey = false
    }
    val exportBook = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val sourceId = exportBookId
        val target = result.data?.data?.takeIf { result.resultCode == android.app.Activity.RESULT_OK }
        exportBookId = null
        if (sourceId != null && target != null) viewModel.exportBook(sourceId, target)
    }
    fun shareBook(book: BookEntity) {
        runCatching {
            val file = File(book.localPath)
            require(file.isFile && file.canRead()) { "The original book file is missing or unreadable." }
            val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = bookMimeType(book.format)
                putExtra(Intent.EXTRA_STREAM, contentUri)
                clipData = ClipData.newUri(context.contentResolver, book.fileName, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share ${book.title} (${book.format})"))
        }.onFailure { scope.launch { snackbar.showSnackbar(it.message ?: "Could not share this book.") } }
    }
    fun startBookExport(book: BookEntity) {
        exportBookId = book.id
        try {
            exportBook.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = bookMimeType(book.format)
                putExtra(Intent.EXTRA_TITLE, book.fileName)
            })
        } catch (failure: Exception) {
            exportBookId = null
            scope.launch { snackbar.showSnackbar(failure.message ?: "Could not open the export destination picker.") }
        }
    }
    fun showFileManager(mode: FileManagerMode) {
        scope.launch {
            if (drawer.isOpen) drawer.close()
            fileManagerMode = mode
            pagerState.animateScrollToPage(1)
        }
    }

    fun returnToLibrary() {
        scope.launch {
            pagerState.animateScrollToPage(0)
            fileManagerMode = FileManagerMode.Browse
        }
    }

    fun choosePdf(path: String, title: String) {
        pendingPdfPath = path
        pendingPdfTitle = title
        pdfConversionMode = PdfEpubMode.Text
    }

    fun openExternalBook(file: ExternalBookFile) {
        if (openingExternalFileName != null) return
        openingExternalFileName = file.name
        scope.launch {
            try {
                val openedFile = withContext(Dispatchers.IO) {
                    val id = "external-" + UUID.nameUUIDFromBytes(file.uri.toString().toByteArray()).toString()
                    val extension = file.name.substringAfterLast('.', "book").lowercase(java.util.Locale.ROOT)
                    val local = File(context.cacheDir, "$id.$extension")
                    try {
                        (if (file.uri.scheme == "file") File(requireNotNull(file.uri.path)).inputStream()
                            else context.contentResolver.openInputStream(file.uri))?.use { input ->
                            local.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("Unable to read this file.")
                    } catch (failure: Exception) {
                        local.delete()
                        throw failure
                    }
                    BookEntity(
                        id = id,
                        title = file.name.substringBeforeLast('.', file.name),
                        fileName = file.name,
                        localPath = local.absolutePath,
                        format = file.format,
                        importedAt = System.currentTimeMillis(),
                    )
                }
                externalBook = openedFile
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                snackbar.showSnackbar(failure.message ?: "Could not open this book.")
            } finally {
                openingExternalFileName = null
            }
        }
    }

    fun leaveOpenedBook() {
        externalBook?.let { temporary ->
            File(temporary.localPath).delete()
            File(context.filesDir, "epub/${temporary.id}").deleteRecursively()
        }
        externalBook = null
        selectedId = null
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage(it)
        }
    }
    LaunchedEffect(folder) {
        val root = folder ?: return@LaunchedEffect
        try {
            val found = withContext(Dispatchers.IO) {
                val result = mutableListOf<ExternalBookFile>()
                val pending = ArrayDeque<File>().apply { add(root) }
                while (pending.isNotEmpty()) {
                    ensureActive()
                    val directory = pending.removeFirst()
                    val entries = directory.listFiles()
                    if (directory == root) checkNotNull(entries) { "Could not read this folder." }
                    for (entry in entries.orEmpty()) {
                        ensureActive()
                        if (java.nio.file.Files.isSymbolicLink(entry.toPath())) continue
                        if (entry.isDirectory) {
                            pending.add(entry)
                        } else if (entry.isFile && BookRepository.formatOf(entry.name) != null) {
                            result += ExternalBookFile(Uri.fromFile(entry), entry.name)
                        }
                    }
                }
                result.sortedBy { it.name.lowercase() }
            }
            checked = emptySet()
            candidates = found
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            scope.launch { snackbar.showSnackbar("Could not read this folder") }
        } finally {
            if (folder == root) folder = null
        }
    }

    val opened = externalBook ?: books.find { it.id == selectedId }
    if (opened != null && keepScreenOn) {
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(view) {
            val previous = view.keepScreenOn
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = previous }
        }
    }

    if (opened != null && externalBook == null) TrackReadingTime(opened.id)
    if (opened != null && opened.format in setOf("PDF", "CBZ", "CBR")) {
        BackHandler { leaveOpenedBook() }
        ImagePageReaderScreen(opened, onBack = { leaveOpenedBook() },
            onProgress = { if (externalBook == null) viewModel.updateProgress(opened.id, it) })
        return
    }
    if (opened != null && opened.format in setOf("EPUB", "TXT", "MOBI", "AZW3")) {
        BackHandler { leaveOpenedBook() }
        Box(Modifier.fillMaxSize()) {
            EpubReaderScreen(
                book = opened,
                onBack = { leaveOpenedBook() },
                libraryBooks = books,
                onProgress = { progress ->
                    if (externalBook == null) viewModel.updateProgress(opened.id, progress)
                }
            )
        }
        return
    }

    val libraryView = androidx.compose.ui.platform.LocalView.current
    val libraryDark = androidx.compose.foundation.isSystemInDarkTheme()
    SideEffect {
        val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
        activity?.let {
            androidx.core.view.WindowCompat.getInsetsController(it.window, libraryView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = !libraryDark
            }
        }
    }
    val latest = books.firstOrNull { it.lastOpenedAt != null }?.id
    val filtered = books.filter { book ->
        (activeLibraryId == null || book.libraryId == activeLibraryId) &&
            (when (filter) {
                "Reading" -> book.progress > 0f && book.progress < .999f
                "Finished" -> book.progress >= .999f
                "Unread" -> book.progress == 0f
                else -> true
            }) &&
            (book.title.contains(search, ignoreCase = true) || book.author.contains(search, ignoreCase = true))
    }.let { matches ->
        when (sortOrder) {
            "Title" -> matches.sortedBy { it.title.lowercase(java.util.Locale.ROOT) }
            "Author" -> matches.sortedWith(compareBy<BookEntity> { it.author.lowercase(java.util.Locale.ROOT) }.thenBy { it.title })
            "Progress" -> matches.sortedByDescending { it.progress }
            "Imported" -> matches.sortedByDescending { it.importedAt }
            else -> matches
        }
    }
    val activeLibrary = libraries.find { it.id == activeLibraryId }
    val title = activeLibrary?.name ?: "All books"
    BackHandler(selectedBooks.isNotEmpty()) { selectedBooks = emptySet() }
    BackHandler(pagerState.currentPage == 1 || fileManagerMode != FileManagerMode.Browse) { returnToLibrary() }
    BackHandler(showWebNovelJobs) { showWebNovelJobs = false }
    Box(Modifier.fillMaxSize()) {
    HorizontalPager(
        state = pagerState,
        userScrollEnabled = !showWebNovelJobs && !drawer.isOpen && fileManagerMode == FileManagerMode.Browse,
        beyondViewportPageCount = 1,
        modifier = Modifier.fillMaxSize().pointerInput(drawer.isOpen, pagerState, fileManagerMode, showWebNovelJobs) {
            val touchSlop = viewConfiguration.touchSlop
            val menuSwipeDistance = 96.dp.toPx()
            val horizontalRatio = 1.5f
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (showWebNovelJobs || drawer.isOpen || fileManagerMode != FileManagerMode.Browse || pagerState.currentPage != 0 ||
                    pagerState.canScrollBackward || pagerState.isScrollInProgress
                ) return@awaitEachGesture

                var directionDecided = false
                var maxVerticalDistance = 0f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.id != down.id && it.pressed }) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val distanceX = change.position.x - down.position.x
                    val distanceY = abs(change.position.y - down.position.y)
                    maxVerticalDistance = maxOf(maxVerticalDistance, distanceY)

                    if (!directionDecided && maxOf(abs(distanceX), distanceY) >= touchSlop) {
                        if (distanceX < touchSlop || distanceX <= distanceY * horizontalRatio) break
                        directionDecided = true
                    }

                    if (!change.pressed) {
                        if (directionDecided && distanceX >= menuSwipeDistance &&
                            distanceX > maxVerticalDistance * horizontalRatio &&
                            !drawer.isOpen && pagerState.currentPage == 0 &&
                            !pagerState.canScrollBackward && pagerState.targetPage == 0
                        ) {
                            scope.launch { drawer.open() }
                        }
                        break
                    }
                    // Claim only right swipes before the pager sees this movement.
                    if (directionDecided && change.position != change.previousPosition) change.consume()
                }
            }
        },
    ) { screen ->
      if (screen == 1) {
        FileManagerScreen(
            mode = fileManagerMode,
            onClose = ::returnToLibrary,
            onOpenFile = { file ->
                if (fileManagerMode == FileManagerMode.ConvertPdf) {
                    file.uri.path?.let { path -> choosePdf(path, file.name.substringBeforeLast('.')) }
                    returnToLibrary()
                } else openExternalBook(file)
            },
            onImportFiles = { uris ->
                viewModel.importBooks(uris, activeLibraryId ?: "default")
                returnToLibrary()
            },
            onScanFolder = { selectedFolder ->
                if (folder == null) folder = selectedFolder
                returnToLibrary()
            },
        )
      } else if (showWebNovelJobs) {
        WebNovelJobsScreen(
            jobs = webNovelJobs,
            viewModel = viewModel,
            onBack = { showWebNovelJobs = false },
        )
      } else {
       ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(Modifier.widthIn(max = 320.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Vibe Reader", Modifier.padding(24.dp), fontFamily = FontFamily.Serif,
                    style = MaterialTheme.typography.headlineMedium)
                NavigationDrawerItem(label = { Text("All books") }, selected = activeLibraryId == null,
                    icon = { Icon(Icons.Rounded.LibraryBooks, null) }, badge = { Text("${books.size}") },
                    onClick = { activeLibraryId = null; selectedBooks = emptySet(); scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp))
                Text("Libraries", Modifier.padding(start = 28.dp, top = 24.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelLarge)
                libraries.forEach { library ->
                        NavigationDrawerItem(label = { Text(library.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            selected = activeLibraryId == library.id,
                            icon = { Icon(Icons.Rounded.MenuBook, null) },
                            badge = { Text("${books.count { it.libraryId == library.id }}") },
                            onClick = { activeLibraryId = library.id; selectedBooks = emptySet(); scope.launch { drawer.close() } },
                            modifier = Modifier.padding(horizontal = 12.dp))
                }
                NavigationDrawerItem(label = { Text("New library") }, selected = false,
                    icon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                    onClick = { libraryName = ""; libraryError = null; editingLibraryId = null; showCreateLibrary = true; scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp))
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                NavigationDrawerItem(label = { Text("Import books") }, selected = false,
                    icon = { Icon(Icons.Rounded.Add, null) }, onClick = { showFileManager(FileManagerMode.Import) },
                    modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Scan folder") }, selected = false,
                    icon = { Icon(Icons.Rounded.FolderOpen, null) },
                    onClick = { if (folder == null) showFileManager(FileManagerMode.Scan) },
                    modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Browse device files") }, selected = false,
                    icon = { Icon(Icons.Rounded.FolderOpen, null) }, onClick = {
                        showFileManager(FileManagerMode.Browse)
                    }, modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Create EPUB from website") }, selected = false,
                    icon = { Icon(Icons.Rounded.Link, null) }, onClick = { beginManualWebsiteConversion(); scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Website conversions") }, selected = false,
                    icon = { Icon(Icons.Rounded.Link, null) }, badge = { Text("${webNovelJobs.count { it.status !in setOf(WebNovelJobState.COMPLETED, WebNovelJobState.CANCELLED) }}") },
                    onClick = { showWebNovelJobs = true; scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Convert PDF to EPUB") }, selected = false,
                    icon = { Icon(Icons.Rounded.PictureAsPdf, null) },
                    onClick = { if (conversionProgress == null) showFileManager(FileManagerMode.ConvertPdf) },
                    modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Discover") }, selected = false,
                    icon = { Icon(Icons.Rounded.Search, null) },
                    onClick = { showBookDiscovery = true; scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp))
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                NavigationDrawerItem(label = { Text("Reading statistics") }, selected = false,
                    icon = { Icon(Icons.Rounded.BarChart, null) }, onClick = { showStatistics = true; scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("App settings") }, selected = false,
                    icon = { Icon(Icons.Rounded.Accessibility, null) }, onClick = { showAccessibility = true; scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Groq AI key") }, selected = false,
                    icon = { Icon(Icons.Rounded.Key, null) }, onClick = { showGroqApiKeyScreen = true; scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Export backup") }, selected = false,
                    icon = { Icon(Icons.Rounded.Backup, null) }, onClick = {
                        if (!libraryBusy) showBackupExportOptions = true
                        scope.launch { drawer.close() }
                    }, modifier = Modifier.padding(horizontal = 12.dp))
                NavigationDrawerItem(label = { Text("Restore backup") }, selected = false,
                    icon = { Icon(Icons.Rounded.Restore, null) }, onClick = {
                        if (!libraryBusy) restoreBackup.launch(arrayOf("application/zip", "application/octet-stream"))
                        scope.launch { drawer.close() }
                    }, modifier = Modifier.padding(horizontal = 12.dp))
                Spacer(Modifier.height(16.dp))
                }
            }
        }
    ) {
      Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    if (selectedBooks.isNotEmpty()) {
                        val selectedPdf = selectedBooks.singleOrNull()?.let { id ->
                            books.find { it.id == id && it.format == "PDF" }
                        }
                        if (selectedPdf != null) {
                            IconButton(enabled = conversionProgress == null,
                                onClick = { choosePdf(selectedPdf.localPath, selectedPdf.title) }) {
                                Icon(Icons.Rounded.PictureAsPdf, "Convert PDF to EPUB")
                            }
                        }
                        Text("${selectedBooks.size} selected", maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Serif)
                    } else {
                        Box {
                            Row(
                                Modifier.clickable { showLibraryMenu = true }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Serif)
                                Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Choose library")
                            }
                            DropdownMenu(expanded = showLibraryMenu, onDismissRequest = { showLibraryMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("All books") },
                                    leadingIcon = { Icon(Icons.Rounded.LibraryBooks, null) },
                                    trailingIcon = { if (activeLibraryId == null) Icon(Icons.Rounded.CheckCircle, "Current library") },
                                    onClick = { activeLibraryId = null; selectedBooks = emptySet(); showLibraryMenu = false }
                                )
                                libraries.forEach { library ->
                                    DropdownMenuItem(
                                        text = { Text(library.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingIcon = { Icon(Icons.Rounded.MenuBook, null) },
                                        trailingIcon = { if (activeLibraryId == library.id) Icon(Icons.Rounded.CheckCircle, "Current library") },
                                        onClick = { activeLibraryId = library.id; selectedBooks = emptySet(); showLibraryMenu = false }
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (selectedBooks.isEmpty()) scope.launch { drawer.open() } else selectedBooks = emptySet() }) {
                        Icon(if (selectedBooks.isEmpty()) Icons.Rounded.Menu else Icons.Rounded.Close,
                            if (selectedBooks.isEmpty()) "Open library menu" else "Clear selection")
                    }
                },
                actions = {
                    if (selectedBooks.isNotEmpty()) {
                        selectedBooks.singleOrNull()?.let { id -> books.firstOrNull { it.id == id } }?.let { selected ->
                            Box {
                                IconButton(onClick = { showSelectedBookActions = true }) {
                                    Icon(Icons.Rounded.MoreVert, "Selected book actions")
                                }
                                DropdownMenu(expanded = showSelectedBookActions, onDismissRequest = { showSelectedBookActions = false }) {
                                    DropdownMenuItem(text = { Text("Details") }, leadingIcon = { Icon(Icons.Rounded.Info, null) },
                                        onClick = { showSelectedBookActions = false; bookDetailsId = selected.id })
                                    DropdownMenuItem(text = { Text("Export") }, leadingIcon = { Icon(Icons.Rounded.FileDownload, null) },
                                        onClick = { showSelectedBookActions = false; startBookExport(selected) })
                                    DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Rounded.Share, null) },
                                        onClick = { showSelectedBookActions = false; shareBook(selected) })
                                }
                            }
                        }
                        IconButton(onClick = { showMoveBooks = true }) { Icon(Icons.Rounded.DriveFileMove, "Move selected books") }
                        IconButton(onClick = { showDeleteBooks = true }) { Icon(Icons.Rounded.Delete, "Delete selected books") }
                        IconButton(onClick = { selectedBooks = filtered.map { it.id }.toSet() }) { Icon(Icons.Rounded.SelectAll, "Select all visible books") }
                    } else {
                        IconButton(onClick = { searchVisible = !searchVisible; if (!searchVisible) search = "" }) {
                            Icon(if (searchVisible) Icons.Rounded.Close else Icons.Rounded.Search, "Search books")
                        }
                        Box {
                        IconButton(onClick = { showMoreActions = true }) { Icon(Icons.Rounded.MoreVert, "More library actions") }
                        DropdownMenu(expanded = showMoreActions, onDismissRequest = { showMoreActions = false }) {
                            DropdownMenuItem(
                                text = { Text("Create library") },
                                leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                                onClick = { showMoreActions = false; libraryName = ""; libraryError = null; editingLibraryId = null; showCreateLibrary = true }
                            )
                            if (activeLibrary != null) {
                                DropdownMenuItem(text = { Text("Rename library") },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                    onClick = { showMoreActions = false; editingLibraryId = activeLibrary.id; libraryName = activeLibrary.name; libraryError = null; showCreateLibrary = true })
                                if (activeLibrary.id != "default") DropdownMenuItem(text = { Text("Delete library") },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                    onClick = { showMoreActions = false; deletingLibraryId = activeLibrary.id })
                            }
                            DropdownMenuItem(text = { Text("Select books") },
                                leadingIcon = { Icon(Icons.Rounded.SelectAll, null) }, enabled = filtered.isNotEmpty(),
                                onClick = { showMoreActions = false; selectedBooks = filtered.map { it.id }.toSet() })
                            DropdownMenuItem(
                                text = { Text("Create EPUB from website") },
                                leadingIcon = { Icon(Icons.Rounded.Link, null) },
                                onClick = { showMoreActions = false; beginManualWebsiteConversion() }
                            )
                            DropdownMenuItem(
                                text = { Text("Scan folder") },
                                leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) },
                                onClick = { showMoreActions = false; showFileManager(FileManagerMode.Scan) },
                                enabled = folder == null
                            )
                            DropdownMenuItem(
                                text = { Text("Import books") },
                                leadingIcon = { Icon(Icons.Rounded.Add, null) },
                                onClick = { showMoreActions = false; showFileManager(FileManagerMode.Import) }
                            )
                        }
                    }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF302820),
                    navigationIconContentColor = Color(0xFFFFF4E3),
                    titleContentColor = Color(0xFFFFF4E3),
                    actionIconContentColor = Color(0xFFFFF4E3)
                )
            )
        },
        bottomBar = { Spacer(Modifier.navigationBarsPadding()) }
      ) { padding ->
          Column(Modifier.fillMaxSize().padding(padding)) {
            if (backupProgress != null) {
                val progress = requireNotNull(backupProgress)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(progress.stage.label, style = MaterialTheme.typography.bodySmall)
                            progress.total?.takeIf { it > 0L }?.let { total ->
                                val position = if (progress.unit == com.paperlight.reader.data.BackupProgressUnit.Bytes) {
                                    "${formatBackupBytes(progress.completed)} / ${formatBackupBytes(total)}"
                                } else "${progress.completed} / $total books"
                                Text(position, style = MaterialTheme.typography.labelSmall)
                            } ?: if (progress.completed > 0L && progress.unit == com.paperlight.reader.data.BackupProgressUnit.Bytes) {
                                Text("${formatBackupBytes(progress.completed)} processed", style = MaterialTheme.typography.labelSmall)
                            } else Unit
                        }
                        TextButton(onClick = viewModel::cancelBackup) { Text("Cancel") }
                    }
                    val total = progress.total?.takeIf { it > 0L }
                    if (total != null) LinearProgressIndicator(
                        progress = { (progress.completed.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    ) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            } else if (libraryBusy || folder != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            conversionProgress?.let { (completed, total) ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (total > 0) "Converting PDF · $completed / $total pages" else "Opening PDF…",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = viewModel::cancelConversion) { Text("Cancel") }
                    }
                    if (total > 0) LinearProgressIndicator(
                        progress = { (completed.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    ) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            downloadProgress?.let { progress ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(progress.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                if (progress.total > 0) Text("${progress.completed} / ${progress.total} chapters", style = MaterialTheme.typography.labelSmall)
                            }
                            if (progress.jobId != null && progress.status in setOf(WebNovelJobState.RUNNING, WebNovelJobState.WAITING_RATE_LIMIT, WebNovelJobState.QUEUED)) {
                                TextButton(onClick = { viewModel.pauseWebNovelJob(progress.jobId) }) { Text("Pause") }
                            }
                            IconButton(onClick = { progress.jobId?.let(viewModel::cancelWebNovelJob) ?: viewModel.cancelDownload() }) {
                                Icon(Icons.Rounded.Close, "Cancel EPUB download")
                            }
                        }
                        if (progress.total > 0) LinearProgressIndicator(progress = { progress.completed.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            if (searchVisible) OutlinedTextField(
                value = search, onValueChange = { search = it }, label = { Text("Title or author") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${filtered.size} book${if (filtered.size == 1) "" else "s"}", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    TextButton(onClick = { showSort = true }) { Icon(Icons.Rounded.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(sortOrder) }
                    DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                        listOf("Recent", "Title", "Author", "Progress", "Imported").forEach { order ->
                            DropdownMenuItem(text = { Text(order) }, onClick = {
                                sortOrder = order; showSort = false; libraryPreferences.edit().putString("sortOrder", order).apply()
                            })
                        }
                    }
                }
                IconButton(onClick = { listView = !listView; libraryPreferences.edit().putBoolean("listView", listView).apply() }) {
                    Icon(if (listView) Icons.Rounded.GridView else Icons.Rounded.ViewList, if (listView) "Show bookshelf" else "Show list")
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("All", "Reading", "Unread", "Finished")) { value ->
                    FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(value) })
                }
            }
            if (filtered.isEmpty() && listView) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.MenuBook, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text(if (books.isEmpty()) "Your next read starts here" else "No books here yet", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(if (search.isNotBlank()) "Try another title or author." else "Import books or move them here from another library.",
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { showFileManager(FileManagerMode.Import) }) { Icon(Icons.Rounded.Add, null); Text("Import books") }
                }
            } else {
                val toggle: (BookEntity) -> Unit = { book ->
                    selectedBooks = if (book.id in selectedBooks) selectedBooks - book.id else selectedBooks + book.id
                }
                if (listView) LazyColumn(Modifier.weight(1f)) {
                    items(filtered, key = { it.id }) { book ->
                        BookListRow(book, markRecent && book.id == latest, book.id in selectedBooks,
                            onClick = { if (selectedBooks.isEmpty()) selectedId = book.id else toggle(book) }, onLongClick = { toggle(book) })
                    }
                } else {
                    val rows = filtered.chunked(shelfColumns)
                    LazyColumn(Modifier.weight(1f).background(Color(0xFF61412C)), contentPadding = PaddingValues(top = 4.dp)) {
                        items(count = maxOf(4, rows.size), key = { index -> "shelf:$index" }) { index ->
                            ShelfRow(rows.getOrNull(index).orEmpty(), latest.takeIf { markRecent }, selectedBooks, shelfColumns,
                                onOpen = { selectedId = it.id }, onToggle = toggle)
                        }
                    }
                }
            }
          }
      }
       }
      }
    }

    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    if (showBookDiscovery) BookDiscoveryScreen(
        sources = discoverySources,
        viewModel = viewModel,
        onDismiss = { showBookDiscovery = false },
        onImportEpub = { file, title, author ->
            viewModel.importDownloadedEpub(file, title, author, activeLibraryId ?: "default")
        },
        onConvertWebsite = { source, url ->
            val savedOptions = DiscoverySourceConfigCodec.decode(source.configJson).scrapeOptions
            val sameSite = sameWebOrigin(source.baseUrl, url)
            converterSourceId = source.id
            converterSourceBaseUrl = source.baseUrl
            savingScrapeSettings = false
            scrapeSettingsStatus = null
            applyScrapeOptions(if (sameSite) savedOptions else WebNovelScrapeOptions())
            showBookDiscovery = false
            novelUrl = url
            sampleChapterUrl = ""
            firstChapter = "1"
            lastChapter = ""
            chapterPreview = null
            previewError = null
            aiScrapeError = null
            showScrapeOptions = true
            showCreateFromWeb = true
        },
        onRequestGroqKey = { showGroqApiKeyScreen = true },
    )
    if (pagerState.settledPage == 0) pendingPdfPath?.let { path ->
        AlertDialog(
            onDismissRequest = { pendingPdfPath = null },
            title = { Text("Convert PDF to EPUB") },
            text = {
                Column {
                    Text(pendingPdfTitle, style = MaterialTheme.typography.titleSmall)
                    PdfEpubMode.entries.forEach { mode ->
                        val label = if (mode == PdfEpubMode.Text) "Reflowable text + OCR" else "Page images"
                        val detail = if (mode == PdfEpubMode.Text) "Extracts selectable text and OCRs scanned pages. Complex layouts may still read out of order."
                            else "Preserves the page layout as images, without selectable text."
                        Row(
                            Modifier.fillMaxWidth().clickable { pdfConversionMode = mode }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = pdfConversionMode == mode, onClick = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(label)
                                Text(detail, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = conversionProgress == null, onClick = {
                    viewModel.convertPdf(File(path), pendingPdfTitle, pdfConversionMode, activeLibraryId ?: "default")
                    pendingPdfPath = null
                    selectedBooks = emptySet()
                }) { Text("Convert and add") }
            },
            dismissButton = { TextButton(onClick = { pendingPdfPath = null }) { Text("Cancel") } },
        )
    }
    if (showStatistics) ReadingStatisticsDialog(books) { showStatistics = false }
    if (showAccessibility) {
        AlertDialog(
            onDismissRequest = { showAccessibility = false },
            title = { Text("App settings") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Books per shelf", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (count in 2..4) FilterChip(selected = shelfColumns == count, onClick = {
                            shelfColumns = count
                            libraryPreferences.edit().putInt("shelfColumns", count).apply()
                        }, label = { Text(count.toString()) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Mark last read book", Modifier.weight(1f))
                        Switch(checked = markRecent, onCheckedChange = {
                            markRecent = it
                            libraryPreferences.edit().putBoolean("markRecent", it).apply()
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Keep screen on while reading", Modifier.weight(1f))
                        Switch(checked = keepScreenOn, onCheckedChange = {
                            keepScreenOn = it
                            libraryPreferences.edit().putBoolean("keepScreenOn", it).apply()
                        })
                    }
                    HorizontalDivider()
                    Text("Interface text size", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = uiTextScale,
                        onValueChange = onUiTextScaleChange,
                        valueRange = .85f..1.5f,
                        steps = 12
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Smaller")
                        Text("${(uiTextScale * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                        Text("Larger")
                    }
                    Text("Book text size", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = readingTextScale,
                        onValueChange = onReadingTextScaleChange,
                        valueRange = .7f..2.2f,
                        steps = 14
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Smaller")
                        Text("${(readingTextScale * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                        Text("Larger")
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Reduce motion", style = MaterialTheme.typography.titleSmall)
                            Text("Use instant page turns and shorter settling animations.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = reduceMotion, onCheckedChange = onReduceMotionChange)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("E-Ink display mode", style = MaterialTheme.typography.titleSmall)
                            Text("Use black and white reading pages and turn off page animations and textures.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = eInkMode, onCheckedChange = onEInkModeChange)
                    }
                    TextButton(onClick = { onUiTextScaleChange(1f); onReadingTextScaleChange(1f); onReduceMotionChange(false); onEInkModeChange(false) }) { Text("Reset accessibility settings") }
                }
            },
            confirmButton = { TextButton(onClick = { showAccessibility = false }) { Text("Done") } }
        )
    }
    if (showGroqApiKeyScreen) GroqApiKeyScreen(onClose = { showGroqApiKeyScreen = false })
    if (showBackupExportOptions) {
        AlertDialog(
            onDismissRequest = {
                showBackupExportOptions = false
                backupPassword = ""
                backupPasswordConfirmation = ""
                backupPasswordVisible = false
            },
            title = { Text("Export app backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Includes library books, reading data, annotations, supported app settings, saved note summaries, and custom fonts. Website conversion checkpoints and custom discovery sources are not included yet.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = backupIncludeApiKey, onCheckedChange = {
                            backupIncludeApiKey = it
                            if (!it) { backupPassword = ""; backupPasswordConfirmation = "" }
                        })
                        Text("Include Groq API key (password-encrypted)")
                    }
                    if (backupIncludeApiKey) OutlinedTextField(
                        value = backupPassword,
                        onValueChange = { backupPassword = it.take(256) },
                        label = { Text("Backup password (12+ characters)") },
                        singleLine = true,
                        visualTransformation = if (backupPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { backupPasswordVisible = !backupPasswordVisible }) {
                                Icon(if (backupPasswordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    if (backupPasswordVisible) "Hide password" else "Show password")
                            }
                        },
                    )
                    if (backupIncludeApiKey) OutlinedTextField(
                        value = backupPasswordConfirmation,
                        onValueChange = { backupPasswordConfirmation = it.take(256) },
                        label = { Text("Confirm backup password") },
                        singleLine = true,
                        isError = backupPasswordConfirmation.isNotEmpty() && backupPasswordConfirmation != backupPassword,
                        supportingText = {
                            if (backupPasswordConfirmation.isNotEmpty() && backupPasswordConfirmation != backupPassword)
                                Text("Passwords do not match.")
                        },
                        visualTransformation = if (backupPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !backupIncludeApiKey ||
                    (backupPassword.length >= 12 && backupPassword == backupPasswordConfirmation), onClick = {
                    showBackupExportOptions = false
                    exportBackup.launch(if (backupIncludeApiKey) "VibeReader-protected-backup.vrb" else "VibeReader-backup.zip")
                }) { Text("Choose destination") }
            },
            dismissButton = { TextButton(onClick = {
                showBackupExportOptions = false
                backupPassword = ""
                backupPasswordConfirmation = ""
                backupPasswordVisible = false
            }) { Text("Cancel") } },
        )
    }
    pendingRestore?.let { uri ->
        AlertDialog(onDismissRequest = { pendingRestore = null; restorePassword = ""; restorePasswordVisible = false }, title = { Text("Restore backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Books already present will be skipped. Missing settings and summaries are added. Choose below whether to replace existing settings or the saved Groq key. Enter a password only for an encrypted backup.")
                    OutlinedTextField(value = restorePassword, onValueChange = { restorePassword = it.take(256) },
                        label = { Text("Backup password (if protected)") }, singleLine = true,
                        visualTransformation = if (restorePasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { restorePasswordVisible = !restorePasswordVisible }) {
                                Icon(if (restorePasswordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    if (restorePasswordVisible) "Hide password" else "Show password")
                            }
                        })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = restoreReplaceSettings, onCheckedChange = { restoreReplaceSettings = it })
                        Text("Replace existing app settings")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = restoreReplaceGroqKey, onCheckedChange = { restoreReplaceGroqKey = it })
                        Text("Allow replacing the saved Groq API key")
                    }
                }
            },
            confirmButton = { TextButton(enabled = !libraryBusy, onClick = {
                pendingRestore = null
                viewModel.backup(
                    uri,
                    restore = true,
                    password = restorePassword.takeIf(String::isNotBlank),
                    replaceExistingSettings = restoreReplaceSettings,
                    replaceExistingGroqKey = restoreReplaceGroqKey,
                )
                restorePassword = ""
                restorePasswordVisible = false
            }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { pendingRestore = null; restorePassword = ""; restorePasswordVisible = false }) { Text("Cancel") } })
    }
    bookDetailsId?.let { id -> books.firstOrNull { it.id == id }?.let { book ->
        AlertDialog(
            onDismissRequest = { bookDetailsId = null },
            title = { Text(book.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(book.author.ifBlank { "Unknown author" })
                    Text("Format: ${book.format}")
                    Text("Filename: ${book.fileName}")
                    Text("Reading progress: ${(book.progress * 100).toInt()}%")
                }
            },
            confirmButton = {
                TextButton(onClick = { bookDetailsId = null; startBookExport(book) }) { Text("Export") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { bookDetailsId = null; shareBook(book) }) { Text("Share") }
                    TextButton(onClick = { bookDetailsId = null }) { Text("Close") }
                }
            },
        )
    } }
    opened?.let {
        AlertDialog(
            onDismissRequest = { selectedId = null },
            title = { Text(it.title) },
            text = { Text("Reading support for ${it.format} is still in development.") },
            confirmButton = { TextButton(onClick = { selectedId = null }) { Text("Close") } }
        )
    }
    if (showCreateLibrary) {
        AlertDialog(
            onDismissRequest = { if (!libraryBusy) showCreateLibrary = false },
            title = { Text(if (editingLibraryId == null) "New library" else "Rename library") },
            text = {
                OutlinedTextField(
                    isError = libraryError != null,
                    supportingText = { libraryError?.let { Text(it) } },
                    enabled = !libraryBusy,
                    value = libraryName,
                    onValueChange = { libraryName = it.take(60) },
                    label = { Text("Library name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(enabled = libraryName.isNotBlank() && !libraryBusy, onClick = {
                    viewModel.saveLibrary(editingLibraryId, libraryName) { id, failure ->
                        if (id != null) { activeLibraryId = id; filter = "All"; search = ""; showCreateLibrary = false }
                        libraryError = failure
                    }
                }) { Text(if (libraryBusy) "Saving…" else "Save") }
            },
            dismissButton = { TextButton(enabled = !libraryBusy, onClick = { showCreateLibrary = false }) { Text("Cancel") } }
        )
    }
    if (showMoveBooks) {
        AlertDialog(
            onDismissRequest = { showMoveBooks = false },
            title = { Text("Move ${selectedBooks.size} book${if (selectedBooks.size == 1) "" else "s"} to") },
            text = {
                if (libraries.isEmpty()) Text("Create a library first.")
                else androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(libraries, key = { it.id }) { library ->
                        TextButton(
                            onClick = {
                                viewModel.moveBooks(selectedBooks.toList(), library.id) { success ->
                                    if (success) {
                                        activeLibraryId = library.id; filter = "All"; search = ""
                                        selectedBooks = emptySet(); showMoveBooks = false
                                    }
                                }
                            },
                            enabled = !libraryBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(library.name, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { libraryName = ""; editingLibraryId = null; libraryError = null; showCreateLibrary = true }) { Text("New library") } },
            dismissButton = { TextButton(onClick = { showMoveBooks = false }) { Text("Cancel") } }
        )
    }
    deletingLibraryId?.let { id ->
        AlertDialog(onDismissRequest = { deletingLibraryId = null }, title = { Text("Delete library?") },
            text = { Text("Its books will move to My Library.") },
            confirmButton = { TextButton(enabled = !libraryBusy, onClick = {
                viewModel.deleteLibrary(id) { success -> if (success) { activeLibraryId = "default"; deletingLibraryId = null } }
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deletingLibraryId = null }) { Text("Cancel") } })
    }
    if (showDeleteBooks) {
        AlertDialog(
            onDismissRequest = { showDeleteBooks = false },
            title = { Text("Delete selected books?") },
            text = { Text("${selectedBooks.size} book${if (selectedBooks.size == 1) "" else "s"} and their reading notes will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeBooks(selectedBooks.toList())
                    selectedBooks = emptySet()
                    showDeleteBooks = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteBooks = false }) { Text("Cancel") } }
        )
    }
    if (showCreateFromWeb) {
        val first = firstChapter.toIntOrNull()
        val last = lastChapter.toIntOrNull()
        val validRange = first != null && first > 0 && (lastChapter.isBlank() || (last != null && last >= first))
        val delay = requestDelayText.toLongOrNull()
        val validDelay = delay != null && delay in 0L..10_000L
        val options = WebNovelScrapeOptions(
            firstChapter = first ?: 1,
            lastChapter = last,
            chapterLinkSelector = chapterLinkSelector,
            chapterContentSelector = chapterContentSelector,
            chapterTitleSelector = chapterTitleSelector,
            removeSelector = removeSelector,
            renderInBrowser = renderNovelInBrowser,
            requestDelayMillis = delay?.takeIf { validDelay } ?: 500L,
        )
        val canCheckSite = !previewBusy && !aiScrapeBusy && validDelay && novelUrl.startsWith("http", ignoreCase = true)
        val canSaveScrapeSettings = converterSourceId != null && converterSourceBaseUrl != null &&
            sameWebOrigin(converterSourceBaseUrl.orEmpty(), novelUrl) && validDelay

        fun saveScrapeSettings() {
            val sourceId = converterSourceId ?: return
            val sourceBaseUrl = converterSourceBaseUrl ?: return
            val pageUrl = novelUrl
            if (!sameWebOrigin(sourceBaseUrl, pageUrl) || !validDelay) return
            savingScrapeSettings = true
            scrapeSettingsStatus = null
            viewModel.saveDiscoveryScrapeOptions(
                sourceId, sourceBaseUrl, pageUrl, options.copy(firstChapter = 1, lastChapter = null)
            ) { saved ->
                if (converterSourceId == sourceId && converterSourceBaseUrl == sourceBaseUrl && novelUrl == pageUrl) {
                    savingScrapeSettings = false
                    scrapeSettingsStatus = if (saved) "Saved settings for this website." else "Could not save settings for this website."
                }
            }
        }
        AlertDialog(
            onDismissRequest = { if (!previewBusy && !aiScrapeBusy) showCreateFromWeb = false },
            title = { Text("Create EPUB from website") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(
                        value = novelUrl,
                        onValueChange = {
                            novelUrl = it
                            savingScrapeSettings = false
                            chapterPreview = null
                            previewError = null
                            scrapeSettingsStatus = null
                        },
                        enabled = !previewBusy && !aiScrapeBusy,
                        label = { Text("Table of contents URL") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = sampleChapterUrl,
                        onValueChange = {
                            sampleChapterUrl = it
                            chapterPreview = null
                            previewError = null
                        },
                        label = { Text("Sample chapter URL (optional)") },
                        singleLine = true,
                        enabled = !previewBusy && !aiScrapeBusy,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = renderNovelInBrowser,
                            onCheckedChange = {
                                renderNovelInBrowser = it
                                chapterPreview = null
                                previewError = null
                                scrapeSettingsStatus = null
                            },
                            enabled = !previewBusy && !aiScrapeBusy,
                        )
                        Text("Render pages in browser")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = firstChapter,
                            onValueChange = {
                                firstChapter = it.filter { char -> char in '0'..'9' }.take(9)
                                chapterPreview = null
                                previewError = null
                            },
                            label = { Text("First chapter") },
                            modifier = Modifier.weight(1f),
                            enabled = !previewBusy && !aiScrapeBusy,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        OutlinedTextField(
                            value = lastChapter,
                            onValueChange = {
                                lastChapter = it.filter { char -> char in '0'..'9' }.take(9)
                                chapterPreview = null
                                previewError = null
                            },
                            label = { Text("Last chapter") },
                            placeholder = { Text("To the end") },
                            modifier = Modifier.weight(1f),
                            enabled = !previewBusy && !aiScrapeBusy,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    Text(
                        if (validRange) "Positions in the table of contents. Leave the last chapter blank to export to the end."
                        else "Enter a positive first chapter and a last chapter at or after it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(enabled = canCheckSite && validRange, onClick = {
                        chapterPreview = null
                        previewError = null
                        previewBusy = true
                        scope.launch {
                            try {
                                chapterPreview = WebNovelEpubUtility(context).preview(novelUrl, sampleChapterUrl, options)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                previewError = failure.message ?: "Could not preview the chapter."
                            } finally {
                                previewBusy = false
                            }
                        }
                    }) { Text(if (previewBusy) "Loading preview…" else "Preview chapter") }
                    chapterPreview?.let {
                        Text("${it.chapterCount} chapters found", style = MaterialTheme.typography.labelMedium)
                        Text(it.chapterTitle, style = MaterialTheme.typography.titleSmall)
                        Text(it.excerpt, style = MaterialTheme.typography.bodySmall)
                    }
                    previewError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { showScrapeOptions = !showScrapeOptions }) {
                        Text(if (showScrapeOptions) "Hide site selectors" else "Advanced site selectors")
                    }
                    if (showScrapeOptions) {
                        OutlinedTextField(
                            value = chapterLinkSelector,
                            onValueChange = {
                                chapterLinkSelector = it
                                chapterPreview = null
                                previewError = null
                                scrapeSettingsStatus = null
                            },
                            enabled = !previewBusy && !aiScrapeBusy,
                            label = { Text("Chapter link CSS selector") },
                            supportingText = { Text("Default: a[href]") }
                        )
                        OutlinedTextField(
                            value = chapterContentSelector,
                            onValueChange = {
                                chapterContentSelector = it
                                chapterPreview = null
                                previewError = null
                                scrapeSettingsStatus = null
                            },
                            enabled = !previewBusy && !aiScrapeBusy,
                            label = { Text("Chapter content CSS selector") },
                            supportingText = { Text("Used when a site needs a custom selector") }
                        )
                        OutlinedTextField(
                            value = chapterTitleSelector,
                            onValueChange = {
                                chapterTitleSelector = it
                                chapterPreview = null
                                previewError = null
                                scrapeSettingsStatus = null
                            },
                            enabled = !previewBusy && !aiScrapeBusy,
                            label = { Text("Chapter title CSS selector") },
                            supportingText = { Text("Default: h1, h2") }
                        )
                        OutlinedTextField(
                            value = removeSelector,
                            onValueChange = {
                                removeSelector = it
                                chapterPreview = null
                                previewError = null
                                scrapeSettingsStatus = null
                            },
                            enabled = !previewBusy && !aiScrapeBusy,
                            label = { Text("Remove from chapter CSS selector") },
                            supportingText = { Text("Navigation, advertisements, comments, etc.") }
                        )
                        OutlinedTextField(
                            value = requestDelayText,
                            onValueChange = {
                                requestDelayText = it.filter { char -> char in '0'..'9' }.take(5)
                                chapterPreview = null
                                previewError = null
                                scrapeSettingsStatus = null
                            },
                            enabled = !previewBusy && !aiScrapeBusy,
                            label = { Text("Request delay (milliseconds)") },
                            supportingText = { Text("0 to 10,000 ms between chapters") },
                            isError = !validDelay,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        if (!validDelay) Text("Enter a delay from 0 to 10,000 milliseconds.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(
                            enabled = canCheckSite,
                            onClick = {
                                aiScrapeError = null
                                if (!GroqApiKeyStore(context).hasKey()) showGroqApiKeyScreen = true
                                else scope.launch {
                                    aiScrapeBusy = true
                                    try {
                                        val suggestion = withContext(Dispatchers.IO) {
                                            GroqAiClient(context).suggestScrapeConfiguration(novelUrl, sampleChapterUrl, options)
                                        }
                                        chapterPreview = null
                                        previewError = null
                                        chapterLinkSelector = suggestion.chapterLinkSelector
                                        chapterContentSelector = suggestion.chapterContentSelector
                                        chapterTitleSelector = suggestion.chapterTitleSelector
                                        removeSelector = suggestion.removeSelector
                                        scrapeSettingsStatus = null
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        aiScrapeError = failure.message ?: "Could not suggest scraper settings."
                                    } finally {
                                        aiScrapeBusy = false
                                    }
                                }
                            }
                        ) {
                            if (aiScrapeBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (aiScrapeBusy) "Analyzing site…" else "Suggest selectors with Groq")
                        }
                        Text("A sanitized sample from the table of contents and one chapter is sent to Groq. Review the selectors before downloading.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Groq suggestions remain editable. Preview the chapter before creating the EPUB.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        aiScrapeError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (converterSourceId != null && converterSourceBaseUrl != null &&
                        !sameWebOrigin(converterSourceBaseUrl.orEmpty(), novelUrl)) {
                        Text("This URL is on a different website. Saved settings for the selected source will not be changed.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    scrapeSettingsStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text("Chapter pages are downloaded one at a time. Some sites may block automated access or need custom selectors.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (canSaveScrapeSettings) {
                        TextButton(enabled = !savingScrapeSettings && !previewBusy && !aiScrapeBusy,
                            onClick = ::saveScrapeSettings) {
                            Text(if (savingScrapeSettings) "Saving…" else "Save for this website")
                        }
                    }
                    TextButton(enabled = canCheckSite && validRange, onClick = {
                        if (canSaveScrapeSettings) saveScrapeSettings()
                        viewModel.createEpubFromUrl(
                            novelUrl,
                            options,
                            activeLibraryId ?: "default"
                        )
                        showCreateFromWeb = false
                    }) { Text("Download and create") }
                }
            },
            dismissButton = { TextButton(enabled = !previewBusy && !aiScrapeBusy, onClick = { showCreateFromWeb = false }) { Text("Cancel") } }
        )
    }
    candidates?.let { files ->
        AlertDialog(
            onDismissRequest = { candidates = null },
            title = { Text("Choose books") },
            text = {
                if (files.isEmpty()) Text("No supported book files in this folder.")
                else androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(files.size) { index ->
                        val entry = files[index]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(entry.uri in checked, onCheckedChange = { selected ->
                                checked = if (selected) checked + entry.uri else checked - entry.uri
                            })
                            Text(entry.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = checked.isNotEmpty(), onClick = {
                    viewModel.importBooks(files.filter { it.uri in checked }.map { it.uri }, activeLibraryId ?: "default")
                    candidates = null
                }) { Text("Import selected") }
            },
            dismissButton = { TextButton(onClick = { candidates = null }) { Text("Close") } }
        )
    }
    if (backupRecoveryBlocked) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Library recovery required") },
            text = { Text("Library startup recovery did not finish. Retry before using the library; no library changes will be accepted yet.") },
            confirmButton = {
                TextButton(enabled = !libraryBusy, onClick = viewModel::retryBackupRecovery) {
                    Text(if (libraryBusy) "Recovering…" else "Retry recovery")
                }
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookTile(
    book: BookEntity,
    recent: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onToggleSelection: () -> Unit
) {
    val cover by key(book.id) {
        produceState<android.graphics.Bitmap?>(BookArtwork.peek(book.id), book.id) {
            if (value == null) value = withContext(Dispatchers.IO) { BookArtwork.load(book) }
        }
    }
    val covers = listOf(0xFF344B53, 0xFF526048, 0xFF794942, 0xFF695474, 0xFF4F586E)
    Column {
        val coverShape = RoundedCornerShape(4.dp)
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().aspectRatio(.74f)
                .shadow(12.dp, coverShape, clip = false)
                .clip(coverShape)
                .then(if (selected) Modifier.border(2.dp, Color(0xFFFFD36A), coverShape) else Modifier)
                .background(Color(covers[Math.floorMod(book.id.hashCode(), covers.size)]))
                .semantics { this.selected = selected }
                .combinedClickable(
                    role = Role.Button,
                    onClick = { if (selectionMode) onToggleSelection() else onOpen() },
                    onLongClick = onToggleSelection,
                    onLongClickLabel = "Select book"
                )
        ) {
            val artwork = cover
            if (artwork != null) {
                Image(artwork.asImageBitmap(), book.title, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
            } else Column(Modifier.align(Alignment.Center).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.MenuBook, null, tint = Color(0xFFFFEFCE))
                Text(book.title, color = Color.White, fontFamily = FontFamily.Serif,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            }
            if (recent) Icon(Icons.Rounded.Bookmark, "Last read", tint = Color(0xFFB5F0DB),
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp))
            if (selected) Icon(Icons.Rounded.CheckCircle, "Selected", tint = Color(0xFFFFD36A),
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp))
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xC918211E)).padding(horizontal = 7.dp, vertical = 5.dp)) {
                Text("${(book.progress * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelSmall)
                LinearProgressIndicator(progress = { book.progress }, color = Color(0xFFE4CD92),
                    trackColor = Color(0xFF55605B), modifier = Modifier.fillMaxWidth().height(2.dp))
            }
        }

    }
}

@Composable
private fun ShelfRow(
    books: List<BookEntity>, recentId: String?, selectedIds: Set<String>, columns: Int,
    onOpen: (BookEntity) -> Unit, onToggle: (BookEntity) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            Image(painterResource(com.paperlight.reader.R.drawable.library_shelf_row), null,
                Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
                repeat(columns) { index ->
                    Box(Modifier.weight(1f).aspectRatio(.74f)) {
                        books.getOrNull(index)?.let { book ->
                            BookTile(book, book.id == recentId, book.id in selectedIds, selectedIds.isNotEmpty(),
                                onOpen = { onOpen(book) }, onToggleSelection = { onToggle(book) })
                        }
                    }
                }
            }
        }

    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookListRow(book: BookEntity, recent: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cover by key(book.id) {
        produceState<android.graphics.Bitmap?>(BookArtwork.peek(book.id), book.id) {
            if (value == null) value = withContext(Dispatchers.IO) { BookArtwork.load(book) }
        }
    }
    ListItem(
        headlineContent = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(listOf(book.author, book.format, "${(book.progress * 100).toInt()}%").filter { it.isNotBlank() }.joinToString(" · ")) },
        leadingContent = {
            Box(Modifier.size(width = 48.dp, height = 68.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                cover?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    ?: Icon(Icons.Rounded.MenuBook, null)
            }
        },
        trailingContent = { if (selected) Icon(Icons.Rounded.CheckCircle, "Selected") else if (recent) Icon(Icons.Rounded.Bookmark, "Last read") },
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        modifier = Modifier.semantics { this.selected = selected }.combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
    )
    HorizontalDivider()
}

private fun formatBackupBytes(value: Long): String = when {
    value >= 1024L * 1024L -> "%.1f MB".format(value / (1024.0 * 1024.0))
    value >= 1024L -> "%.0f KB".format(value / 1024.0)
    else -> "$value B"
}

private fun bookMimeType(format: String): String = when (format.uppercase()) {
    "EPUB" -> "application/epub+zip"
    "PDF" -> "application/pdf"
    "TXT" -> "text/plain"
    "CBZ" -> "application/vnd.comicbook+zip"
    "CBR" -> "application/vnd.comicbook-rar"
    else -> "application/octet-stream"
}

private fun sameWebOrigin(firstUrl: String, secondUrl: String): Boolean {
    val first = Uri.parse(firstUrl)
    val second = Uri.parse(secondUrl)
    val firstScheme = first.scheme?.lowercase()
    val secondScheme = second.scheme?.lowercase()
    if (firstScheme !in setOf("http", "https") || secondScheme !in setOf("http", "https") || firstScheme != secondScheme ||
        first.userInfo != null || second.userInfo != null || first.host.isNullOrBlank() || second.host.isNullOrBlank()) return false
    fun port(uri: Uri, scheme: String): Int = uri.port.takeIf { it >= 0 } ?: if (scheme == "https") 443 else 80
    return first.host.equals(second.host, ignoreCase = true) && port(first, firstScheme!!) == port(second, secondScheme!!)
}
