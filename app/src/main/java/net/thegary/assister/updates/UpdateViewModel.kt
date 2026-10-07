package net.thegary.assister.updates

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.thegary.assister.BuildConfig
import java.io.File
import android.content.pm.PackageManager

data class UpdateState(val busy: Boolean = false, val checked: Boolean = false,
    val release: UpdateRelease? = null, val progress: Int? = null, val ready: Boolean = false,
    val error: String? = null, val endpoint: String = "")

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    val apk = File(application.cacheDir, "updates/Assister.apk")
    fun check(endpoint: String) {
        if (state.value.busy) return
        mutable.value = UpdateState(busy = true, endpoint = endpoint)
        viewModelScope.launch {
            try {
                val release = withContext(Dispatchers.IO) { UpdateClient(endpoint).check(BuildConfig.VERSION_NAME) }
                mutable.value = state.value.copy(busy = false, checked = true, release = release)
            } catch (e: Exception) { mutable.value = state.value.copy(busy = false, error = e.message) }
        }
    }
    fun download() {
        val snapshot = state.value
        val release = snapshot.release ?: return
        if (snapshot.busy) return
        mutable.value = snapshot.copy(busy = true, progress = 0, error = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    UpdateClient(snapshot.endpoint).download(release, apk) { percent ->
                        mutable.value = state.value.copy(progress = percent)
                    }
                    validatePackage(release)
                }
                mutable.value = state.value.copy(busy = false, ready = true, progress = null)
            } catch (e: Exception) {
                apk.delete()
                mutable.value = state.value.copy(busy = false, progress = null, error = e.message)
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun validatePackage(release: UpdateRelease) {
        val manager = getApplication<Application>().packageManager
        val candidate = manager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
            ?: error("Invalid Android package")
        val installed = manager.getPackageInfo(BuildConfig.APPLICATION_ID, PackageManager.GET_SIGNATURES)
        require(candidate.packageName == BuildConfig.APPLICATION_ID &&
            UpdateClient.compareVersions(candidate.versionName ?: "", release.version) == 0 &&
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(candidate) > androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(installed)) { "Update identity or build number is incompatible" }
        require(!candidate.signatures.isNullOrEmpty() && candidate.signatures!!.toSet() == installed.signatures!!.toSet()) {
            "Update signing key does not match this installation"
        }
    }
    fun installError(message: String) { mutable.value = state.value.copy(error = message) }
}
