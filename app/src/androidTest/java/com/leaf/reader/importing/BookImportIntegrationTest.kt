package com.leaf.reader.importing

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leaf.reader.core.util.BoundedFileCopier
import com.leaf.reader.core.util.ImportProblem
import com.leaf.reader.core.util.PublicationFormat
import com.leaf.reader.core.util.PublicationImportException
import com.leaf.reader.data.local.database.LeafDatabase
import com.leaf.reader.data.local.files.ManagedBookFiles
import com.leaf.reader.data.readium.PublicationAccessException
import com.leaf.reader.data.readium.PublicationRepository
import com.leaf.reader.data.repository.*
import com.leaf.reader.domain.usecase.ImportBooksUseCase
import com.leaf.reader.domain.usecase.RemoveBookUseCase
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.use

/** Real content-provider streams, Room, filesystem and Readium; all data is isolated per test. */
@RunWith(AndroidJUnit4::class)
class BookImportIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private lateinit var testRoot: File
    private lateinit var sourceRoot: File
    private lateinit var privateContext: Context
    private lateinit var database: LeafDatabase
    private lateinit var books: BookRepository
    private lateinit var reading: ReadingRepository
    private lateinit var annotations: AnnotationRepository
    private lateinit var files: ManagedBookFiles
    private lateinit var publications: PublicationRepository
    private lateinit var importer: BookImportRepository
    private lateinit var preferences: PreferencesRepository
    private lateinit var preferenceJob: Job

    @Before fun setUp() {
        val testId = UUID.randomUUID().toString()
        testRoot = File(target.cacheDir, "import-test-state/$testId").apply { check(mkdirs()) }
        sourceRoot = File(target.cacheDir, "publication-fixtures/$testId").apply { check(mkdirs()) }
        privateContext = object : ContextWrapper(target) {
            override fun getFilesDir(): File = File(testRoot, "files").apply { check(isDirectory || mkdirs()) }
        }
        database = newDatabase()
        books = BookRepository(database)
        reading = ReadingRepository(database)
        annotations = AnnotationRepository(database)
        files = ManagedBookFiles(privateContext)
        publications = PublicationRepository(target)
        importer = BookImportRepository(target, files, books, publications)
        preferenceJob = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + preferenceJob), produceFile = { File(testRoot, "preferences.preferences_pb") })
        preferences = PreferencesRepository(store)
    }

    @After fun tearDown() = runBlocking {
        if (this@BookImportIntegrationTest::database.isInitialized) database.close()
        if (this@BookImportIntegrationTest::preferenceJob.isInitialized) preferenceJob.cancelAndJoin()
        if (this@BookImportIntegrationTest::testRoot.isInitialized) testRoot.deleteRecursively()
        if (this@BookImportIntegrationTest::sourceRoot.isInitialized) sourceRoot.deleteRecursively()
    }

    @Test fun epubContentUriImportExtractsRealMetadataAndPreservesSourceBytes() = runBlocking {
        val (uri, original) = source("standard.epub")
        val sourceHash = sha256(original)
        val result = importer.importBook(uri)
        val book = checkNotNull(books.get(result.bookId))
        assertFalse(result.duplicate)
        assertFalse(result.restored)
        assertEquals(PublicationFormat.EPUB, book.format)
        assertEquals("Leaf Quiet Mornings", book.title)
        assertEquals("Leaf fixture contributors", book.author)
        assertEquals(sourceHash, book.contentHash)
        assertEquals(original.length(), book.fileSize)
        assertEquals(uri.toString(), book.sourceUri)
        assertTrue(File(book.storagePath).isFile)
        assertEquals(sourceHash, sha256(File(book.storagePath)))
        assertTrue(File(checkNotNull(book.coverPath)).length() > 0)
        assertEquals(sourceHash, sha256(original))
        assertTrue(temporaryFiles().isEmpty())
        publications.open(File(book.storagePath)).use { publication -> assertEquals(3, publication.readingOrder.size) }
    }

    @Test fun ordinaryAndScannedPdfImportUseActualPublicationParser() = runBlocking {
        for ((name, pages) in listOf("ordinary.pdf" to 3, "scanned.pdf" to 2)) {
            val (uri, original) = source(name)
            val result = importer.importBook(uri)
            val book = checkNotNull(books.get(result.bookId))
            assertEquals(PublicationFormat.PDF, book.format)
            publications.open(File(book.storagePath)).use { publication ->
                assertEquals(1, publication.readingOrder.size)
                assertEquals(pages, publication.metadata.numberOfPages)
                assertEquals(pages, publication.positions().size)
            }
            assertTrue(File(checkNotNull(book.coverPath)).isFile)
            assertEquals(sha256(original), sha256(File(book.storagePath)))
        }
        assertEquals(2, books.books.first().size)
        assertTrue(temporaryFiles().isEmpty())
    }

    @Test fun byteIdenticalBooksDeduplicateAcrossDifferentSourceNames() = runBlocking {
        val (firstUri, original) = source("standard.epub")
        val first = importer.importBook(firstUri)
        val secondSource = File(sourceRoot, "Another name.epub").apply { original.copyTo(this) }
        val second = importer.importBook(uri(secondSource))
        assertTrue(second.duplicate)
        assertFalse(second.restored)
        assertEquals(first.bookId, second.bookId)
        assertEquals(1, books.books.first().size)
        assertEquals(1, File(privateContext.filesDir, "books").listFiles()!!.size)
        assertTrue(original.isFile)
        assertTrue(secondSource.isFile)
        assertTrue(temporaryFiles().isEmpty())
    }

    @Test fun reimportRestoresMissingPrivateCopyWithoutLosingProgressOrAnnotations() = runBlocking {
        val (uri, original) = source("standard.epub")
        val first = importer.importBook(uri)
        val book = checkNotNull(books.get(first.bookId))
        reading.saveProgress(book.id, LOCATOR, 0.4)
        val bookmark = annotations.addBookmark(book.id, LOCATOR, "Keep this")
        val highlight = annotations.addHighlight(book.id, LOCATOR, "A silver fern", 0xFFFFD54FL, "A private note")
        assertTrue(File(book.storagePath).delete())
        val restored = importer.importBook(uri)
        assertTrue(restored.restored)
        assertEquals(book.id, restored.bookId)
        assertTrue(File(book.storagePath).isFile)
        assertEquals(sha256(original), sha256(File(book.storagePath)))
        assertEquals(LOCATOR, reading.getProgress(book.id)?.locatorJson)
        assertEquals(bookmark, annotations.bookmarks(book.id).first().single().id)
        assertEquals(highlight, annotations.highlights(book.id).first().single().id)
        assertEquals("A private note", annotations.highlights(book.id).first().single().note)
        assertEquals(1, books.books.first().size)
    }

    @Test fun reimportRepairsZeroLengthAndSameSizeCorruptPrivateCopiesWithoutChangingIdentity() = runBlocking {
        val (sourceUri, original) = source("standard.epub")
        val id = importer.importBook(sourceUri).bookId
        val book = checkNotNull(books.get(id))
        annotations.addBookmark(id, LOCATOR, "Keep my position")
        reading.saveProgress(id, LOCATOR, 0.4)
        for (zeroLength in listOf(true, false)) {
            val damaged = if (zeroLength) ByteArray(0) else original.readBytes().also { it[it.lastIndex / 2] = (it[it.lastIndex / 2].toInt() xor 1).toByte() }
            File(book.storagePath).writeBytes(damaged)
            val result = importer.importBook(sourceUri)
            assertTrue(result.restored)
            assertEquals(id, result.bookId)
            assertEquals(sha256(original), sha256(File(book.storagePath)))
            assertEquals(LOCATOR, reading.getProgress(id)?.locatorJson)
            assertEquals("Keep my position", annotations.bookmarks(id).first().single().label)
            assertTrue(File(privateContext.filesDir, "recovery/books").listFiles().orEmpty().any { it.readBytes().contentEquals(damaged) })
        }
        assertEquals(1, books.books.first().size)
        assertTrue(original.isFile)
    }

    @Test fun oversizedMetadataIsRejectedWithoutErasingSourceOrCreatingLibraryRows() = runBlocking {
        val (_, original) = source("standard.epub")
        val oversized = File(sourceRoot, "oversized-title.epub")
        ZipFile(original).use { archive -> ZipOutputStream(oversized.outputStream()).use { output ->
            archive.entries().asSequence().forEach { entry ->
                val sourceBytes = archive.getInputStream(entry).use { it.readBytes() }
                val bytes = if (entry.name.endsWith(".opf")) {
                    val sourceText = sourceBytes.toString(Charsets.UTF_8)
                    val changed = sourceText.replace("<dc:title>Leaf Quiet Mornings</dc:title>", "<dc:title>${"x".repeat(16 * 1024 + 1)}</dc:title>")
                    check(changed != sourceText)
                    changed.toByteArray()
                } else sourceBytes
                val replacement = ZipEntry(entry.name)
                if (entry.name == "mimetype") { replacement.method = ZipEntry.STORED; replacement.size = bytes.size.toLong(); replacement.crc = CRC32().apply { update(bytes) }.value }
                output.putNextEntry(replacement); output.write(bytes); output.closeEntry()
            }
        } }
        val sourceHash = sha256(oversized)
        assertEquals(ImportProblem.RESOURCE_LIMIT, (failure { importer.importBook(uri(oversized)) } as PublicationImportException).problem)
        assertEquals(sourceHash, sha256(oversized))
        assertTrue(books.books.first().isEmpty())
        assertTrue(temporaryFiles().isEmpty())
    }

    @Test fun importedBookAndPositionSurviveDatabaseReopen() = runBlocking {
        val id = importer.importBook(source("standard.epub").first).bookId
        reading.saveProgress(id, LOCATOR, 0.6)
        database.close()
        database = newDatabase()
        val reopened = BookRepository(database).get(id)
        assertNotNull(reopened)
        assertTrue(File(checkNotNull(reopened).storagePath).isFile)
        assertEquals(LOCATOR, ReadingRepository(database).getProgress(id)?.locatorJson)
        assertEquals(0.6, ReadingRepository(database).getProgress(id)!!.progression, 0.00001)
    }

    @Test fun confirmedRemovalDeletesOnlyManagedCopyAndCascadesAnnotations() = runBlocking {
        val (uri, original) = source("standard.epub")
        val id = importer.importBook(uri).bookId
        val book = checkNotNull(books.get(id))
        annotations.addBookmark(id, LOCATOR)
        annotations.addHighlight(id, LOCATOR, "Text", 0xFFFFD54FL, "Private note")
        reading.saveProgress(id, LOCATOR, 0.5)
        val result = RemoveBookUseCase(books, files, preferences)(id, deleteManagedCopyConfirmed = true)
        assertTrue(result.removed)
        assertTrue(result.managedCopiesDeleted)
        assertFalse(result.copiesRetainedForRecovery)
        assertNull(books.get(id))
        assertFalse(File(book.storagePath).exists())
        assertFalse(File(checkNotNull(book.coverPath)).exists())
        assertTrue(original.isFile)
        assertTrue(annotations.bookmarks(id).first().isEmpty())
        assertTrue(annotations.highlights(id).first().isEmpty())
        assertNull(reading.getProgress(id))
    }

    @Test fun removalWithoutCopyConfirmationPreservesPrivateBytesForRecovery() = runBlocking {
        val (uri, original) = source("standard.epub")
        val id = importer.importBook(uri).bookId
        val book = checkNotNull(books.get(id))
        val result = RemoveBookUseCase(books, files, preferences)(id, deleteManagedCopyConfirmed = false)
        assertTrue(result.copiesRetainedForRecovery)
        assertFalse(result.managedCopiesDeleted)
        assertNull(books.get(id))
        assertFalse(File(book.storagePath).exists())
        val recovery = File(privateContext.filesDir, "recovery/books").listFiles().orEmpty()
        assertEquals(1, recovery.size)
        assertEquals(sha256(original), sha256(recovery.single()))
        importer.clearTemporaryCache()
        assertTrue(recovery.single().isFile)
        assertTrue(original.isFile)
    }

    @Test fun corruptFilesFailSafelyWithoutVisibleRowsOrTemporaryFiles() = runBlocking {
        for (name in listOf("malformed.epub", "malformed.pdf")) {
            val (uri, original) = source(name)
            val failure = failure { importer.importBook(uri) }
            assertTrue(failure is PublicationImportException || failure is PublicationAccessException)
            assertFalse(failure.message.orEmpty().contains(privateContext.filesDir.path))
            assertFalse(failure.message.orEmpty().contains(uri.toString()))
            assertTrue(original.isFile)
            assertTrue(temporaryFiles().isEmpty())
        }
        assertTrue(books.books.first().isEmpty())
    }

    @Test fun unsupportedEmptyAndOversizeDocumentsHaveSpecificRecoveryErrors() = runBlocking {
        val unsupported = File(sourceRoot, "unsupported.txt").apply { writeText("text") }
        val empty = File(sourceRoot, "empty.pdf").apply { createNewFile() }
        val oversized = File(sourceRoot, "oversize.pdf").apply {
            RandomAccessFile(this, "rw").use { it.setLength(BoundedFileCopier.DEFAULT_MAX_BYTES + 1) }
        }
        assertEquals(ImportProblem.UNSUPPORTED_FORMAT, (failure { importer.importBook(uri(unsupported)) } as PublicationImportException).problem)
        assertEquals(ImportProblem.EMPTY_FILE, (failure { importer.importBook(uri(empty)) } as PublicationImportException).problem)
        assertEquals(ImportProblem.FILE_TOO_LARGE, (failure { importer.importBook(uri(oversized)) } as PublicationImportException).problem)
        assertTrue(books.books.first().isEmpty())
        assertTrue(temporaryFiles().isEmpty())
    }

    @Test fun multiFileImportKeepsValidBooksWhenAnotherFileFails() = runBlocking {
        val first = source("standard.epub").first
        val invalid = source("malformed.epub").first
        val last = source("ordinary.pdf").first
        val outcomes = ImportBooksUseCase(importer)(listOf(first, invalid, last))
        assertNotNull(outcomes[0].result)
        assertNull(outcomes[1].result)
        assertNotNull(outcomes[1].message)
        assertNotNull(outcomes[2].result)
        assertEquals(2, books.books.first().size)
    }

    @Test fun actualDatabaseAbortAfterFilePromotionPreservesSourcesAndQuarantinesPartialCopies() = runBlocking {
        val (existingUri, existingSource) = source("standard.epub")
        val existingId = importer.importBook(existingUri).bookId
        val existing = checkNotNull(books.get(existingId))
        val existingHash = sha256(existingSource)
        val (newUri, newSource) = source("ordinary.pdf")
        val newHash = sha256(newSource)
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_test_import BEFORE INSERT ON books BEGIN SELECT RAISE(ABORT, 'expected import transaction failure'); END")
        val error = try { failure { importer.importBook(newUri) } }
        finally { database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_test_import") }
        assertTrue("The failure must come from the real database insertion", error is BookImportException && error.cause is android.database.sqlite.SQLiteException)
        assertEquals(listOf(existingId), books.books.first().map { it.id })
        assertEquals(existingHash, sha256(File(existing.storagePath)))
        assertEquals(existingHash, sha256(existingSource))
        assertEquals(newHash, sha256(newSource))
        assertEquals(1, File(privateContext.filesDir, "books").listFiles()!!.size)
        val recovered = File(privateContext.filesDir, "recovery/books").listFiles().orEmpty()
        assertEquals(1, recovered.size)
        assertEquals(newHash, sha256(recovered.single()))
        assertEquals(1, File(privateContext.filesDir, "recovery/covers").listFiles().orEmpty().size)
        assertTrue(temporaryFiles().isEmpty())
    }

    @Test fun recoveryPreservesUnreferencedBooksAndCleansOnlyOwnedTemporaryFiles() = runBlocking {
        val id = importer.importBook(source("standard.epub").first).bookId
        val referenced = checkNotNull(books.get(id))
        val orphan = File(privateContext.filesDir, "books/${UUID.randomUUID()}.epub").apply { writeText("recoverable original bytes") }
        val temp = File(privateContext.filesDir, "cache/temporary-imports/leaf-import-abandoned.epub").apply { writeText("incomplete") }
        val foreign = File(temp.parentFile, "keep-me.txt").apply { writeText("unrelated") }
        val report = importer.recoverManagedFiles()
        assertEquals(1, report.quarantinedBooks)
        assertEquals(1, report.removedTemporaryFiles)
        assertEquals(1, report.retainedTemporaryEntries)
        assertFalse(orphan.exists())
        assertEquals("recoverable original bytes", File(privateContext.filesDir, "recovery/books").listFiles()!!.single().readText())
        assertTrue(File(referenced.storagePath).isFile)
        assertTrue(foreign.isFile)
        assertFalse(temp.exists())
    }

    @Test fun cleanupWaitsForAnInFlightImportStorageTransaction() = runBlocking {
        val lockHeld = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holding = launch(Dispatchers.IO) { files.exclusive { lockHeld.complete(Unit); release.await() } }
        lockHeld.await()
        val cleanup = async(Dispatchers.IO) { importer.clearTemporaryCache() }
        try { withTimeout(150) { cleanup.await() }; fail("Cleanup must wait for the import lock") }
        catch (_: TimeoutCancellationException) { }
        release.complete(Unit)
        holding.join()
        assertEquals(0, cleanup.await())
    }

    private fun newDatabase(): LeafDatabase = Room.databaseBuilder(target, LeafDatabase::class.java, File(testRoot, "library.db").absolutePath).build()
    private fun temporaryFiles(): List<File> = File(privateContext.filesDir, "cache/temporary-imports").listFiles().orEmpty().toList()
    private fun uri(file: File): Uri = FileProvider.getUriForFile(target, "${target.packageName}.fixtures", file)
    private fun source(name: String): Pair<Uri, File> {
        val file = File(sourceRoot, name)
        instrumentation.context.assets.open("publications/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        return uri(file) to file
    }
    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private suspend fun failure(action: suspend () -> Unit): Exception {
        try { action() } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { return failure }
        throw AssertionError("An import failure was expected")
    }
    private companion object { const val LOCATOR = "{\"href\":\"chapter-1.xhtml\",\"type\":\"application/xhtml+xml\",\"locations\":{\"progression\":0.4}}" }
}
