package com.leaf.reader.domain.model

enum class ReaderTheme { LIGHT, DARK, SEPIA }
enum class AppTheme { SYSTEM, LIGHT, DARK }
enum class BookSort { TITLE, AUTHOR, IMPORTED, LAST_OPENED, PROGRESS }

data class ReaderPreferences(
    val theme: ReaderTheme = ReaderTheme.SEPIA,
    val fontSize: Double = 100.0,
    val fontFamily: String = "serif",
    val lineSpacing: Double = 1.5,
    val margins: Double = 1.0,
    val scroll: Boolean = false,
)

data class LeafPreferences(
    val appTheme: AppTheme = AppTheme.DARK,
    val accent: Long = 0xFF91B78FL,
    val reader: ReaderPreferences = ReaderPreferences(),
    val sort: BookSort = BookSort.IMPORTED,
    val listView: Boolean = false,
)
