package com.leaf.reader.feature.statistics

import com.leaf.reader.data.repository.BookRepository
import com.leaf.reader.data.repository.StatisticsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Debug-only access for real repository/UI integration tests; no exported component. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface StatisticsTestDependencies {
    fun books(): BookRepository
    fun statistics(): StatisticsRepository
}
