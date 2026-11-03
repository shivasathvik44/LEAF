package com.leaf.reader.feature.library

import com.leaf.reader.domain.model.*

data class LibraryUiState(
    val loading: Boolean = true,
    val books: List<Book> = emptyList(),
    val visibleBooks: List<Book> = emptyList(),
    val query: String = "",
    val status: ReadingStatus? = null,
    val preferences: LeafPreferences = LeafPreferences(),
    val importing: Boolean = false,
    val importProgress: String? = null,
    val loadError: String? = null,
)

fun ReadingStatus.displayName(): String = when (this) {
    ReadingStatus.UNREAD -> "Unread"
    ReadingStatus.READING -> "Reading"
    ReadingStatus.COMPLETED -> "Completed"
}

fun BookSort.displayName(): String = when (this) {
    BookSort.TITLE -> "Title"
    BookSort.AUTHOR -> "Author"
    BookSort.IMPORTED -> "Date imported"
    BookSort.LAST_OPENED -> "Last opened"
    BookSort.PROGRESS -> "Reading progress"
}
