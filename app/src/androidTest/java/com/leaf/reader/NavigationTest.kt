package com.leaf.reader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun mainDestinationsAreReachable() {
        for (name in listOf("Shelves", "Activity", "Settings", "Library")) {
            compose.onNodeWithTag("nav-$name").performClick().assertIsSelected()
        }
        compose.onNodeWithText("Leaf").assertIsDisplayed()
    }
}
