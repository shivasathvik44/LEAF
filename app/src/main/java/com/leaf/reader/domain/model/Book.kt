package com.leaf.reader.domain.model

import com.leaf.reader.core.util.PublicationFormat

enum class ReadingStatus { UNREAD, READING, COMPLETED }

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val description: String?,
    val format: PublicationFormat,
    val storagePath: String,
    val sourceUri: String?,
    val coverPath: String?,
    val fileSize: Long,
    val contentHash: String,
    val importedAt: Long,
    val lastOpenedAt: Long? = null,
    val status: ReadingStatus = ReadingStatus.UNREAD,
    val progression: Double = 0.0,
)
