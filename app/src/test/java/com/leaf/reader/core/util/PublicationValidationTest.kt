package com.leaf.reader.core.util

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PublicationValidationTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun supportedExtensionsAreCaseInsensitive() {
        assertEquals(PublicationFormat.EPUB, PublicationValidation.formatFor("A.Book.EPUB"))
        assertEquals(PublicationFormat.PDF, PublicationValidation.formatFor("résumé.PdF"))
    }

    @Test
    fun unsupportedAndMissingExtensionsAreRejected() {
        listOf("epub", "book.epub.exe", "book.txt", "book.").forEach { name ->
            assertProblem(ImportProblem.UNSUPPORTED_FORMAT) { PublicationValidation.formatFor(name) }
        }
    }

    @Test
    fun structuralEpubGateAcceptsRequiredEntriesWithoutExtractingThem() {
        val file = epub(mapOf("mimetype" to "application/epub+zip", "META-INF/container.xml" to "<container/>"))
        PublicationValidation.validate(file, PublicationFormat.EPUB)
        assertEquals(1, temporary.root.listFiles()!!.size)
    }

    @Test
    fun zipWithoutContainerOrWithWrongMimeIsRejected() {
        val missingContainer = epub(mapOf("mimetype" to "application/epub+zip"))
        assertProblem(ImportProblem.INVALID_CONTENT) { PublicationValidation.validate(missingContainer, PublicationFormat.EPUB) }
        val wrongMime = epub(mapOf("mimetype" to "text/plain", "META-INF/container.xml" to "<container/>"))
        assertProblem(ImportProblem.INVALID_CONTENT) { PublicationValidation.validate(wrongMime, PublicationFormat.EPUB) }
    }

    @Test
    fun oversizedMimeCannotBeMistakenForEpub() {
        val file = epub(mapOf("mimetype" to "application/epub+zip" + " ".repeat(10_000), "META-INF/container.xml" to "<container/>"))
        assertProblem(ImportProblem.INVALID_CONTENT) { PublicationValidation.validate(file, PublicationFormat.EPUB) }
    }

    @Test
    fun malformedZipIsRejected() {
        val file = temporary.newFile().apply { writeText("PK not an actual ZIP") }
        assertProblem(ImportProblem.INVALID_CONTENT) { PublicationValidation.validate(file, PublicationFormat.EPUB) }
    }

    @Test
    fun pdfHeaderIsAcceptedWithinFirstKilobyte() {
        val file = temporary.newFile().apply { writeText("\n%PDF-1.7\n1 0 obj") }
        PublicationValidation.validate(file, PublicationFormat.PDF)
    }

    @Test
    fun renamedTextIsNotPdf() {
        val file = temporary.newFile("fake.pdf").apply { writeText("A plain text book") }
        assertProblem(ImportProblem.INVALID_CONTENT) { PublicationValidation.validate(file, PublicationFormat.PDF) }
    }

    @Test
    fun emptyAndMissingFilesHaveSpecificRecoveryErrors() {
        assertProblem(ImportProblem.EMPTY_FILE) { PublicationValidation.validate(temporary.newFile(), PublicationFormat.PDF) }
        assertProblem(ImportProblem.UNREADABLE_CONTENT) { PublicationValidation.validate(File(temporary.root, "missing"), PublicationFormat.EPUB) }
    }

    @Test
    fun metadataUsesFilenameAndAuthorFallbacksWithoutLosingUnicode() {
        val metadata = MetadataFallback.resolve(" ", listOf("", " "), "  ", "/source/雪の本.EPUB")
        assertEquals(BookMetadata("雪の本", "Unknown author", null), metadata)
    }

    @Test
    fun publicationMetadataWinsOverFilename() {
        val metadata = MetadataFallback.resolve("  A title ", listOf(" Ada ", "", " Linus "), " Description ", "copy.epub")
        assertEquals(BookMetadata("A title", "Ada, Linus", "Description"), metadata)
        assertEquals("Untitled book", MetadataFallback.resolve(null, emptyList(), null, ".pdf").title)
    }

    private fun epub(entries: Map<String, String>): File = temporary.newFile().apply {
        ZipOutputStream(outputStream()).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
    }

    private fun assertProblem(problem: ImportProblem, action: () -> Unit) {
        assertEquals(problem, assertThrows(PublicationImportException::class.java, action).problem)
    }
}
