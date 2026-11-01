package com.leaf.reader.domain.model

data class ReadingProgress(
    val bookId: String,
    val locatorJson: String,
    val progression: Double,
    val updatedAt: Long,
)

/** A fully observed, closed checkpoint. No running timestamp survives process death. */
data class ReadingSession(
    val id: String,
    val bookId: String,
    val startedAt: Long,
    val endedAt: Long,
    val activeDurationMs: Long,
)
