package dev.njr.zync.ui

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.data.ZyncDatabase
import dev.njr.zync.domain.NodeRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavigationSmokeTest {
    @get:Rule val compose = createComposeRule()

    private fun repo(): NodeRepository =
        NodeRepository(ZyncDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>()))

    @Test
    fun `bottom bar navigates between the three screens`() {
        compose.setContent { ZyncNavHost(repository = repo()) }
        compose.onAllNodesWithText("Inbox").assertCountEquals(2) // tab label + placeholder title
        compose.onNodeWithText("Tree").performClick()
        compose.onNodeWithText("Folders").assertIsDisplayed()    // tree screen title
        compose.onNodeWithText("Contexts").performClick()
        compose.onNodeWithText("No contexts yet").assertIsDisplayed()
    }
}
