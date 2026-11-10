package com.leaf.reader.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry
import com.leaf.reader.MainActivity
import com.leaf.reader.data.readium.ReaderHarnessDependencies
import com.leaf.reader.domain.model.AppTheme
import com.leaf.reader.domain.model.ReaderTheme
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsPersistAcrossRecreationAndDisplayBundledNotices() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = EntryPointAccessors.fromApplication(context, ReaderHarnessDependencies::class.java).preferences()
        val original = preferences.preferences.first()
        try {
            preferences.update { it.copy(appTheme = AppTheme.DARK, listView = false, reader = it.reader.copy(theme = ReaderTheme.SEPIA)) }
            compose.onNodeWithTag("nav-Settings").performClick()
            compose.onNodeWithTag("settings-screen").assertIsDisplayed()
            compose.onNodeWithText("App theme").performScrollTo()
            awaitDisplayed(hasText("App theme") and hasText("Dark"))
            compose.onNodeWithText("Default reader theme").performScrollTo()
            awaitDisplayed(hasText("Default reader theme") and hasText("Sepia"))

            compose.onNodeWithText("App theme").performScrollTo().performClick()
            // Activate the displayed radio row using its accessibility action.
            compose.onNode(hasText("Light") and hasAnyAncestor(isDialog()))
                .assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.OnClick)
            awaitDisplayed(hasText("App theme") and hasText("Light"))
            assertEquals(AppTheme.LIGHT, withTimeout(10_000) { preferences.preferences.first { it.appTheme == AppTheme.LIGHT } }.appTheme)

            compose.onNodeWithText("Default reader theme").performScrollTo().performClick()
            compose.onNode(hasText("Dark") and hasAnyAncestor(isDialog()))
                .assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.OnClick)
            awaitDisplayed(hasText("Default reader theme") and hasText("Dark"))
            assertEquals(ReaderTheme.DARK, withTimeout(10_000) { preferences.preferences.first { it.reader.theme == ReaderTheme.DARK } }.reader.theme)
            compose.onNodeWithText("List view").performScrollTo().assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
            awaitDisplayed(hasText("List view") and isOn())
            assertTrue(withTimeout(10_000) { preferences.preferences.first { it.listView } }.listView)

            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("settings-screen").assertIsDisplayed()
            compose.onNodeWithText("List view").performScrollTo()
            awaitDisplayed(hasText("List view") and isOn())
            compose.onNodeWithText("List view").assertIsOn()
            compose.onNodeWithText("Open-source licenses").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("License index").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("License index").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Leaf third-party notices", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Leaf third-party notices", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Back to licenses").performClick()
            compose.onNodeWithText("Back to Settings").performClick()
            compose.onNodeWithTag("settings-screen").assertIsDisplayed()
        } finally {
            preferences.update { original }
        }
    }

    private fun awaitDisplayed(matcher: SemanticsMatcher) {
        compose.waitUntil(15_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1 }
        compose.onNode(matcher).assertIsDisplayed()
    }
}
