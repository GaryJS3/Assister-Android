package net.thegary.assister

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import net.thegary.assister.ui.MarkdownText
import net.thegary.assister.ui.theme.AssisterTheme
import org.junit.Rule
import org.junit.Test

class MarkdownScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun headings_lists_tables_and_code_render_and_stream_updates() {
        val source = mutableStateOf("**Office")
        compose.setContent { AssisterTheme { Surface { Column(Modifier.fillMaxWidth().padding(24.dp)) { MarkdownText(source.value) } } } }
        compose.onNodeWithText("**Office").assertIsDisplayed()
        compose.runOnIdle { source.value = """
            ## Office lights

            **Current state**: *checked*

            - Yellow light: off
            - White light: on

            3. Check state
            4. Read brightness

            | Light | State | Brightness |
            | --- | --- | ---: |
            | Yellow | Off | 0% |
            | White | On | 1% |

            ```json
            {"state":"on"}
            ```
        """.trimIndent() }
        compose.onNodeWithText("Office lights").assertIsDisplayed()
        compose.onNodeWithText("Current state: checked").assertIsDisplayed()
        compose.onNodeWithText("Yellow light: off").assertIsDisplayed()
        compose.onNodeWithText("3.").assertIsDisplayed()
        compose.onNodeWithText("4.").assertIsDisplayed()
        compose.onNodeWithText("Light").assertIsDisplayed()
        compose.onNodeWithText("Yellow").assertIsDisplayed()
        compose.onNodeWithText("{\"state\":\"on\"}").assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "markdown-preview.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
