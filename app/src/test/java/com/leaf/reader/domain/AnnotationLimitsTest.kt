package com.leaf.reader.domain

import com.leaf.reader.domain.model.AnnotationLimits
import org.junit.Assert.*
import org.junit.Test

class AnnotationLimitsTest {
    @Test fun exactAsciiLimitsAreAcceptedAndNextByteIsRejected() {
        AnnotationLimits.validateNote("n".repeat(64 * 1024))
        AnnotationLimits.validateSelection("s".repeat(64 * 1024))
        AnnotationLimits.validateLocator("l".repeat(256 * 1024))
        reject { AnnotationLimits.validateNote("n".repeat(64 * 1024 + 1)) }
        reject { AnnotationLimits.validateSelection("s".repeat(64 * 1024 + 1)) }
        reject { AnnotationLimits.validateLocator("l".repeat(256 * 1024 + 1)) }
    }
    @Test fun limitsMeasureUtf8BytesIncludingEmojiInsteadOfCharacterCount() {
        AnnotationLimits.validateNote("é".repeat(32 * 1024))
        AnnotationLimits.validateSelection("🌿".repeat(16 * 1024))
        reject { AnnotationLimits.validateNote("é".repeat(32 * 1024) + "x") }
        reject { AnnotationLimits.validateSelection("🌿".repeat(16 * 1024) + "x") }
    }
    @Test fun labelsAreBoundedAndOptionalNotesRemainOptional() {
        AnnotationLimits.validateNote(null)
        AnnotationLimits.validateNote("")
        AnnotationLimits.validateLabel(null)
        AnnotationLimits.validateLabel("b".repeat(4 * 1024))
        reject { AnnotationLimits.validateLabel("b".repeat(4 * 1024 + 1)) }
    }
    private fun reject(block: () -> Unit) {
        try { block(); fail("An oversized annotation must not reach the database") }
        catch (error: IllegalArgumentException) { assertFalse(error.message.isNullOrBlank()) }
    }
}
