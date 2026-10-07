package net.thegary.assister

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.FileProvider
import net.thegary.assister.ui.UpdateOptions
import net.thegary.assister.ui.theme.AssisterTheme
import net.thegary.assister.updates.UpdateViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class UpdateOptionsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun settings_show_installed_version_and_check_action() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val model = UpdateViewModel(app)
        compose.setContent { AssisterTheme { UpdateOptions(model, "http://10.44.0.33:8081", {}) } }
        compose.onNodeWithText("Version ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}").assertIsDisplayed()
        compose.onNodeWithText("Check for updates").assertIsEnabled()
        compose.onNodeWithText("Download update").assertDoesNotExist()
    }
    @Test fun installer_provider_exposes_only_dedicated_update_directory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", File(context.cacheDir, "updates/Assister.apk"))
        assertEquals("content", uri.scheme)
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, "${context.packageName}.updates", File(context.cacheDir, "private.txt"))
        }
    }
}
