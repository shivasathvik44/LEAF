package com.leaf.reader.data.readium

import android.icu.text.BreakIterator
import android.icu.text.Collator
import android.icu.text.RuleBasedCollator
import android.icu.text.SearchIterator
import android.icu.text.StringSearch
import java.text.StringCharacterIterator
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.SearchService
import org.readium.r2.shared.publication.services.search.StringSearchService
import org.readium.r2.shared.publication.services.search.searchServiceFactory

/** Safe failure instead of materializing millions of locators for an extremely broad query. */
class SearchMatchLimitException : IllegalArgumentException("Too many search matches in one chapter.")
class SearchContextLimitException : IllegalArgumentException("The search context contains an unbroken segment longer than 4096 characters.")

private const val SNIPPET_LENGTH = 200

@OptIn(ExperimentalReadiumApi::class)
internal class BoundedSearchAlgorithm : StringSearchService.Algorithm {
    override val options = SearchService.Options(caseSensitive = false, diacriticSensitive = false, wholeWord = false)
    override suspend fun findRanges(query: String, options: SearchService.Options, text: String, locale: Locale): List<IntRange> {
        if (query.isEmpty() || text.isEmpty()) return emptyList()
        val collator = (Collator.getInstance(locale) as RuleBasedCollator).apply {
            strength = when {
                options.caseSensitive == true -> Collator.TERTIARY
                options.diacriticSensitive == true -> Collator.SECONDARY
                else -> Collator.PRIMARY
            }
        }
        val boundaries = if (options.wholeWord == true) BreakIterator.getWordInstance(locale) else null
        val iterator = StringSearch(query, StringCharacterIterator(text), collator, boundaries)
        val ranges = ArrayList<IntRange>()
        var position = iterator.first()
        while (position != SearchIterator.DONE) {
            currentCoroutineContext().ensureActive()
            if (ranges.size == MAX_MATCHES_PER_RESOURCE) throw SearchMatchLimitException()
            val range = position until position + iterator.matchLength
            // Readium extends its nominal snippet to a whitespace boundary, constructing
            // strings character by character. Bound only the actual match's two context
            // boundaries; unrelated long segments in a chapter do not reject the search.
            // Upstream's `count >= 0` loop consumes snippetLength + 1 characters
            // before testing the next character for the whitespace extension.
            checkContextBoundary(text, range.first - SNIPPET_LENGTH - 2, direction = -1)
            checkContextBoundary(text, range.last + SNIPPET_LENGTH + 2, direction = 1)
            ranges += range
            position = iterator.next()
        }
        return ranges
    }
    private suspend fun checkContextBoundary(text: String, start: Int, direction: Int) {
        var cursor = start
        var count = 0
        while (cursor in text.indices && !text[cursor].isWhitespace()) {
            if (count == MAX_UNBROKEN_CONTEXT) throw SearchContextLimitException()
            if (count % 256 == 0) currentCoroutineContext().ensureActive()
            count++
            cursor += direction
        }
    }
    private companion object {
        const val MAX_MATCHES_PER_RESOURCE = 1_000
        const val MAX_UNBROKEN_CONTEXT = 4_096
    }
}

/** Invoke from PublicationOpener.onCreatePublication; the remaining Readium search pipeline stays intact. */
@OptIn(ExperimentalReadiumApi::class)
internal fun Publication.Builder.configureBoundedEpubSearch() {
    if (Publication.Profile.EPUB in manifest.metadata.conformsTo) {
        servicesBuilder.searchServiceFactory = StringSearchService.createDefaultFactory(snippetLength = SNIPPET_LENGTH, searchAlgorithm = BoundedSearchAlgorithm())
    }
}
