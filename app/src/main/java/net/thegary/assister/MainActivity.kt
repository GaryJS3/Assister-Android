package net.thegary.assister

import android.Manifest
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import net.thegary.assister.updates.UpdateViewModel
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import net.thegary.assister.interaction.ConversationViewModel
import net.thegary.assister.ui.ConversationScreen
import net.thegary.assister.ui.theme.AssisterTheme

class MainActivity : ComponentActivity() {
    private lateinit var model: ConversationViewModel
    private lateinit var updates: UpdateViewModel
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.startRecording() else model.permissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = (application as AssisterApplication).conversation
        updates = ViewModelProvider(this)[UpdateViewModel::class.java]
        enableEdgeToEdge()
        setContent { AssisterTheme { ConversationScreen(model, ::recordVoice, updates, ::installUpdate) } }
    }

    private fun installUpdate() {
        try {
            if (!packageManager.canRequestPackageInstalls()) {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", updates.apk)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { updates.installError(e.message ?: "Cannot open Android installer") }
    }

    private fun recordVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            model.startRecording()
        } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onStart() {
        super.onStart()
        (application as AssisterApplication).visible("conversation", true)
    }

    override fun onStop() {
        if (!isChangingConfigurations) (application as AssisterApplication).visible("conversation", false)
        super.onStop()
    }

}
