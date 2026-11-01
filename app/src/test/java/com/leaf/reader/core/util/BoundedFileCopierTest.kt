package com.leaf.reader.core.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedFileCopierTest {
    @Test
    fun knownSha256AndBytesArePreserved() {
        val bytes = "abc".toByteArray()
        val output = ByteArrayOutputStream()
        val result = BoundedFileCopier.copy(bytes.inputStream(), output, 3L, { 100L }, 0L)
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(3L, result.byteCount)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result.sha256)
    }

    @Test
    fun identicalContentHasSameHashAcrossDifferentReadBoundaries() {
        val bytes = ByteArray(180_000) { (it % 251).toByte() }
        val shortReads = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 137))
        }
        val first = BoundedFileCopier.copy(bytes.inputStream(), ByteArrayOutputStream(), availableBytes = { Long.MAX_VALUE })
        val second = BoundedFileCopier.copy(shortReads, ByteArrayOutputStream(), availableBytes = { Long.MAX_VALUE })
        assertEquals(first, second)
    }

    @Test
    fun overLimitDoesNotWriteTheOverflowingChunk() {
        val output = ByteArrayOutputStream()
        val error = assertThrows(PublicationImportException::class.java) {
            BoundedFileCopier.copy(ByteArray(70_000).inputStream(), output, 65_536L, { Long.MAX_VALUE })
        }
        assertEquals(ImportProblem.FILE_TOO_LARGE, error.problem)
        assertEquals(65_536, output.size())
    }

    @Test
    fun storageReserveIsRecheckedDuringCopy() {
        val output = ByteArrayOutputStream()
        val error = assertThrows(PublicationImportException::class.java) {
            BoundedFileCopier.copy(
                ByteArray(70_000).inputStream(),
                output,
                availableBytes = { if (output.size() == 0) 100_000L else 17L },
                reserveBytes = 16L,
            )
        }
        assertEquals(ImportProblem.INSUFFICIENT_STORAGE, error.problem)
        assertEquals(65_536, output.size())
    }

    @Test
    fun exactReserveAndPayloadFit() {
        val result = BoundedFileCopier.copy("a".byteInputStream(), ByteArrayOutputStream(), 1L, { 9L }, 8L)
        assertEquals(1L, result.byteCount)
    }

    @Test
    fun emptyFileIsRejected() {
        val error = assertThrows(PublicationImportException::class.java) {
            BoundedFileCopier.copy(ByteArray(0).inputStream(), ByteArrayOutputStream(), availableBytes = { 0L })
        }
        assertEquals(ImportProblem.EMPTY_FILE, error.problem)
    }

    @Test
    fun unreadableStreamHasUserSafeMessage() {
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("/private/secret-book.epub")
        }
        val error = assertThrows(PublicationImportException::class.java) {
            BoundedFileCopier.copy(input, ByteArrayOutputStream(), availableBytes = { Long.MAX_VALUE })
        }
        assertEquals(ImportProblem.UNREADABLE_CONTENT, error.problem)
        assertFalse(error.message.orEmpty().contains("secret-book"))
    }

    @Test
    fun callerRetainsStreamOwnership() {
        var inputClosed = false
        var outputClosed = false
        val input = object : ByteArrayInputStream(byteArrayOf(42)) {
            override fun close() { inputClosed = true }
        }
        val output = object : ByteArrayOutputStream() {
            override fun close() { outputClosed = true }
        }
        BoundedFileCopier.copy(input, output, availableBytes = { Long.MAX_VALUE })
        assertFalse(inputClosed)
        assertFalse(outputClosed)
    }

    @Test
    fun zeroLengthProviderReadsMakeProgress() {
        val input = object : ByteArrayInputStream("abc".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        val result = BoundedFileCopier.copy(input, ByteArrayOutputStream(), availableBytes = { Long.MAX_VALUE })
        assertEquals(3L, result.byteCount)
    }
}
