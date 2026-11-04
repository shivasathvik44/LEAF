package com.leaf.reader.domain.model

/** Keeps complete annotation rows comfortably below Android's SQLite CursorWindow limit. */
object AnnotationLimits {
    const val MAX_TEXT_BYTES = 64 * 1024
    const val MAX_NOTE_BYTES = 64 * 1024
    const val MAX_LOCATOR_BYTES = 256 * 1024
    const val MAX_LABEL_BYTES = 4 * 1024

    fun fitsNote(value: String): Boolean = fits(value, MAX_NOTE_BYTES)
    fun fitsText(value: String): Boolean = fits(value, MAX_TEXT_BYTES)
    fun fitsLocator(value: String): Boolean = fits(value, MAX_LOCATOR_BYTES)
    fun validateNote(value: String?) { require(value == null || fitsNote(value)) { "Notes can contain up to 64 KiB of text. Shorten this note before saving." } }
    fun validateSelection(value: String) { require(fitsText(value)) { "This passage is too long to highlight. Choose a shorter selection (up to 64 KiB)." } }
    fun validateLocator(value: String) { require(fitsLocator(value)) { "This saved location is too large. Choose a shorter passage or a nearby reading position." } }
    fun validateLabel(value: String?) { require(value == null || fits(value, MAX_LABEL_BYTES)) { "Bookmark labels can contain up to 4 KiB of text." } }
    private fun fits(value: String, maximum: Int): Boolean =
        value.length <= maximum && value.toByteArray(Charsets.UTF_8).size <= maximum
}
