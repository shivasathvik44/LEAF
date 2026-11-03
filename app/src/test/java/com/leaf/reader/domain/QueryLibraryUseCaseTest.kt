package com.leaf.reader.domain

import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.domain.model.*
import com.leaf.reader.domain.usecase.QueryLibraryUseCase
import org.junit.Assert.assertEquals
import org.junit.Test

class QueryLibraryUseCaseTest {
    private val query = QueryLibraryUseCase()
    private fun book(id: String, title: String, author: String, imported: Long, opened: Long?, progress: Double, status: ReadingStatus = ReadingStatus.UNREAD) =
        Book(id, title, author, null, PublicationFormat.EPUB, "/private/$id.epub", null, null, 20, "a".repeat(64), imported, opened, status, progress)
    private val books = listOf(
        book("a", "The Fern", "Zara", 3, null, 0.0),
        book("b", "a quiet day", "Amal", 1, 8, 0.4, ReadingStatus.READING),
        book("c", "River", "Bela Fern", 2, 9, 1.0, ReadingStatus.COMPLETED),
    )
    @Test fun searchMatchesAuthorOrTitleWithoutCaseAndTrimsWhitespace() {
        assertEquals(listOf("a", "c"), query(books, " FERN ", null, BookSort.IMPORTED).map { it.id })
    }
    @Test fun textAndStatusAreAppliedTogether() {
        assertEquals(listOf("c"), query(books, "fern", ReadingStatus.COMPLETED, BookSort.TITLE).map { it.id })
        assertEquals(emptyList<Book>(), query(books, "fern", ReadingStatus.READING, BookSort.TITLE))
    }
    @Test fun everySortHasItsDocumentedOrder() {
        val expected = mapOf(BookSort.TITLE to listOf("b", "c", "a"), BookSort.AUTHOR to listOf("b", "c", "a"),
            BookSort.IMPORTED to listOf("a", "c", "b"), BookSort.LAST_OPENED to listOf("c", "b", "a"), BookSort.PROGRESS to listOf("c", "b", "a"))
        expected.forEach { (sort, ids) -> assertEquals(sort.name, ids, query(books, "", null, sort).map { it.id }) }
    }
    @Test fun equalSortValuesHaveStableIdOrderWithoutMutatingTheSource() {
        val original = listOf(books[0].copy(id = "z"), books[0].copy(id = "a"))
        assertEquals(listOf("a", "z"), query(original, "", null, BookSort.TITLE).map { it.id })
        assertEquals(listOf("z", "a"), original.map { it.id })
    }
}
