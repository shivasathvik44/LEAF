package com.leaf.reader.domain

import com.leaf.reader.domain.model.ShelfLimits
import org.junit.Assert.*
import org.junit.Test

class ShelfLimitsTest {
    @Test fun nameAndDescriptionLimitsAcceptExactValuesWithoutTruncation() {
        ShelfLimits.validate("n".repeat(100), "d".repeat(2_000))
        reject { ShelfLimits.validate("n".repeat(101), "") }
        reject { ShelfLimits.validate("Name", "d".repeat(2_001)) }
    }

    @Test fun blankNamesAreRejectedAndSurroundingWhitespaceIsNotCountedAsTheName() {
        reject { ShelfLimits.validate(" \n\t", "Description") }
        ShelfLimits.validate("  ${"n".repeat(100)}  ", "")
        assertFalse(ShelfLimits.validName("   "))
    }

    @Test fun unicodeCodePointsAreCountedConsistentlyIncludingSupplementaryCharacters() {
        val leaves = "🌿".repeat(100)
        assertEquals(100, ShelfLimits.characters(leaves))
        ShelfLimits.validate(leaves, "🌿".repeat(2_000))
        assertFalse(ShelfLimits.validName(leaves + "x"))
        assertFalse(ShelfLimits.validDescription("🌿".repeat(2_000) + "x"))
    }

    private fun reject(action: () -> Unit) {
        try { action(); fail("Invalid shelf metadata must not reach storage") }
        catch (failure: IllegalArgumentException) { assertFalse(failure.message.isNullOrBlank()) }
    }
}
