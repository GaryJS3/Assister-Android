package net.thegary.assister

import java.io.File
import java.security.MessageDigest
import net.thegary.assister.updates.UpdateClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class UpdateClientTest {
    @Test fun numeric_versions_compare_without_lexical_order_or_downgrades() {
        assertTrue(UpdateClient.compareVersions("1.10", "1.9") > 0)
        assertEquals(0, UpdateClient.compareVersions("1.0", "1.0.0.0"))
        assertTrue(UpdateClient.compareVersions("1.0.0.1", "1.0") > 0)
        assertThrows(IllegalArgumentException::class.java) { UpdateClient.compareVersions("1.0-beta", "1.0") }
    }
    @Test fun check_and_download_are_public_and_verify_bytes() {
        MockWebServer().use { server ->
            server.start()
            val bytes = "APK test bytes".toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            server.enqueue(MockResponse().setBody(metadata(bytes.size, hash)))
            server.enqueue(MockResponse().setBody("APK test bytes"))
            val api = client(server)
            val release = api.check("1.0")!!
            val folder = java.nio.file.Files.createTempDirectory("update-test").toFile()
            try {
                val file = File(folder, "update.apk")
                api.download(release, file)
                assertArrayEquals(bytes, file.readBytes())
                val check = server.takeRequest()
                assertEquals("/api/updates/assister/android/check?currentVersion=1.0", check.path)
                assertNull(check.getHeader("Authorization"))
                assertNull(server.takeRequest().getHeader("Authorization"))
            } finally { folder.deleteRecursively() }
        }
    }
    @Test fun corrupt_download_preserves_existing_file_and_removes_partial_file() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(metadata(3, "0".repeat(64))))
            server.enqueue(MockResponse().setBody("bad"))
            val api = client(server)
            val release = api.check("1.0")!!
            val folder = java.nio.file.Files.createTempDirectory("update-test").toFile()
            try {
                val file = File(folder, "update.apk").apply { writeText("existing") }
                assertThrows(IllegalArgumentException::class.java) { api.download(release, file) }
                assertEquals("existing", file.readText())
                assertFalse(File(folder, "download.part").exists())
            } finally { folder.deleteRecursively() }
        }
    }
    @Test fun check_rejects_cross_host_download_and_older_version() {
        MockWebServer().use { server ->
            server.start()
            val api = client(server)
            server.enqueue(MockResponse().setBody(metadata(3, "0".repeat(64), "https://other.example/update.apk")))
            assertThrows(IllegalArgumentException::class.java) { api.check("1.0") }
            server.enqueue(MockResponse().setBody(metadata(3, "0".repeat(64)).replace("1.1", "0.9")))
            assertThrows(IllegalArgumentException::class.java) { api.check("1.0") }
            server.enqueue(MockResponse().setBody("""{"updateAvailable":false,"release":null}"""))
            assertNull(api.check("1.0"))
        }
    }
    private fun client(server: MockWebServer) = UpdateClient("http://10.44.0.33:${server.port}",
        OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(chain.request().url.newBuilder().host("localhost").build()).build()) }.build())
    private fun metadata(size: Int, hash: String, url: String = "/api/updates/assister/android/1.1/download") =
        """{"updateAvailable":true,"release":{"appId":"assister","platform":"android","version":"1.1","size":$size,"sha256":"$hash","downloadUrl":"$url"}}"""
}
