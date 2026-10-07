package net.thegary.assister.updates

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class UpdateRelease(val version: String, val size: Long, val sha256: String, val downloadUrl: HttpUrl)

/** Public update transport, deliberately independent of authenticated chat transport. */
class UpdateClient(endpoint: String, private val http: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).callTimeout(10, TimeUnit.MINUTES).build()) {
    private val base = endpoint.trim().trimEnd('/').toHttpUrl()
    init {
        require(base.scheme == "https" || base.host == "10.44.0.33") { "Use HTTPS or the permitted LAN server" }
        require(base.encodedPath == "/" && base.query == null && base.fragment == null &&
            base.username.isEmpty() && base.password.isEmpty()) { "Enter a server origin" }
    }
    fun check(currentVersion: String): UpdateRelease? {
        val url = base.newBuilder().encodedPath("/api/updates/assister/android/check")
            .addQueryParameter("currentVersion", currentVersion).build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "Update check failed (HTTP ${response.code})" }
            val json = JSONObject(response.body!!.string())
            if (!json.getBoolean("updateAvailable")) return null
            val release = json.getJSONObject("release")
            require(release.getString("appId") == "assister" && release.getString("platform") == "android")
            val version = release.getString("version")
            require(compareVersions(version, currentVersion) > 0) { "Server returned an older version" }
            val size = release.getLong("size")
            val hash = release.getString("sha256")
            require(size in 1..536870912 && hash.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid release metadata" }
            val download = base.resolve(release.getString("downloadUrl")) ?: error("Invalid download URL")
            require(download.scheme == base.scheme && download.host == base.host && download.port == base.port &&
                download.username.isEmpty() && download.password.isEmpty()) { "Download must use the configured server" }
            return UpdateRelease(version, size, hash, download)
        }
    }
    fun download(release: UpdateRelease, destination: File, progress: (Int) -> Unit = {}) {
        destination.parentFile!!.mkdirs()
        val temporary = File(destination.parentFile, "download.part")
        try {
            http.newCall(Request.Builder().url(release.downloadUrl).build()).execute().use { response ->
                check(response.isSuccessful) { "Download failed (HTTP ${response.code})" }
                val hash = MessageDigest.getInstance("SHA-256")
                var size = 0L
                response.body!!.byteStream().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            size += count
                            require(size <= release.size) { "Download exceeds expected size" }
                            hash.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            progress((size * 100 / release.size).toInt())
                        }
                    }
                }
                val digest = hash.digest().joinToString("") { "%02x".format(it) }
                require(size == release.size && digest.equals(release.sha256, true)) { "Update integrity check failed" }
                check(temporary.renameTo(destination)) { "Cannot save verified update" }
            }
        } finally { temporary.delete() }
    }
    companion object {
        fun compareVersions(first: String, second: String): Int {
            fun parts(value: String): List<Int> {
                require(value.matches(Regex("[0-9]+\\.[0-9]+(?:\\.[0-9]+){0,2}"))) { "Invalid numeric version" }
                return value.split('.').map { it.toInt() }.let { it + List(4 - it.size) { 0 } }
            }
            return parts(first).zip(parts(second)).firstOrNull { it.first != it.second }
                ?.let { it.first.compareTo(it.second) } ?: 0
        }
    }
}
