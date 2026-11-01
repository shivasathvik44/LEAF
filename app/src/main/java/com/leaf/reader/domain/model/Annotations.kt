package com.leaf.reader.domain.model

data class Bookmark(
    val id: String,
    val bookId: String,
    val locatorJson: String,
    val label: String?,
    val createdAt: Long,
)

data class Highlight(
    val id: String,
    val bookId: String,
    val locatorJson: String,
    val selectedText: String,
    val color: Long,
    val createdAt: Long,
    val note: String? = null,
)
