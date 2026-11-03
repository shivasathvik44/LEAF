package com.leaf.reader

import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Verifies the real system picker launch; content import is covered by separate repository tests. */
class LibraryPickerTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun importOpensSystemDocumentsAndCancellationReturnsToLibrary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("*/*")
        }
        // Non-blocking monitor observes the request without replacing the system picker.
        val monitor = Instrumentation.ActivityMonitor(filter, null, false)
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithTag("import-books").assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                monitor.hits > 0 && systemDocumentsVisible(instrumentation)
            }
            assertEquals("One tap must launch one document request", 1, monitor.hits)
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            compose.waitUntil(timeoutMillis = 10_000) { !systemDocumentsVisible(instrumentation) }
            compose.waitForIdle()
            compose.onNodeWithText("Leaf").assertIsDisplayed()
            compose.onNodeWithTag("import-books").assertIsEnabled()
        } finally {
            instrumentation.removeMonitor(monitor)
            if (systemDocumentsVisible(instrumentation)) {
                instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun systemDocumentsVisible(instrumentation: Instrumentation): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        return try {
            root.packageName?.toString().orEmpty().endsWith(".documentsui")
        } finally {
            root.recycle()
        }
    }
}
