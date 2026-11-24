package com.paperlight.reader.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.paperlight.reader.data.DiscoverySourceEntity
import com.paperlight.reader.data.DiscoverySourceIds
import com.paperlight.reader.data.DiscoverySourceKind
import com.paperlight.reader.discovery.DiscoveryBook
import com.paperlight.reader.discovery.DiscoveryCatalogCache
import com.paperlight.reader.discovery.DiscoveryCatalogClient
import com.paperlight.reader.discovery.DiscoveryLink
import com.paperlight.reader.discovery.DiscoveryPage
import com.paperlight.reader.discovery.DiscoverySourceConfig
import com.paperlight.reader.discovery.DiscoverySourceConfigCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookDiscoveryScreen(
    sources: List<DiscoverySourceEntity>,
    viewModel: LibraryViewModel,
    onDismiss: () -> Unit,
    onImportEpub: (File, String, String) -> Unit,
    onConvertWebsite: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var selectedSourceId by rememberSaveable { mutableStateOf(DiscoverySourceIds.STANDARD_EBOOKS) }
    var requestStateSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf(DiscoveryCatalogClient.MODE_LATEST) }
    var requestedQuery by rememberSaveable { mutableStateOf("") }
    var requestedMode by rememberSaveable { mutableStateOf(DiscoveryCatalogClient.MODE_LATEST) }
    var requestedPageUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var refreshRevision by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<DiscoveryPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sourceMenu by remember { mutableStateOf(false) }
    var manageSources by rememberSaveable { mutableStateOf(false) }
    var editorVisible by remember { mutableStateOf(false) }
    var editorId by remember { mutableStateOf("") }
    var editorCreatedAt by remember { mutableLongStateOf(0L) }
    var editorName by remember { mutableStateOf("") }
    var editorUrl by remember { mutableStateOf("") }
    var editorKind by remember { mutableStateOf(DiscoverySourceKind.HTML) }
    var editorEnabled by remember { mutableStateOf(true) }
    var editorConfig by remember { mutableStateOf(DiscoverySourceConfig()) }
    var editorPreviewTitles by remember { mutableStateOf<List<String>>(emptyList()) }
    var editorDetected by remember { mutableStateOf(false) }
    var detecting by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var sourceActionError by remember { mutableStateOf<String?>(null) }
    var deleteSource by remember { mutableStateOf<DiscoverySourceEntity?>(null) }
    var browseStack by remember { mutableStateOf<List<DiscoveryLink>>(emptyList()) }
    var requestGeneration by remember { mutableIntStateOf(0) }
    val enabledSources = sources.filter { it.enabled }
    val selectedSource = sources.firstOrNull { it.id == selectedSourceId && it.enabled }
        ?: enabledSources.firstOrNull { it.kind == DiscoverySourceKind.STANDARD_EBOOKS }
        ?: enabledSources.firstOrNull()
    val sourceIdentity = selectedSource?.let { listOf(it.id, it.kind, it.baseUrl, it.configJson).joinToString("\n") }
    val sourceChanged = sourceIdentity != requestStateSourceId
    val effectiveQuery = if (sourceChanged) "" else requestedQuery
    val effectiveMode = if (sourceChanged) {
        if (selectedSource?.kind == DiscoverySourceKind.STANDARD_EBOOKS) DiscoveryCatalogClient.MODE_LATEST
        else DiscoveryCatalogClient.MODE_BROWSE
    } else requestedMode
    val effectivePageUrl = if (sourceChanged) null else requestedPageUrl
    val config = selectedSource?.let { DiscoverySourceConfigCodec.decode(it.configJson) } ?: DiscoverySourceConfig()
    val localHtmlFilter = selectedSource?.kind == DiscoverySourceKind.HTML && config.searchUrlTemplate.isBlank()
    val supportsSearch = selectedSource != null && (
        selectedSource.kind == DiscoverySourceKind.STANDARD_EBOOKS ||
            selectedSource.kind == DiscoverySourceKind.GUTENBERG ||
            config.searchUrlTemplate.isNotBlank()
        )
    val visiblePage = if (localHtmlFilter && query.isNotBlank()) {
        page?.copy(books = page?.books.orEmpty().filter {
            it.title.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true)
        })
    } else page

    fun requestSearch() {
        requestedQuery = query.trim()
        requestedMode = mode
        requestedPageUrl = null
        browseStack = emptyList()
        refreshRevision++
    }

    fun beginEdit(source: DiscoverySourceEntity? = null, suggestedConfig: DiscoverySourceConfig? = null,
        previewTitles: List<String> = emptyList()) {
        editorId = source?.id ?: UUID.randomUUID().toString()
        editorCreatedAt = source?.createdAt ?: System.currentTimeMillis()
        editorName = source?.name.orEmpty()
        editorUrl = source?.baseUrl.orEmpty()
        editorKind = source?.kind ?: DiscoverySourceKind.HTML
        editorEnabled = source?.enabled ?: true
        editorConfig = suggestedConfig ?: source?.let { DiscoverySourceConfigCodec.decode(it.configJson) } ?: DiscoverySourceConfig()
        editorPreviewTitles = previewTitles.take(4)
        editorDetected = source != null
        editorError = null
        editorVisible = true
    }

    fun updateEditorConfig(update: (DiscoverySourceConfig) -> DiscoverySourceConfig) {
        editorConfig = update(editorConfig)
        editorPreviewTitles = emptyList()
    }

    fun previewEditorListing() {
        if (!editorDetected || editorKind != DiscoverySourceKind.HTML || detecting) return
        detecting = true
        editorError = null
        val previewSource = DiscoverySourceEntity(editorId, editorName.ifBlank { "Preview" }, editorKind, editorUrl.trim(),
            enabled = true, configJson = DiscoverySourceConfigCodec.encode(editorConfig),
            createdAt = editorCreatedAt, updatedAt = System.currentTimeMillis())
        scope.launch {
            try {
                val preview = DiscoveryCatalogClient.search(previewSource, "", DiscoveryCatalogClient.MODE_BROWSE,
                    renderedContext = if (editorConfig.renderJavaScript) context.applicationContext else null)
                editorPreviewTitles = preview.books.take(4).map { it.title }
                editorError = if (editorPreviewTitles.isEmpty()) "No book cards matched these selectors."
                    else "Previewed ${preview.books.size} books with the current settings."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                editorPreviewTitles = emptyList()
                editorError = failure.message ?: "Could not preview this listing."
            } finally {
                detecting = false
            }
        }
    }

    LaunchedEffect(sourceIdentity) {
        requestStateSourceId = sourceIdentity
        requestedQuery = ""
        requestedPageUrl = null
        browseStack = emptyList()
        query = ""
        requestedMode = if (selectedSource?.kind == DiscoverySourceKind.STANDARD_EBOOKS)
            DiscoveryCatalogClient.MODE_LATEST else DiscoveryCatalogClient.MODE_BROWSE
        mode = requestedMode
    }

    LaunchedEffect(selectedSource?.id, selectedSource?.kind, selectedSource?.baseUrl, selectedSource?.enabled,
        selectedSource?.configJson, effectiveQuery, effectiveMode, effectivePageUrl, refreshRevision) {
        val source = selectedSource
        val token = ++requestGeneration
        if (source == null) {
            page = null
            loading = false
            error = if (sources.isEmpty()) "No discovery sources yet. Add one from Sources."
                else "Enable a source to browse books."
            return@LaunchedEffect
        }
        val searchMode = if (source.kind == DiscoverySourceKind.STANDARD_EBOOKS || source.kind == DiscoverySourceKind.HTML) effectiveMode
            else DiscoveryCatalogClient.MODE_BROWSE
        val cacheKey = DiscoveryCatalogCache.key(source, effectiveQuery, searchMode, effectivePageUrl)
        val cached = source.cacheJson?.takeIf { source.cacheKey == cacheKey }?.let(DiscoveryCatalogCache::decode)
        page = cached
        error = if (cached != null) "Showing saved results while refreshing…" else null
        loading = true
        try {
            val fresh = DiscoveryCatalogClient.search(source, effectiveQuery, searchMode, effectivePageUrl,
                renderedContext = if (source.kind == DiscoverySourceKind.HTML && config.renderJavaScript)
                    context.applicationContext else null)
            if (requestGeneration != token) return@LaunchedEffect
            page = fresh
            error = if (fresh.books.isEmpty() && fresh.collections.isEmpty()) "No matching books or collections were found." else null
            DiscoveryCatalogCache.encode(fresh)?.let { json ->
                viewModel.cacheDiscoverySourceResults(source, cacheKey, json)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (requestGeneration == token) {
                error = if (cached != null) "Could not refresh; showing saved results. " + failure.message.orEmpty()
                else failure.message ?: "Could not reach this source."
            }
        } finally {
            if (requestGeneration == token) loading = false
        }
    }

    fun downloadBook(source: DiscoverySourceEntity?, book: DiscoveryBook) {
        if (source == null || downloadingId != null) return
        downloadingId = book.id
        error = null
        scope.launch {
            val safePrefix = book.id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(60)
            val temporary = File(context.cacheDir, "discovery-" + safePrefix + "-" + UUID.randomUUID() + ".epub")
            var transferred = false
            try {
                DiscoveryCatalogClient.downloadEpub(source, book, temporary,
                    if (source.kind == DiscoverySourceKind.HTML && DiscoverySourceConfigCodec.decode(source.configJson).renderJavaScript)
                        context.applicationContext else null)
                onImportEpub(temporary, book.title, book.author)
                transferred = true
                onDismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Could not download this EPUB."
            } finally {
                if (!transferred) temporary.delete()
                downloadingId = null
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (manageSources) IconButton(onClick = { manageSources = false }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back to Discover")
                    }
                    Text(if (manageSources) "Discovery sources" else "Discover books",
                        Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    if (!manageSources) TextButton(enabled = downloadingId == null, onClick = { manageSources = true }) {
                        Icon(Icons.Rounded.Settings, contentDescription = null)
                        Text("Sources")
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }


                if (manageSources) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Enable a source to browse. Custom OPDS and HTML sources can be edited or removed.",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { beginEdit() }, enabled = downloadingId == null) { Text("Add source") }
                    }
                    sourceActionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sources, key = { it.id }) { source ->
                            Card {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(source.name, style = MaterialTheme.typography.titleSmall)
                                            Text(sourceKindLabel(source.kind), style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(checked = source.enabled, enabled = downloadingId == null,
                                            onCheckedChange = { enabled ->
                                                if (!enabled && selectedSourceId == source.id) {
                                                    page = null
                                                    requestedPageUrl = null
                                                }
                                                viewModel.setDiscoverySourceEnabled(source.id, enabled) { saved ->
                                                    if (!saved) sourceActionError = "Could not update this source."
                                                    else sourceActionError = null
                                                }
                                            })
                                    }
                                    Text(source.baseUrl, style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                        TextButton(onClick = {
                                            selectedSourceId = source.id
                                            query = ""
                                            requestedQuery = ""
                                            requestedMode = if (source.kind == DiscoverySourceKind.STANDARD_EBOOKS)
                                                DiscoveryCatalogClient.MODE_LATEST else DiscoveryCatalogClient.MODE_BROWSE
                                            mode = requestedMode
                                            requestedPageUrl = null
                                            browseStack = emptyList()
                                            manageSources = false
                                            refreshRevision++
                                        }, enabled = source.enabled && downloadingId == null) { Text("Browse") }
                                        if (source.id !in setOf(DiscoverySourceIds.STANDARD_EBOOKS, DiscoverySourceIds.GUTENBERG)) {
                                            IconButton(enabled = downloadingId == null, onClick = { beginEdit(source) }) {
                                                Icon(Icons.Rounded.Edit, contentDescription = "Edit " + source.name)
                                            }
                                            IconButton(enabled = downloadingId == null, onClick = { deleteSource = source }) {
                                                Icon(Icons.Rounded.Delete, contentDescription = "Remove " + source.name)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(onClick = { sourceMenu = true }, enabled = enabledSources.isNotEmpty() && downloadingId == null,
                                modifier = Modifier.fillMaxWidth()) {
                                Text(selectedSource?.name ?: "No enabled sources", Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Choose source")
                            }
                            DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                                enabledSources.forEach { source ->
                                    DropdownMenuItem(text = { Text(source.name) }, enabled = downloadingId == null, onClick = {
                                        sourceMenu = false
                                        selectedSourceId = source.id
                                        query = ""
                                        requestedQuery = ""
                                        requestedPageUrl = null
                                        browseStack = emptyList()
                                        requestedMode = if (source.kind == DiscoverySourceKind.STANDARD_EBOOKS)
                                            DiscoveryCatalogClient.MODE_LATEST else DiscoveryCatalogClient.MODE_BROWSE
                                        mode = requestedMode
                                        refreshRevision++
                                    })
                                }
                            }
                        }
                        IconButton(onClick = { refreshRevision++ },
                            enabled = selectedSource != null && !loading && downloadingId == null) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh source")
                        }
                    }
                    selectedSource?.let { source ->
                        Text(source.baseUrl, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val modeOptions = when {
                            source.kind == DiscoverySourceKind.STANDARD_EBOOKS -> listOf(
                                DiscoveryCatalogClient.MODE_LATEST to "Latest",
                                DiscoveryCatalogClient.MODE_POPULAR to "Popular",
                                DiscoveryCatalogClient.MODE_BROWSE to "Browse")
                            source.kind == DiscoverySourceKind.HTML && query.isBlank() -> buildList {
                                add(DiscoveryCatalogClient.MODE_BROWSE to "Browse")
                                if (config.htmlLatestUrl.isNotBlank()) add(DiscoveryCatalogClient.MODE_LATEST to "Latest")
                                if (config.htmlPopularUrl.isNotBlank()) add(DiscoveryCatalogClient.MODE_POPULAR to "Popular")
                            }
                            else -> emptyList()
                        }
                        if (modeOptions.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                modeOptions.forEach { (value, label) ->
                                    FilterChip(selected = mode == value, enabled = downloadingId == null, onClick = {
                                        mode = value
                                        requestedMode = value
                                        if (source.kind == DiscoverySourceKind.HTML) {
                                            query = ""
                                            requestedQuery = ""
                                        } else requestedQuery = query.trim()
                                        requestedPageUrl = null
                                        browseStack = emptyList()
                                        refreshRevision++
                                    }, label = { Text(label) })
                                }
                            }
                        } else {
                            TextButton(enabled = downloadingId == null, onClick = {
                                query = ""
                                requestedQuery = ""
                                requestedMode = DiscoveryCatalogClient.MODE_BROWSE
                                requestedPageUrl = null
                                browseStack = emptyList()
                                refreshRevision++
                            }) { Text(if (browseStack.isEmpty()) "Browse" else "Back to source") }
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(240) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        label = { Text(when {
                            localHtmlFilter -> "Filter this page"
                            supportsSearch -> "Title or author"
                            else -> "Search not advertised"
                        }) },
                        enabled = (supportsSearch || localHtmlFilter) && downloadingId == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            if (supportsSearch) requestSearch() else query = query.trim()
                        }),
                        trailingIcon = {
                            IconButton(onClick = { if (supportsSearch) requestSearch() else query = query.trim() },
                                enabled = (supportsSearch || localHtmlFilter) && downloadingId == null) {
                                Icon(Icons.Rounded.Search,
                                    contentDescription = if (localHtmlFilter) "Filter results" else "Search")
                            }
                        },
                    )
                    if (selectedSource?.kind == DiscoverySourceKind.OPDS && !supportsSearch) {
                        Text("This catalog does not advertise search. Browse its collections or edit the source's search URL template.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (selectedSource?.kind == DiscoverySourceKind.HTML) {
                        Text("This listing uses saved CSS selectors. If results are missing, edit them or review the page in Website to EPUB; sign-in sites are unsupported.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        page?.detectedConfig?.let { suggestion ->
                            TextButton(enabled = downloadingId == null, onClick = {
                                beginEdit(selectedSource, suggestion, page?.books.orEmpty().take(4).map { it.title })
                            }) { Text("Review detected selectors") }
                        }
                    }
                    sourceActionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    error?.let { Text(it, color = if (page != null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall) }
                    val shown = visiblePage
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
                        shown?.collections?.let { collections ->
                            items(collections, key = { "collection:" + it.url }) { item ->
                                ListItem(
                                    headlineContent = { Text(item.title) },
                                    supportingContent = { Text("Browse collection") },
                                    trailingContent = { Text("Open") },
                                    modifier = Modifier.clickable(enabled = downloadingId == null) {
                                        browseStack = browseStack + item
                                        requestedPageUrl = item.url
                                        requestedQuery = ""
                                        refreshRevision++
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                        shown?.books?.let { books ->
                            items(books, key = { "book:" + it.id }) { book ->
                                ListItem(
                                    headlineContent = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(book.author.ifBlank { sourceHost(book.detailsUrl) },
                                        maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingContent = { DiscoveryCover(book.coverUrl, book.title) },
                                    trailingContent = {
                                        Column(horizontalAlignment = Alignment.End) {
                                            if (selectedSource?.kind == DiscoverySourceKind.HTML) {
                                                TextButton(enabled = downloadingId == null,
                                                    onClick = { onConvertWebsite(book.detailsUrl) }) { Text("Review") }
                                            }
                                            TextButton(enabled = downloadingId == null,
                                                onClick = { downloadBook(selectedSource, book) }) {
                                                Text(if (downloadingId == book.id) "Downloading…" else "Add")
                                            }
                                        }
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                    shown?.nextUrl?.let { next ->
                        TextButton(enabled = !loading && downloadingId == null, onClick = {
                            requestedPageUrl = next
                            refreshRevision++
                        }, modifier = Modifier.align(Alignment.End)) { Text("Next page") }
                    }
                }
            }
        }
    }


    if (editorVisible) {
        AlertDialog(
            onDismissRequest = { if (!detecting) editorVisible = false },
            title = { Text(if (editorDetected && editorId !in setOf(DiscoverySourceIds.STANDARD_EBOOKS, DiscoverySourceIds.GUTENBERG))
                "Edit source" else "Add source") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = editorName, enabled = !detecting, onValueChange = { editorName = it.take(120) },
                        label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(value = editorUrl, onValueChange = {
                        editorUrl = it.take(4096)
                        editorDetected = false
                        editorPreviewTitles = emptyList()
                    }, enabled = !detecting, label = { Text("Source URL") }, singleLine = true)
                    if (editorKind == DiscoverySourceKind.HTML) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = editorConfig.renderJavaScript, enabled = !detecting, onCheckedChange = { enabled ->
                                updateEditorConfig { it.copy(renderJavaScript = enabled) }
                            })
                            Text("Render this public listing with JavaScript when needed", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(enabled = !detecting && editorUrl.isNotBlank(), onClick = {
                        detecting = true
                        editorError = null
                        scope.launch {
                            try {
                                val detected = DiscoveryCatalogClient.detectSource(editorUrl, editorName,
                                    if (editorConfig.renderJavaScript) context.applicationContext else null,
                                    editorConfig.renderJavaScript)
                                if (detected.kind == DiscoverySourceKind.STANDARD_EBOOKS &&
                                    sources.any { it.id == DiscoverySourceIds.STANDARD_EBOOKS }) {
                                    editorError = "Standard Ebooks is already included as a built-in source."
                                } else {
                                    editorKind = detected.kind
                                    editorUrl = detected.baseUrl
                                    if (editorName.isBlank()) editorName = detected.suggestedName
                                    editorConfig = detected.config.copy(renderJavaScript =
                                        detected.kind == DiscoverySourceKind.HTML && editorConfig.renderJavaScript)
                                    editorPreviewTitles = detected.previewTitles
                                    editorDetected = true
                                    editorError = "Detected " + sourceKindLabel(detected.kind) + ". Review settings before saving."
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                editorError = failure.message ?: "Could not inspect this URL."
                            } finally {
                                detecting = false
                            }
                        }
                    }) { Text(if (detecting) "Checking…" else "Detect source") }
                    if (editorDetected && editorKind == DiscoverySourceKind.HTML) {
                        TextButton(enabled = !detecting, onClick = ::previewEditorListing) {
                            Text(if (detecting) "Previewing…" else "Preview listing")
                        }
                    }
                    if (editorPreviewTitles.isNotEmpty()) {
                        Text("Preview: " + editorPreviewTitles.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (editorDetected) {
                        OutlinedTextField(value = editorConfig.searchUrlTemplate,
                            onValueChange = { value -> updateEditorConfig { it.copy(searchUrlTemplate = value.take(4096)) } },
                            enabled = !detecting, label = { Text("Search URL template (optional)") }, singleLine = true,
                            supportingText = { Text("Use {searchTerms} where the encoded query belongs.") })
                        if (editorKind == DiscoverySourceKind.HTML) {
                            Text("Book list selectors", style = MaterialTheme.typography.titleSmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = editorConfig.excludeNonCatalogRegions == true, enabled = !detecting,
                                    onCheckedChange = { enabled ->
                                        updateEditorConfig { it.copy(excludeNonCatalogRegions = enabled) }
                                    })
                                Text("Ignore navigation, sidebar, and comment cards", style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedTextField(value = editorConfig.htmlLatestUrl,
                                onValueChange = { value -> updateEditorConfig { it.copy(htmlLatestUrl = value.take(4096)) } },
                                enabled = !detecting, label = { Text("Latest listing URL (optional)") }, singleLine = true)
                            OutlinedTextField(value = editorConfig.htmlPopularUrl,
                                onValueChange = { value -> updateEditorConfig { it.copy(htmlPopularUrl = value.take(4096)) } },
                                enabled = !detecting, label = { Text("Popular listing URL (optional)") }, singleLine = true)
                            OutlinedTextField(value = editorConfig.htmlBookSelector,
                                onValueChange = { value -> updateEditorConfig { it.copy(htmlBookSelector = value.take(1000)) } },
                                enabled = !detecting, label = { Text("Book card selector") })
                            OutlinedTextField(value = editorConfig.htmlTitleSelector,
                                onValueChange = { value -> updateEditorConfig { it.copy(htmlTitleSelector = value.take(1000)) } },
                                enabled = !detecting, label = { Text("Title selector") })
                            OutlinedTextField(value = editorConfig.htmlAuthorSelector,
                                onValueChange = { value -> updateEditorConfig { it.copy(htmlAuthorSelector = value.take(1000)) } },
                                enabled = !detecting, label = { Text("Author selector (optional)") })
                            OutlinedTextField(value = editorConfig.htmlDetailLinkSelector,
                                onValueChange = { value -> updateEditorConfig { it.copy(htmlDetailLinkSelector = value.take(1000)) } },
                                enabled = !detecting, label = { Text("Book details link selector") })
                            OutlinedTextField(value = editorConfig.htmlCoverSelector,
                                onValueChange = { value -> updateEditorConfig { it.copy(htmlCoverSelector = value.take(1000)) } },
                                enabled = !detecting, label = { Text("Cover image selector (optional)") })
                            OutlinedTextField(value = editorConfig.htmlEpubLinkSelector,
                                onValueChange = { value -> updateEditorConfig { it.copy(htmlEpubLinkSelector = value.take(1000)) } },
                                enabled = !detecting, label = { Text("Direct EPUB link selector (optional)") })
                            TextButton(onClick = {
                                editorVisible = false
                                onConvertWebsite(editorUrl)
                            }) { Text("Review this website in the EPUB converter") }
                        }
                    }
                    editorError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall) }
                    Text("Public HTTP(S) catalogs only. Sign-in and restricted catalogs are unsupported.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(enabled = editorDetected && editorName.isNotBlank() && !detecting, onClick = {
                    val now = System.currentTimeMillis()
                    val source = DiscoverySourceEntity(editorId, editorName.trim(), editorKind, editorUrl.trim(),
                        enabled = editorEnabled, configJson = DiscoverySourceConfigCodec.encode(editorConfig),
                        cacheKey = null, cacheJson = null, cacheUpdatedAt = null,
                        createdAt = editorCreatedAt, updatedAt = now)
                    viewModel.saveDiscoverySource(source) { saved ->
                        if (saved) {
                            if (source.enabled) {
                                selectedSourceId = source.id
                                query = ""
                                requestedQuery = ""
                                requestedMode = if (source.kind == DiscoverySourceKind.STANDARD_EBOOKS)
                                    DiscoveryCatalogClient.MODE_LATEST else DiscoveryCatalogClient.MODE_BROWSE
                                mode = requestedMode
                                requestedPageUrl = null
                            }
                            editorVisible = false
                            manageSources = !source.enabled
                            refreshRevision++
                        } else editorError = "Could not save this source. Review the URL and settings."
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(enabled = !detecting, onClick = { editorVisible = false }) { Text("Cancel") } },
        )
    }
    deleteSource?.let { source ->
        AlertDialog(
            onDismissRequest = { deleteSource = null },
            title = { Text("Remove " + source.name + "?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This removes the saved source and its cached results. It does not affect books already in your library.")
                    sourceActionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = downloadingId == null, onClick = {
                    if (selectedSourceId == source.id) {
                        page = null
                        requestedPageUrl = null
                    }
                    viewModel.deleteDiscoverySource(source.id) { deleted ->
                        if (deleted) deleteSource = null else sourceActionError = "Could not remove this source."
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deleteSource = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DiscoveryCover(url: String?, title: String) {
    val bitmap by produceState<Bitmap?>(null, url) {
        value = null
        if (!url.isNullOrBlank()) value = loadDiscoveryCover(url)
    }
    Box(Modifier.size(width = 42.dp, height = 62.dp).clip(RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.Book, contentDescription = null)
    }
}

private suspend fun loadDiscoveryCover(rawUrl: String): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val uri = Uri.parse(rawUrl)
        require((uri.scheme == "https" || uri.scheme == "http") && uri.userInfo == null && !uri.host.isNullOrBlank())
        val connection = URL(rawUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "VibeReader/1.1 (Android EPUB reader)")
            require(connection.responseCode in 200..299)
            val finalUri = Uri.parse(connection.url.toExternalForm())
            require((finalUri.scheme == "https" || finalUri.scheme == "http") && finalUri.userInfo == null)
            require(connection.contentLengthLong <= 1_000_000L)
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= 1_000_000)
                    bytes.write(buffer, 0, read)
                }
            }
            val encoded = bytes.toByteArray()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 256) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

private fun sourceHost(url: String): String = Uri.parse(url).host.orEmpty()

private fun sourceKindLabel(kind: String): String = when (kind) {
    DiscoverySourceKind.STANDARD_EBOOKS -> "Standard Ebooks"
    DiscoverySourceKind.GUTENBERG -> "Project Gutenberg OPDS"
    DiscoverySourceKind.OPDS -> "Custom OPDS"
    DiscoverySourceKind.HTML -> "Custom website"
    else -> "Unsupported source"
}
