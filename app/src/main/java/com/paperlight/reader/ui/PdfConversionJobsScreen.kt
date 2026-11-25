package com.paperlight.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paperlight.reader.data.PdfConversionJobEntity
import com.paperlight.reader.data.PdfConversionJobState
import com.paperlight.reader.data.PdfEpubMode

private val pdfActiveStatuses = setOf(PdfConversionJobState.QUEUED, PdfConversionJobState.RUNNING,
    PdfConversionJobState.PAUSE_REQUESTED, PdfConversionJobState.CANCEL_REQUESTED)
private val pdfRemovableStatuses = setOf(PdfConversionJobState.COMPLETED, PdfConversionJobState.CANCELLED,
    PdfConversionJobState.PAUSED, PdfConversionJobState.INTERRUPTED, PdfConversionJobState.FAILED)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfConversionJobsScreen(
    jobs: List<PdfConversionJobEntity>,
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    var detailsJobId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeJobId by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PDF conversions", fontFamily = FontFamily.Serif) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back to library") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF302820),
                    navigationIconContentColor = Color(0xFFFFF4E3),
                    titleContentColor = Color(0xFFFFF4E3),
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("${jobs.size} total", style = MaterialTheme.typography.labelLarge)
                Text("${jobs.count { it.status == PdfConversionJobState.QUEUED }} queued", style = MaterialTheme.typography.labelLarge)
                Text("${jobs.count { it.status in pdfActiveStatuses }} active", style = MaterialTheme.typography.labelLarge)
            }
            if (jobs.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.PictureAsPdf, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("No PDF conversions yet", style = MaterialTheme.typography.titleMedium)
                        Text("Choose Convert PDF to EPUB from the library menu, or convert a PDF book in your library.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(jobs, key = { it.id }) { job ->
                        PdfConversionJobCard(
                            job = job,
                            viewModel = viewModel,
                            onDetails = { detailsJobId = job.id },
                            onRemove = { removeJobId = job.id },
                            onOpenBook = onOpenBook,
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }

    jobs.firstOrNull { it.id == detailsJobId }?.let { job ->
        val completedResultId = job.resultBookId.takeIf {
            job.status == PdfConversionJobState.COMPLETED && it.isNotBlank()
        }
        AlertDialog(
            onDismissRequest = { detailsJobId = null },
            title = { Text(job.sourceName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PdfJobDetail("Status", displayPdfStatus(job.status))
                    PdfJobDetail("Mode", displayPdfMode(job.mode))
                    PdfJobDetail("Stage", job.stage.ifBlank { "Preparing" })
                    PdfJobDetail("Pages", pageDescription(job))
                    PdfJobDetail("Source size", formatPdfBytes(job.sourceSize))
                    PdfJobDetail("Added", formatPdfTime(job.createdAt))
                    PdfJobDetail("Updated", formatPdfTime(job.updatedAt))
                    job.error?.let {
                        Text("Error", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                        SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                    job.warning?.let {
                        Text("Reading order fallback", style = MaterialTheme.typography.labelLarge)
                        SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    completedResultId?.let { Text("Result is available in your library.", style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                completedResultId?.let { resultId ->
                    TextButton(onClick = { detailsJobId = null; onOpenBook(resultId) }) { Text("Open EPUB") }
                }
            },
            dismissButton = { TextButton(onClick = { detailsJobId = null }) { Text("Close") } },
        )
    }

    jobs.firstOrNull { it.id == removeJobId }?.let { job ->
        AlertDialog(
            onDismissRequest = { removeJobId = null },
            title = { Text("Remove PDF conversion?") },
            text = { Text("Remove ${job.sourceName} and its saved conversion data?") },
            confirmButton = {
                TextButton(onClick = { viewModel.removePdfConversionJob(job.id); removeJobId = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeJobId = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun PdfConversionJobCard(
    job: PdfConversionJobEntity,
    viewModel: LibraryViewModel,
    onDetails: () -> Unit,
    onRemove: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val status = job.status
    val canPause = status in setOf(PdfConversionJobState.QUEUED, PdfConversionJobState.RUNNING)
    val canCancel = status in setOf(PdfConversionJobState.QUEUED, PdfConversionJobState.RUNNING,
        PdfConversionJobState.PAUSED, PdfConversionJobState.FAILED, PdfConversionJobState.INTERRUPTED)
    val canResume = status in setOf(PdfConversionJobState.QUEUED, PdfConversionJobState.PAUSED, PdfConversionJobState.INTERRUPTED)
    val canRetry = status == PdfConversionJobState.FAILED
    val completedResultId = job.resultBookId.takeIf { status == PdfConversionJobState.COMPLETED && it.isNotBlank() }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(job.sourceName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(displayPdfStatus(status), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Text(displayPdfMode(job.mode), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${job.stage.ifBlank { "Preparing" }} · ${pageDescription(job)}",
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (job.totalPages > 0) {
                LinearProgressIndicator(
                    progress = { (job.completedPages.toFloat() / job.totalPages).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (status in pdfActiveStatuses) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            job.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            job.warning?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
            Column(horizontalAlignment = Alignment.End) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDetails) { Text("Details") }
                    if (canResume) TextButton(onClick = { viewModel.resumePdfConversionJob(job.id) }) {
                        Text(if (status == PdfConversionJobState.QUEUED) "Start" else "Resume")
                    }
                    if (canRetry) TextButton(onClick = { viewModel.retryPdfConversionJob(job.id) }) { Text("Retry") }
                    if (canPause) TextButton(onClick = { viewModel.pausePdfConversionJob(job.id) }) { Text("Pause") }
                    if (canCancel) TextButton(onClick = { viewModel.cancelPdfConversionJob(job.id) }) { Text("Cancel") }
                }
                if (completedResultId != null || status in pdfRemovableStatuses) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        completedResultId?.let { resultId -> TextButton(onClick = { onOpenBook(resultId) }) { Text("Open") } }
                        if (status in pdfRemovableStatuses) TextButton(onClick = onRemove) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfJobDetail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(0.35f), style = MaterialTheme.typography.labelMedium)
        Text(value, Modifier.weight(0.65f), style = MaterialTheme.typography.bodySmall)
    }
}

private fun pageDescription(job: PdfConversionJobEntity): String = if (job.totalPages > 0) {
    "${job.completedPages.coerceAtLeast(0)} / ${job.totalPages} pages"
} else {
    "${job.completedPages.coerceAtLeast(0)} pages processed · total unknown"
}

private fun displayPdfStatus(status: String): String = status.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

private fun displayPdfMode(mode: String): String = when (mode) {
    PdfEpubMode.Text.name, "REFLOW" -> "Reflow + OCR"
    PdfEpubMode.Images.name, "IMAGES" -> "Page images"
    else -> mode
}

private fun formatPdfBytes(value: Long): String = when {
    value >= 1024L * 1024L -> "%.1f MB".format(value / (1024.0 * 1024.0))
    value >= 1024L -> "%.0f KB".format(value / 1024.0)
    else -> "$value B"
}

private fun formatPdfTime(value: Long): String = if (value > 0L) {
    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(value))
} else "Unknown"
