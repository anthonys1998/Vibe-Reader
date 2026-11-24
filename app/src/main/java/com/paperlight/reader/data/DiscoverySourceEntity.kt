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
    const val GUTENBERG = "project-gutenberg"
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
