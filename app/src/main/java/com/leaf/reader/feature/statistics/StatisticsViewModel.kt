package com.leaf.reader.feature.statistics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leaf.reader.data.repository.BookRepository
import com.leaf.reader.data.repository.StatisticsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModel @Inject constructor(
    statistics: StatisticsRepository,
    books: BookRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val retryCount = MutableStateFlow(0)
    private val month = savedState.getStateFlow<String?>(MONTH, null)
    private val day = savedState.getStateFlow<String?>(DAY, null)
    private val clock = flow {
        while (currentCoroutineContext().isActive) { emit(Instant.now()); delay(60_000L) }
    }
    private val activity = retryCount.flatMapLatest {
        combine(statistics.sessions, books.books, clock) { sessions, library, now ->
            StatisticsUiState(loading = false,
                activity = ActivityProjection.build(library, sessions, ZoneId.systemDefault(), now))
        }.flowOn(Dispatchers.Default).catch { failure ->
            if (failure is CancellationException) throw failure
            emit(StatisticsUiState(loading = false, error = "Reading activity could not be loaded. Please retry."))
        }
    }
    // The database's complete checkpoint stream stops shortly after Activity is left.
    val state: StateFlow<StatisticsUiState> = combine(activity, month, day) { source, monthValue, dayValue ->
        val currentMonth = YearMonth.from(source.activity.today)
        val selectedMonth = (monthValue?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
            ?: currentMonth).coerceAtMost(currentMonth)
        val selectedDay = dayValue?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.takeIf { it <= source.activity.today && YearMonth.from(it) == selectedMonth }
        source.copy(month = selectedMonth, selectedDay = selectedDay,
            visibleHistory = source.activity.history.filter { selectedDay == null || it.day == selectedDay })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), StatisticsUiState())

    fun retry() { retryCount.value++ }
    fun previousMonth() { selectMonth(state.value.month.minusMonths(1)) }
    fun nextMonth() {
        val target = state.value.month.plusMonths(1)
        if (target <= YearMonth.from(state.value.activity.today)) selectMonth(target)
    }
    fun selectDay(value: LocalDate?) {
        if (value == null || (value <= state.value.activity.today && YearMonth.from(value) == state.value.month)) {
            savedState[DAY] = value?.toString()
        }
    }
    private fun selectMonth(value: YearMonth) {
        savedState[MONTH] = value.takeUnless { it == YearMonth.from(state.value.activity.today) }?.toString()
        savedState[DAY] = null
    }
    private companion object { const val MONTH = "statistics_month"; const val DAY = "statistics_day" }
}
