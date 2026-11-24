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
import androidx.compose.material.icons.rounded.Link
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
import com.paperlight.reader.data.WebNovelJobEntity
import com.paperlight.reader.data.WebNovelJobState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebNovelJobsScreen(
    jobs: List<WebNovelJobEntity>,
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
) {
    var resetManifestJobId by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Website conversions", fontFamily = FontFamily.Serif) },
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${jobs.size} total", style = MaterialTheme.typography.labelLarge)
                Text("${jobs.count { it.status == WebNovelJobState.QUEUED }} queued", style = MaterialTheme.typography.labelLarge)
                Text("${jobs.count { it.status == WebNovelJobState.RUNNING || it.status == WebNovelJobState.WAITING_RATE_LIMIT }} active",
                    style = MaterialTheme.typography.labelLarge)
            }
            if (jobs.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Link, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("No website conversions yet", style = MaterialTheme.typography.titleMedium)
                        Text("Create an EPUB from a website to see its progress here.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(jobs, key = { it.id }) { job ->
                        ConversionJobCard(
                            job = job,
                            viewModel = viewModel,
                            onResetManifest = { resetManifestJobId = job.id },
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }

    resetManifestJobId?.let { jobId ->
        AlertDialog(
            onDismissRequest = { resetManifestJobId = null },
            title = { Text("Read the chapter list again?") },
            text = {
                Text("The saved order will be reset and the current table of contents will be read. Saved chapter pages will be reused only when their URLs still match.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetInvalidWebNovelChapterManifest(jobId)
                    resetManifestJobId = null
                }) { Text("Reset and retry") }
            },
            dismissButton = { TextButton(onClick = { resetManifestJobId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConversionJobCard(
    job: WebNovelJobEntity,
    viewModel: LibraryViewModel,
    onResetManifest: () -> Unit,
) {
    val invalidManifest = viewModel.hasInvalidSavedChapterManifest(job)
    val resumable = viewModel.canResumeWebNovelJob(job)
    val canPause = job.status in setOf(
        WebNovelJobState.QUEUED,
        WebNovelJobState.RUNNING,
        WebNovelJobState.WAITING_RATE_LIMIT,
    )
    val canCancel = job.status !in setOf(
        WebNovelJobState.COMPLETED,
        WebNovelJobState.CANCELLED,
        WebNovelJobState.CANCEL_REQUESTED,
    )
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(job.bookTitle?.takeIf(String::isNotBlank) ?: "Website conversion",
                style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(job.sourceUrl, style = MaterialTheme.typography.bodySmall,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(job.status.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase),
                    Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                if (job.status == WebNovelJobState.WAITING_RATE_LIMIT) {
                    Text(waitDescription(job), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (job.total > 0) {
                val percent = (job.completed.toDouble() * 100.0 / job.total).toInt().coerceIn(0, 100)
                Text("${job.completed.coerceAtLeast(0)} / ${job.total} chapters · $percent%",
                    style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { (job.completed.toFloat() / job.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text("${job.completed.coerceAtLeast(0)} chapters completed · total unknown",
                    style = MaterialTheme.typography.bodySmall)
                if (job.status in setOf(WebNovelJobState.RUNNING, WebNovelJobState.WAITING_RATE_LIMIT)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            Text(job.label, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            job.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (invalidManifest) {
                        TextButton(onClick = onResetManifest) { Text("Reset chapter list") }
                    } else if (resumable) {
                        TextButton(onClick = { viewModel.resumeWebNovelJob(job.id) }) {
                            Text(when (job.status) {
                                WebNovelJobState.QUEUED -> "Start"
                                WebNovelJobState.WAITING_RATE_LIMIT -> "Retry"
                                else -> "Resume / retry"
                            })
                        }
                    }
                    if (canPause) TextButton(onClick = { viewModel.pauseWebNovelJob(job.id) }) { Text("Pause") }
                }
                if (canCancel) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { viewModel.cancelWebNovelJob(job.id) }) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

private fun waitDescription(job: WebNovelJobEntity): String = when {
    job.runnerToken != null -> "Waiting in service"
    job.waitUntil == null -> "Ready to retry"
    job.waitUntil <= System.currentTimeMillis() -> "Ready to retry"
    else -> "Retry after ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(job.waitUntil))}"
}
