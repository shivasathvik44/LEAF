package com.leaf.reader.feature.statistics

import com.leaf.reader.core.reading.ReadingStatistics
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class ReadingHistoryItem(
    val bookId: String,
    val title: String,
    val author: String,
    val day: LocalDate,
    val activeDurationMs: Long,
    val firstReadAt: Long,
    val lastReadAt: Long,
    val available: Boolean,
)

data class ActivitySnapshot(
    val summary: ReadingStatistics = ReadingStatistics(0L, emptyMap(), 0, 0, 0),
    val booksStarted: Int = 0,
    val booksCompleted: Int = 0,
    val history: List<ReadingHistoryItem> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val zone: ZoneId = ZoneId.systemDefault(),
)

data class StatisticsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val activity: ActivitySnapshot = ActivitySnapshot(),
    val month: YearMonth = YearMonth.now(),
    val selectedDay: LocalDate? = null,
    val visibleHistory: List<ReadingHistoryItem> = emptyList(),
)

fun formatReadingDuration(milliseconds: Long): String {
    val minutes = milliseconds.coerceAtLeast(0L) / 60_000L
    return when {
        milliseconds <= 0L -> "0 min"
        minutes == 0L -> "<1 min"
        minutes < 60L -> "$minutes min"
        minutes % 60L == 0L -> "${minutes / 60} hr"
        else -> "${minutes / 60} hr ${minutes % 60} min"
    }
}

fun preciseReadingDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1_000L
    if (milliseconds > 0L && seconds == 0L) return "less than 1 second"
    val minutes = seconds / 60
    val remainder = seconds % 60
    return "$minutes ${if (minutes == 1L) "minute" else "minutes"} $remainder ${if (remainder == 1L) "second" else "seconds"}"
}
