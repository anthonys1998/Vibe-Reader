package com.paperlight.reader.data

import androidx.room.Entity
import androidx.room.PrimaryKey

object DiscoverySourceKind {
    const val STANDARD_EBOOKS = "STANDARD_EBOOKS"
    const val GUTENBERG = "GUTENBERG"
    const val OPDS = "OPDS"
    const val HTML = "HTML"
}

object DiscoverySourceIds {
    const val STANDARD_EBOOKS = "standard-ebooks"
    const val NOVELPING = "novelping"
    /** Retained only to recognize and remove the former built-in during upgrades/restores. */
    const val GUTENBERG = "project-gutenberg"
    const val LEGACY_GUTENBERG_URL = "https://www.gutenberg.org/ebooks/search.opds/"
    val BUILT_INS = setOf(STANDARD_EBOOKS, NOVELPING)

    fun isRemovedGutenbergBuiltin(source: DiscoverySourceEntity): Boolean =
        source.id == GUTENBERG && source.kind == DiscoverySourceKind.GUTENBERG &&
            source.baseUrl.equals(LEGACY_GUTENBERG_URL, ignoreCase = true)
}

@Entity(tableName = "discovery_sources")
data class DiscoverySourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val configJson: String = "{}",
    val cacheKey: String? = null,
    val cacheJson: String? = null,
    val cacheUpdatedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
