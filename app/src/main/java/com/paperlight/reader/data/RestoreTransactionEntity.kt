package com.paperlight.reader.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "backup_restore_transactions")
data class RestoreTransactionEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
)
