package com.paperlight.reader.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object PdfConversionJobState {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val PAUSE_REQUESTED = "PAUSE_REQUESTED"
    const val PAUSED = "PAUSED"
    const val CANCEL_REQUESTED = "CANCEL_REQUESTED"
    const val CANCELLED = "CANCELLED"
    const val INTERRUPTED = "INTERRUPTED"
    const val FAILED = "FAILED"
    const val COMPLETED = "COMPLETED"
}

@Entity(
    tableName = "pdf_conversion_jobs",
    indices = [Index(value = ["createdAt"]), Index(value = ["status"]), Index(value = ["checkpointKey"])],
)
data class PdfConversionJobEntity(
    @PrimaryKey val id: String,
    val sourceName: String,
    val sourceSize: Long,
    val inputHash: String,
    val inputSnapshotPath: String,
    val mode: String,
    val optionsJson: String,
    val libraryId: String,
    val status: String,
    val completedPages: Int,
    val totalPages: Int,
    val stage: String,
    val error: String?,
    val warning: String?,
    val runnerToken: String?,
    val runnerType: String?,
    val checkpointKey: String,
    val resultBookId: String,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val autoRun: Boolean = false,
)
