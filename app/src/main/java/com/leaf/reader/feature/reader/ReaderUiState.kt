package com.leaf.reader.feature.reader

import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.data.readium.ReaderChapter
import com.leaf.reader.data.readium.ReaderDocument
import com.leaf.reader.data.readium.ReaderLocation
import com.leaf.reader.domain.model.Bookmark
import com.leaf.reader.domain.model.ReaderPreferences

data class ReaderUiState(
    val loading: Boolean = true,
    val title: String = "Reader",
    val format: PublicationFormat = PublicationFormat.EPUB,
    val document: ReaderDocument? = null,
    val location: ReaderLocation? = null,
    val chapters: List<ReaderChapter> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val preferences: ReaderPreferences = ReaderPreferences(),
    val controlsVisible: Boolean = true,
    val error: String? = null,
    val message: String? = null,
)
