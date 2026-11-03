package com.leaf.reader.core.util

import java.io.File
import java.io.IOException
import java.io.ByteArrayInputStream
import java.io.RandomAccessFile
import java.net.URI
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

enum class PublicationFormat(val extension: String, val mediaType: String) {
    EPUB("epub", "application/epub+zip"),
    PDF("pdf", "application/pdf"),
}

/** An inexpensive structural gate before the publication engine performs full parsing. */
object PublicationValidation {
    const val MAX_TEXT_RESOURCE_BYTES = 8L * 1024 * 1024
    const val MAX_BINARY_RESOURCE_BYTES = 256L * 1024 * 1024
    const val MAX_EXPANDED_ARCHIVE_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_ARCHIVE_ENTRIES = 100_000
    private const val MAX_CONTAINER_BYTES = 1024L * 1024
    private const val MAX_CENTRAL_DIRECTORY_BYTES = 16L * 1024 * 1024
    private const val MAX_METADATA_ELEMENTS = 25_000
    private val binaryExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "heic", "bmp", "tif", "tiff", "ttf", "otf", "woff", "woff2", "mp3", "m4a", "mp4", "ogg", "oga", "wav", "webm")
    fun formatFor(fileName: String): PublicationFormat =
        when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "epub" -> PublicationFormat.EPUB
            "pdf" -> PublicationFormat.PDF
            else -> throw PublicationImportException(ImportProblem.UNSUPPORTED_FORMAT)
        }

    fun validate(file: File, format: PublicationFormat) {
        if (!file.isFile || !file.canRead()) {
            throw PublicationImportException(ImportProblem.UNREADABLE_CONTENT)
        }
        if (file.length() == 0L) throw PublicationImportException(ImportProblem.EMPTY_FILE)
        try {
            when (format) {
                PublicationFormat.EPUB -> validateEpub(file)
                PublicationFormat.PDF -> validatePdf(file)
            }
        } catch (error: PublicationImportException) {
            throw error
        } catch (error: ZipException) {
            throw PublicationImportException(ImportProblem.INVALID_CONTENT, error)
        } catch (error: IOException) {
            throw PublicationImportException(ImportProblem.UNREADABLE_CONTENT, error)
        } catch (error: SAXException) {
            throw PublicationImportException(ImportProblem.INVALID_CONTENT, error)
        }
    }

    private fun validatePdf(file: File) {
        val header = ByteArray(1024)
        val count = file.inputStream().use { it.read(header) }
        val signature = "%PDF-".toByteArray(Charsets.US_ASCII)
        val found = (0..(count - signature.size)).any { offset ->
            signature.indices.all { header[offset + it] == signature[it] }
        }
        if (!found) throw PublicationImportException(ImportProblem.INVALID_CONTENT)
        // Password protection and page-tree validity are checked by Readium's PDF parser.
    }

    private fun validateEpub(file: File) {
        preflightCentralDirectory(file)
        ZipFile(file).use { archive ->
            val entries = linkedMapOf<String, ZipEntry>()
            var expanded = 0L
            val enumeration = archive.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (entries.size >= MAX_ARCHIVE_ENTRIES) resourceLimit()
                if (entry.name.length > 4096 || entry.name.startsWith('/') || '\\' in entry.name || entry.name.split('/').any { it == ".." }) invalid()
                if (entries.put(entry.name, entry) != null) invalid()
                if (entry.isDirectory) continue
                val size = entry.size
                if (size < 0) invalid()
                val limit = when {
                    entry.name == "META-INF/container.xml" -> MAX_CONTAINER_BYTES
                    entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in binaryExtensions -> MAX_BINARY_RESOURCE_BYTES
                    else -> MAX_TEXT_RESOURCE_BYTES
                }
                if (size > limit || size > MAX_EXPANDED_ARCHIVE_BYTES - expanded) resourceLimit()
                expanded += size
            }
            val mime = archive.getEntry("mimetype")
            val container = archive.getEntry("META-INF/container.xml")
            if (mime == null || mime.isDirectory || container == null || container.isDirectory ||
                container.size == 0L
            ) {
                throw PublicationImportException(ImportProblem.INVALID_CONTENT)
            }
            // Read no more than the fixed MIME value plus one byte, even for a malicious ZIP.
            val expected = PublicationFormat.EPUB.mediaType.toByteArray(Charsets.US_ASCII)
            val actual = archive.getInputStream(mime).use { stream ->
                val value = ByteArray(expected.size + 1)
                var size = 0
                while (size < value.size) {
                    val count = stream.read(value, size, value.size - size)
                    if (count < 0) break
                    if (count == 0) {
                        val byte = stream.read()
                        if (byte < 0) break
                        value[size++] = byte.toByte()
                    } else {
                        size += count
                    }
                }
                value.copyOf(size)
            }
            if (!actual.contentEquals(expected)) {
                throw PublicationImportException(ImportProblem.INVALID_CONTENT)
            }
            val textPaths = entries.values.filterNot { it.isDirectory }.filter {
                it.name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in binaryExtensions
            }.mapTo(linkedSetOf()) { it.name }
            val rootfiles = linkedSetOf<String>()
            val metadataPaths = linkedSetOf("META-INF/container.xml")
            parseXml(readBounded(archive, container, MAX_CONTAINER_BYTES), object : SafeXmlHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                    if ((localName ?: qName) == "rootfile") {
                        val path = attributes.getValue("full-path") ?: invalid()
                        if (rootfiles.size >= 100 || path.length > 4096) resourceLimit()
                        rootfiles += normalizePath(path)
                    }
                }
            })
            rootfiles.forEach { path ->
                val opf = entries[path]?.takeUnless { it.isDirectory } ?: invalid()
                textPaths += path
                metadataPaths += path
                val manifest = mutableMapOf<String, String>()
                val spine = mutableListOf<String>()
                parseXml(readBounded(archive, opf, MAX_TEXT_RESOURCE_BYTES), object : SafeXmlHandler() {
                    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                        when (localName ?: qName) {
                            "item" -> {
                                if (manifest.size >= MAX_ARCHIVE_ENTRIES) resourceLimit()
                                val href = attributes.getValue("href") ?: return
                                val resolved = resolveResource(path, href) ?: return
                                attributes.getValue("id")?.let { manifest[it] = resolved }
                                val mediaType = attributes.getValue("media-type").orEmpty().lowercase(Locale.ROOT)
                                if (mediaType.startsWith("text/") || "xml" in mediaType || "json" in mediaType || "javascript" in mediaType) textPaths += resolved
                                if ("nav" in attributes.getValue("properties").orEmpty().split(Regex("\\s+")) || mediaType == "application/x-dtbncx+xml" || mediaType == "application/smil+xml") metadataPaths += resolved
                            }
                            "itemref" -> attributes.getValue("idref")?.let { if (spine.size >= MAX_ARCHIVE_ENTRIES) resourceLimit(); spine += it }
                        }
                    }
                })
                spine.forEach { id ->
                    val resource = manifest[id]?.let { entries[it] } ?: invalid()
                    if (resource.isDirectory || resource.size == 0L) invalid()
                }
            }
            // Bound actual inflated reads as well as ZIP metadata. OPF files and text with a
            // misleading binary extension are included using the package's real manifest.
            textPaths.forEach { path -> entries[path]?.takeUnless { it.isDirectory }?.let {
                if (path in metadataPaths) checkMarkupBudget(readBounded(archive, it, if (path == "META-INF/container.xml") MAX_CONTAINER_BYTES else MAX_TEXT_RESOURCE_BYTES))
                else drainBounded(archive, it, MAX_TEXT_RESOURCE_BYTES)
            } }
            // No entries are extracted. Readium performs full publication/protection parsing.
        }
    }

    /** Prevent ZipFile from allocating a huge central directory before entry limits can run. */
    private fun preflightCentralDirectory(file: File) {
        RandomAccessFile(file, "r").use { input ->
            val tailSize = minOf(input.length(), 65_557L).toInt()
            if (tailSize < 22) invalid()
            val tail = ByteArray(tailSize)
            input.seek(input.length() - tailSize); input.readFully(tail)
            val index = (tailSize - 22 downTo 0).firstOrNull {
                u32(tail, it) == 0x06054b50L && it + 22 + u16(tail, it + 20) == tail.size
            } ?: invalid()
            val eocdOffset = input.length() - tailSize + index
            if (u16(tail, index + 4) != 0 || u16(tail, index + 6) != 0) invalid()
            var count = u16(tail, index + 10).toLong()
            var size = u32(tail, index + 12)
            var offset = u32(tail, index + 16)
            if (count == 65_535L || size == 0xFFFFFFFFL || offset == 0xFFFFFFFFL) {
                if (eocdOffset < 20) invalid()
                val locator = ByteArray(20)
                input.seek(eocdOffset - 20); input.readFully(locator)
                if (u32(locator, 0) != 0x07064b50L || u32(locator, 4) != 0L || u32(locator, 16) != 1L) invalid()
                val zip64Offset = u64(locator, 8)
                if (zip64Offset < 0 || zip64Offset > eocdOffset - 56) invalid()
                val record = ByteArray(56)
                input.seek(zip64Offset); input.readFully(record)
                if (u32(record, 0) != 0x06064b50L || u32(record, 16) != 0L || u32(record, 20) != 0L) invalid()
                count = u64(record, 32); size = u64(record, 40); offset = u64(record, 48)
            }
            if (count < 0 || size < 0 || offset < 0) invalid()
            if (count > MAX_ARCHIVE_ENTRIES || size > MAX_CENTRAL_DIRECTORY_BYTES) resourceLimit()
            if (offset > eocdOffset || size > eocdOffset - offset) invalid()
            var cursor = offset
            var actualCount = 0L
            val header = ByteArray(46)
            while (cursor < offset + size) {
                if (++actualCount > MAX_ARCHIVE_ENTRIES) resourceLimit()
                if (offset + size - cursor < header.size) invalid()
                input.seek(cursor); input.readFully(header)
                if (u32(header, 0) != 0x02014b50L) invalid()
                val recordSize = 46L + u16(header, 28) + u16(header, 30) + u16(header, 32)
                if (recordSize > offset + size - cursor) invalid()
                cursor += recordSize
            }
            if (actualCount != count) invalid()
        }
    }

    private fun readBounded(archive: ZipFile, entry: ZipEntry, maximum: Long): ByteArray {
        if (entry.size < 0 || entry.size > maximum) resourceLimit()
        return archive.getInputStream(entry).use { stream ->
            val output = java.io.ByteArrayOutputStream(minOf(entry.size, 65_536L).toInt())
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (count.toLong() > maximum - total) resourceLimit()
                output.write(buffer, 0, count); total += count
            }
            output.toByteArray()
        }
    }

    private fun drainBounded(archive: ZipFile, entry: ZipEntry, maximum: Long) {
        if (entry.size > maximum) resourceLimit()
        archive.getInputStream(entry).use { stream ->
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (count.toLong() > maximum - total) resourceLimit()
                total += count
            }
        }
    }

    private open class SafeXmlHandler : DefaultHandler2() {
        override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("Document types are not supported in EPUB metadata.") }
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource { throw SAXException("External entities are not supported.") }
    }

    private fun parseXml(bytes: ByteArray, handler: SafeXmlHandler) {
        checkMarkupBudget(bytes)
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        reader.contentHandler = handler
        reader.entityResolver = handler
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.parse(InputSource(ByteArrayInputStream(bytes)))
    }

    /** Conservative token budget before Readium creates metadata/navigation DOMs. */
    private fun checkMarkupBudget(bytes: ByteArray) {
        fun byte(index: Int): Int = bytes.getOrNull(index)?.toInt()?.and(255) ?: -1
        val width: Int
        val littleEndian: Boolean
        when {
            (byte(0) == 255 && byte(1) == 254 && byte(2) == 0 && byte(3) == 0) || (byte(0) == 60 && byte(1) == 0 && byte(2) == 0 && byte(3) == 0) -> { width = 4; littleEndian = true }
            (byte(0) == 0 && byte(1) == 0 && byte(2) == 254 && byte(3) == 255) || (byte(0) == 0 && byte(1) == 0 && byte(2) == 0 && byte(3) == 60) -> { width = 4; littleEndian = false }
            (byte(0) == 255 && byte(1) == 254) || (byte(0) == 60 && byte(1) == 0) -> { width = 2; littleEndian = true }
            (byte(0) == 254 && byte(1) == 255) || (byte(0) == 0 && byte(1) == 60) -> { width = 2; littleEndian = false }
            else -> { width = 1; littleEndian = true }
        }
        fun character(index: Int): Int {
            if (index < 0 || index + width > bytes.size) return -1
            return when (width) {
                4 -> (if (littleEndian) u32(bytes, index) else (0 until 4).fold(0L) { result, offset -> (result shl 8) or byte(index + offset).toLong() }).let { if (it > Int.MAX_VALUE) -1 else it.toInt() }
                2 -> if (littleEndian) u16(bytes, index) else (byte(index) shl 8) or byte(index + 1)
                else -> byte(index)
            }
        }
        var nodes = 0
        var offset = 0
        while (offset + width <= bytes.size) {
            if (character(offset) == '<'.code && character(offset + width) !in listOf('/'.code, '!'.code, '?'.code) && ++nodes > MAX_METADATA_ELEMENTS) resourceLimit()
            offset += width
        }
    }

    private fun resolveResource(opf: String, href: String): String? {
        if (href.length > 4096) resourceLimit()
        val reference = try { URI(href.replace(" ", "%20")) } catch (_: Exception) { invalid() }
        if (reference.isAbsolute || reference.rawAuthority != null) return null
        val base = URI("epub", "package", "/$opf", null)
        val resolved = base.resolve(reference)
        if (resolved.host != "package") return null
        return normalizePath(resolved.path.removePrefix("/"))
    }

    private fun normalizePath(path: String): String {
        if (path.startsWith('/') || '\\' in path) invalid()
        val segments = mutableListOf<String>()
        path.split('/').forEach { segment -> when (segment) {
            "", "." -> Unit
            ".." -> { if (segments.isEmpty()) invalid(); segments.removeAt(segments.lastIndex) }
            else -> segments += segment
        } }
        return segments.joinToString("/")
    }

    private fun u16(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    private fun u32(bytes: ByteArray, offset: Int): Long = (0 until 4).fold(0L) { value, index -> value or ((bytes[offset + index].toLong() and 255) shl (index * 8)) }
    private fun u64(bytes: ByteArray, offset: Int): Long = (0 until 8).fold(0L) { value, index -> value or ((bytes[offset + index].toLong() and 255) shl (index * 8)) }
    private fun invalid(): Nothing = throw PublicationImportException(ImportProblem.INVALID_CONTENT)
    private fun resourceLimit(): Nothing = throw PublicationImportException(ImportProblem.RESOURCE_LIMIT)
}

data class BookMetadata(val title: String, val author: String, val description: String?)

object MetadataFallback {
    fun resolve(
        title: String?,
        authors: List<String>,
        description: String?,
        fileName: String,
    ): BookMetadata {
        val displayName = fileName.substringAfterLast('/').substringAfterLast('\\')
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val stem = if (extension == "epub" || extension == "pdf") {
            displayName.substringBeforeLast('.')
        } else {
            displayName
        }
        return BookMetadata(
            title = title?.trim()?.takeIf(String::isNotEmpty)
                ?: stem.trim().takeIf(String::isNotEmpty)
                ?: "Untitled book",
            author = authors.map(String::trim).filter(String::isNotEmpty)
                .joinToString(", ").ifEmpty { "Unknown author" },
            description = description?.trim()?.takeIf(String::isNotEmpty),
        )
    }
}
