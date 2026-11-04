package com.leaf.reader.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.data.readium.PublicationRepository
import com.leaf.reader.data.readium.ReaderDocument
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.publication.LocatorCollection
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.SearchService
import org.readium.r2.shared.publication.services.search.SearchTry
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.resource.Resource

/** Real EPUB search with delayed iterator delivery, without replacing matching or locators. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalReadiumApi::class, InternalReadiumApi::class)
class ReaderSearchLifetimeTest {
    @Test fun ownerReleaseWaitsForRealSearchIteratorToClose() = runBlocking {
        val fixture = delayedSearch()
        var matches = 0
        val search = async(Dispatchers.Default) { fixture.document.search("silver fern") { matches += it.size } }
        try {
            withTimeout(10_000) { fixture.entered.await() }
            fixture.document.releaseOwner()
            assertEquals(0, fixture.publicationCloses.get())
            fixture.deliver.complete(Unit)
            withTimeout(10_000) { search.await() }
            assertEquals("The actual Readium service must match each fixture chapter", 3, matches)
            assertEquals(1, fixture.iteratorCloses.get())
            assertEquals(1, fixture.publicationCloses.get())
        } finally { search.cancelAndJoin(); fixture.close() }
    }

    @Test fun cancellingSearchClosesIteratorBeforeReleasingPublication() = runBlocking {
        val fixture = delayedSearch()
        val search = async(Dispatchers.Default) { fixture.document.search("silver fern") {} }
        try {
            withTimeout(10_000) { fixture.entered.await() }
            fixture.document.releaseOwner()
            assertEquals(0, fixture.publicationCloses.get())
            search.cancelAndJoin()
            assertTrue(search.isCancelled)
            assertEquals(1, fixture.iteratorCloses.get())
            assertEquals(1, fixture.publicationCloses.get())
        } finally { search.cancelAndJoin(); fixture.close() }
    }

    private suspend fun delayedSearch(): SearchFixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "reader-search-lifetime/${UUID.randomUUID()}").apply { check(mkdirs()) }
        val file = File(directory, "standard.epub")
        instrumentation.context.assets.open("publications/standard.epub").use { input -> file.outputStream().use(input::copyTo) }
        val original = PublicationRepository(instrumentation.targetContext).open(file)
        try {
            val realSearch = requireNotNull(original.findService(SearchService::class))
            val entered = CompletableDeferred<Unit>()
            val deliver = CompletableDeferred<Unit>()
            val publicationCloses = AtomicInteger()
            val iteratorCloses = AtomicInteger()
            val delayed = object : SearchService by realSearch {
                override suspend fun search(query: String, options: SearchService.Options?): SearchIterator {
                    val actual = realSearch.search(query, options)
                    return object : SearchIterator by actual {
                        override suspend fun next(): SearchTry<LocatorCollection?> {
                            entered.complete(Unit)
                            deliver.await()
                            return actual.next()
                        }
                        override fun close() { iteratorCloses.incrementAndGet(); actual.close() }
                    }
                }
                override fun close() = Unit // Original publication owns the delegated service.
            }
            val container = object : Container<Resource> by original.container {
                override fun close() {
                    assertEquals("Close the iterator before its real publication", 1, iteratorCloses.get())
                    if (publicationCloses.incrementAndGet() == 1) original.close()
                }
            }
            val publication = Publication(original.manifest, container, Publication.ServicesBuilder(search = { delayed }))
            return SearchFixture(ReaderDocument(publication, PublicationFormat.EPUB, null), entered, deliver,
                publicationCloses, iteratorCloses, directory)
        } catch (failure: Throwable) {
            original.close(); directory.deleteRecursively(); throw failure
        }
    }

    private class SearchFixture(
        val document: ReaderDocument,
        val entered: CompletableDeferred<Unit>,
        val deliver: CompletableDeferred<Unit>,
        val publicationCloses: AtomicInteger,
        val iteratorCloses: AtomicInteger,
        val directory: File,
    ) {
        fun close() { deliver.complete(Unit); document.releaseOwner(); directory.deleteRecursively() }
    }
}
