package com.leaf.reader.domain.usecase

import com.leaf.reader.domain.model.Book
import com.leaf.reader.domain.model.BookSort
import com.leaf.reader.domain.model.ReadingStatus
import java.util.Locale
import javax.inject.Inject

class QueryLibraryUseCase @Inject constructor() {
    operator fun invoke(books: List<Book>, query: String, status: ReadingStatus?, sort: BookSort): List<Book> {
        val term = query.trim()
        val filtered = books.filter {
            (status == null || it.status == status) &&
                (term.isEmpty() || it.title.contains(term, true) || it.author.contains(term, true))
        }
        val comparator = when (sort) {
            BookSort.TITLE -> compareBy<Book> { it.title.lowercase(Locale.ROOT) }
            BookSort.AUTHOR -> compareBy<Book> { it.author.lowercase(Locale.ROOT) }
            BookSort.IMPORTED -> compareByDescending<Book> { it.importedAt }
            BookSort.LAST_OPENED -> compareByDescending<Book> { it.lastOpenedAt ?: Long.MIN_VALUE }
            BookSort.PROGRESS -> compareByDescending<Book> { it.progression }
        }.thenBy { it.id }
        return filtered.sortedWith(comparator)
    }
}
