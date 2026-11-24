package com.paperlight.reader.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import com.paperlight.reader.webnovel.WebNovelScrapeOptions
import org.json.JSONObject

object WebNovelJobState {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val WAITING_RATE_LIMIT = "WAITING_RATE_LIMIT"
    const val PAUSE_REQUESTED = "PAUSE_REQUESTED"
    const val PAUSED = "PAUSED"
    const val CANCEL_REQUESTED = "CANCEL_REQUESTED"
    const val CANCELLED = "CANCELLED"
    const val INTERRUPTED = "INTERRUPTED"
    const val FAILED = "FAILED"
    const val COMPLETED = "COMPLETED"
}

@Entity(tableName = "web_novel_jobs", indices = [Index(value = ["createdAt"])])
data class WebNovelJobEntity(
    @PrimaryKey val id: String,
    val sourceUrl: String,
    val optionsJson: String,
    val libraryId: String,
    val status: String,
    val completed: Int,
    val total: Int,
    val label: String,
    val error: String?,
    val waitUntil: Long?,
    val runnerToken: String?,
    val checkpointKey: String,
    val resultBookId: String,
    val bookTitle: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val runnerType: String? = null,
    @ColumnInfo(defaultValue = "0") val autoRun: Boolean = false,
)

object WebNovelScrapeOptionsJson {
    fun encode(options: WebNovelScrapeOptions): String = JSONObject()
        .put("chapterLinkSelector", options.chapterLinkSelector)
        .put("chapterContentSelector", options.chapterContentSelector)
        .put("chapterTitleSelector", options.chapterTitleSelector)
        .put("removeSelector", options.removeSelector)
        .put("firstChapter", options.firstChapter)
        .put("lastChapter", options.lastChapter ?: JSONObject.NULL)
        .put("requestDelayMillis", options.requestDelayMillis)
        .put("renderInBrowser", options.renderInBrowser)
        .toString()

    fun decode(serialized: String): WebNovelScrapeOptions {
        val value = JSONObject(serialized)
        val options = WebNovelScrapeOptions(
            chapterLinkSelector = value.optString("chapterLinkSelector", WebNovelScrapeOptions().chapterLinkSelector),
            chapterContentSelector = value.optString("chapterContentSelector", WebNovelScrapeOptions().chapterContentSelector),
            chapterTitleSelector = value.optString("chapterTitleSelector", WebNovelScrapeOptions().chapterTitleSelector),
            removeSelector = value.optString("removeSelector", WebNovelScrapeOptions().removeSelector),
            firstChapter = value.optInt("firstChapter", 1),
            lastChapter = if (value.isNull("lastChapter")) null else value.optInt("lastChapter"),
            requestDelayMillis = value.optLong("requestDelayMillis", 500L),
            renderInBrowser = value.optBoolean("renderInBrowser", false),
        )
        require(options.firstChapter > 0 && (options.lastChapter == null || options.lastChapter >= options.firstChapter)) {
            "Saved conversion chapter range is invalid."
        }
        require(options.requestDelayMillis in 0L..10_000L) { "Saved conversion request delay is invalid." }
        return options
    }
}
