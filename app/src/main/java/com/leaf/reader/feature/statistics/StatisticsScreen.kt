package com.leaf.reader.feature.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leaf.reader.core.ui.leafActivityViewModel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields

@Composable
fun StatisticsScreen(onBook: (String) -> Unit) {
    val model: StatisticsViewModel = leafActivityViewModel("statistics")
    val ui by model.state.collectAsStateWithLifecycle()
    StatisticsContent(ui, model::retry, model::previousMonth, model::nextMonth, model::selectDay, onBook)
}

@Composable
private fun StatisticsContent(
    state: StatisticsUiState,
    retry: () -> Unit,
    previousMonth: () -> Unit,
    nextMonth: () -> Unit,
    selectDay: (LocalDate?) -> Unit,
    onBook: (String) -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    when {
        state.loading -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            ActivityHeading()
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).semantics { contentDescription = "Loading reading activity" })
        }
        state.error != null -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ActivityHeading()
            Text(state.error, color = MaterialTheme.colorScheme.error)
            Button(onClick = retry) { Text("Retry") }
        }
        else -> LazyColumn(Modifier.fillMaxSize().testTag("activity-list"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { ActivityHeading() }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActivityMetric("Reading time", formatReadingDuration(state.activity.summary.totalActiveMs),
                        Modifier.weight(1f).testTag("activity-total"), "Total active reading time: ${preciseReadingDuration(state.activity.summary.totalActiveMs)}")
                    val streak = state.activity.summary.currentStreak
                    ActivityMetric("Current streak", "$streak ${if (streak == 1) "day" else "days"}", Modifier.weight(1f).testTag("activity-streak"))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActivityMetric("Books started", state.activity.booksStarted.toString(), Modifier.weight(1f).testTag("activity-started"))
                    ActivityMetric("Books completed", state.activity.booksCompleted.toString(), Modifier.weight(1f).testTag("activity-completed"))
                }
            }
            item {
                val today = state.activity.summary.dailyActiveMs[state.activity.today] ?: 0L
                val longest = state.activity.summary.longestStreak
                Text("Today · ${formatReadingDuration(today)}    Longest streak · $longest ${if (longest == 1) "day" else "days"}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                ReadingCalendar(state.month, state.activity.today, state.activity.summary.dailyActiveMs,
                    state.selectedDay, previousMonth, nextMonth, selectDay)
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Reading history", style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Serif, modifier = Modifier.weight(1f))
                    if (state.selectedDay != null) TextButton(onClick = { selectDay(null) }) { Text("All dates") }
                }
                state.selectedDay?.let { day ->
                    Text("${day.format(dateFormat)} · ${formatReadingDuration(state.activity.summary.dailyActiveMs[day] ?: 0L)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.visibleHistory.isEmpty()) item {
                Text(if (state.selectedDay != null) "No reading time recorded on this day."
                    else "Your reading history will appear here. Open a book and make a little time for yourself.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.visibleHistory, key = { "${it.day}:${it.bookId}" }) { item ->
                OutlinedCard(Modifier.fillMaxWidth().testTag("activity-history-${item.bookId}-${item.day}")
                    .clickable(enabled = item.available, role = Role.Button) { onBook(item.bookId) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.day.format(dateFormat), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (item.author.isNotBlank()) Text(item.author, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(formatReadingDuration(item.activeDurationMs), style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.testTag("activity-history-duration-${item.bookId}-${item.day}")
                                    .semantics { contentDescription = preciseReadingDuration(item.activeDurationMs) })
                        }
                    }
                }
            }
            item {
                Text("Only active time in the reader is recorded. Time while away or inactive does not count.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ActivityHeading() {
    Column(Modifier.testTag("activity-heading"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reading activity", style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Serif)
        Text("Small moments, lasting journeys.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActivityMetric(label: String, value: String, modifier: Modifier, description: String = "$label: $value") {
    OutlinedCard(modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        Column(Modifier.padding(16.dp).heightIn(min = 76.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif)
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReadingCalendar(
    month: YearMonth,
    today: LocalDate,
    daily: Map<LocalDate, Long>,
    selectedDay: LocalDate?,
    previousMonth: () -> Unit,
    nextMonth: () -> Unit,
    selectDay: (LocalDate?) -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    val firstWeekday = WeekFields.of(locale).firstDayOfWeek
    val offset = (month.atDay(1).dayOfWeek.value - firstWeekday.value + 7) % 7
    val monthFormat = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
    val fullDateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    Column(Modifier.testTag("activity-calendar"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reading calendar", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
        Text(month.format(monthFormat), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("activity-month"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = previousMonth, modifier = Modifier.weight(1f).semantics { contentDescription = "Previous month" }) { Text("Previous") }
            TextButton(onClick = nextMonth, enabled = month < YearMonth.from(today),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Next month" }) { Text("Next") }
        }
        // Keep seven accessible touch targets on narrow screens; the calendar can scroll
        // horizontally instead of shrinking adjacent dates below 48 dp.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val calendarWidth = maxOf(maxWidth, 348.dp)
            Column(Modifier.horizontalScroll(rememberScrollState()).width(calendarWidth)) {
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { index ->
                        val weekday = firstWeekday.plus(index.toLong())
                        Box(Modifier.weight(1f).heightIn(min = 24.dp), contentAlignment = Alignment.Center) {
                            Text(weekday.getDisplayName(TextStyle.NARROW, locale), style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.semantics { contentDescription = weekday.getDisplayName(TextStyle.FULL, locale) })
                        }
                    }
                }
                repeat(6) { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(7) { weekday ->
                            val dayNumber = week * 7 + weekday - offset + 1
                            if (dayNumber !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).heightIn(min = 52.dp))
                            else {
                                val day = month.atDay(dayNumber)
                                val duration = daily[day] ?: 0L
                                val selected = day == selectedDay
                                val active = duration > 0L
                                val future = day > today
                                val shape = RoundedCornerShape(12.dp)
                                val fill = when {
                                    selected -> MaterialTheme.colorScheme.primary
                                    active -> MaterialTheme.colorScheme.primary.copy(alpha = if (duration >= 30 * 60_000L) 0.22f else 0.1f)
                                    else -> Color.Transparent
                                }
                                val ink = when {
                                    selected -> MaterialTheme.colorScheme.onPrimary
                                    future -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                                Column(Modifier.weight(1f).heightIn(min = 52.dp).testTag("activity-day-$day")
                                    .background(fill, shape)
                                    .border(1.dp, if (day == today) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
                                    .clickable(enabled = !future, role = Role.Button) { selectDay(day) }
                                    .semantics(mergeDescendants = true) {
                                        this.selected = selected
                                        contentDescription = "${day.format(fullDateFormat)}, ${preciseReadingDuration(duration)} active reading"
                                    }.padding(vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(dayNumber.toString(), style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (active || day == today) FontWeight.Bold else FontWeight.Normal, color = ink)
                                    Box(Modifier.size(4.dp).background(if (active) ink else Color.Transparent, CircleShape))
                                }
                            }
                        }
                    }
                }
            }
        }
        Text("Tap a day to see its reading history.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Dates follow your device’s local time.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
