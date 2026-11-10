package com.leaf.reader.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.data.readium.PublicationRepository
import com.leaf.reader.data.readium.ReaderDocument
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.LocatorService
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.resource.Resource

/**
 * Uses a parsed EPUB and its actual Readium locator service. Only the service's delivery is
 * gated to reproduce slow position loading; no publication data or locator result is faked.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(InternalReadiumApi::class)
class ReaderOperationLifetimeTest {
    @get:Rule val testTimeout: TestRule = Timeout.seconds(120)
    @Test fun closingReaderKeepsRealPublicationOpenUntilPendingSeekCompletes() = runBlocking {
        val fixture = delayedFixture()
        val seek = async(Dispatchers.Main) { fixture.document.goProgression(0.75) }
        try {
            withTimeout(10_000) { fixture.entered.await() }
            withContext(Dispatchers.Main) { fixture.document.releaseOwner() }
            assertEquals("A pending read must retain the real publication", 0, fixture.closes.get())

            fixture.continueSeek.complete(Unit)
            // No navigator is attached in this lifetime test; the real locator calculation
            // still finishes before the document relinquishes its container.
            assertFalse(withTimeout(10_000) { seek.await() })
            assertNotNull("The real Readium service must resolve the fixture", fixture.resolved.get())
            assertEquals(1, fixture.closes.get())
        } finally {
            seek.cancelAndJoin()
            fixture.close()
        }
    }

    @Test fun cancellingPendingSeekReleasesItsPublicationLease() = runBlocking {
        val fixture = delayedFixture()
        val seek = async(Dispatchers.Main) { fixture.document.goProgression(0.75) }
        try {
            withTimeout(10_000) { fixture.entered.await() }
            withContext(Dispatchers.Main) { fixture.document.releaseOwner() }
            assertEquals(0, fixture.closes.get())

            seek.cancelAndJoin()
            assertTrue("Seek cancellation must propagate", seek.isCancelled)
            assertEquals("Cancellation must release the final resource lease", 1, fixture.closes.get())
            fixture.continueSeek.complete(Unit)
            assertFalse("A released reader must reject subsequent seeks", fixture.document.goProgression(0.25))
            assertEquals("Repeated close/rejected seek must not close twice", 1, fixture.closes.get())
        } finally {
            seek.cancelAndJoin()
            fixture.close()
        }
    }

    private suspend fun delayedFixture(): DelayedFixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "reader-operation-tests/${UUID.randomUUID()}")
            .apply { check(mkdirs()) }
        val file = File(directory, "standard.epub")
        instrumentation.context.assets.open("publications/standard.epub").use { input ->
            file.outputStream().use(input::copyTo)
        }
        val original = PublicationRepository(instrumentation.targetContext).open(file)
        try {
            val realLocatorService = requireNotNull(original.findService(LocatorService::class))
            val entered = CompletableDeferred<Unit>()
            val continueSeek = CompletableDeferred<Unit>()
            val closes = AtomicInteger()
            val resolved = AtomicReference<Locator?>()
            val delayedLocator = object : LocatorService by realLocatorService {
                override suspend fun locateProgression(totalProgression: Double): Locator? {
                    entered.complete(Unit)
                    continueSeek.await()
                    return realLocatorService.locateProgression(totalProgression).also(resolved::set)
                }
                // The tracked container closes the original publication and all its services.
                override fun close() = Unit
            }
            val trackedContainer = object : Container<Resource> by original.container {
                override fun close() {
                    if (closes.incrementAndGet() == 1) original.close()
                }
            }
            val publication = Publication(original.manifest, trackedContainer,
                Publication.ServicesBuilder(locator = { delayedLocator }))
            return DelayedFixture(ReaderDocument(publication, PublicationFormat.EPUB, null), entered,
                continueSeek, closes, resolved, directory)
        } catch (failure: Throwable) {
            original.close()
            directory.deleteRecursively()
            throw failure
        }
    }

    private class DelayedFixture(
        val document: ReaderDocument,
        val entered: CompletableDeferred<Unit>,
        val continueSeek: CompletableDeferred<Unit>,
        val closes: AtomicInteger,
        val resolved: AtomicReference<Locator?>,
        private val directory: File,
    ) {
        fun close() {
            continueSeek.complete(Unit)
            document.releaseOwner()
            directory.deleteRecursively()
        }
    }
}
