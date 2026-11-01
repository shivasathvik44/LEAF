package com.leaf.reader.domain.model

data class Shelf(
    val id: String,
    val name: String,
    val description: String,
    val createdAt: Long,
    val bookIds: List<String> = emptyList(),
) {
    val count: Int get() = bookIds.size
}
