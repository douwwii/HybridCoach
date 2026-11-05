package fr.hybridcoach

import fr.hybridcoach.updates.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class UpdateTransportTest {
    private class Connection(
        uri: URI,
        val status: Int = 200,
        val body: ByteArray = "ok".toByteArray(),
        val location: String? = null,
        val length: Long = -1,
    ) : HttpURLConnection(uri.toURL()) {
        var closed = false

        override fun connect() {}

        override fun disconnect() {
            closed = true
        }

        override fun usingProxy() = false

        override fun getResponseCode() = status

        override fun getInputStream() = ByteArrayInputStream(body)

        override fun getContentLengthLong() = length

        override fun getHeaderField(name: String?) = if (name == "Location") location else null
    }

    @Test
    fun headersAndConnectionOptionsAreActuallyApplied() {
        lateinit var connection: Connection
        val out = ByteArrayOutputStream()
        GitHubUpdateTransport { uri -> Connection(uri).also { connection = it } }
            .transfer(LATEST_RELEASE_API, 100, out) {}
        assertEquals("ok", out.toString())
        assertEquals("GET", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals("application/vnd.github+json", connection.getRequestProperty("Accept"))
        assertEquals(
            "HybridCoach/${BuildConfig.VERSION_NAME}",
            connection.getRequestProperty("User-Agent"),
        )
        assertNull(connection.getRequestProperty("Authorization"))
        assertTrue(connection.closed)
    }

    @Test
    fun signedGithubAssetRedirectIsSupportedAndEveryConnectionCloses() {
        val connections = mutableListOf<Connection>()
        val out = ByteArrayOutputStream()
        GitHubUpdateTransport { uri ->
                Connection(
                        uri,
                        status = if (connections.isEmpty()) 302 else 200,
                        location =
                            "https://release-assets.githubusercontent.com/public/asset?signature=test",
                    )
                    .also { connections += it }
            }
            .transfer(UpdateFixtures.manifest().apk.url, 100, out) {}
        assertEquals(2, connections.size)
        assertTrue(connections.all { it.closed })
        assertEquals("ok", out.toString())
    }

    @Test
    fun httpAndForeignRedirectsAreRejectedBeforeConnectingToThem() {
        for (location in
            listOf(
                "http://github.com/file",
                "https://attacker.test/file",
                "https://github.com:444/file",
                "https://user@github.com/file",
            )) {
            var opened = 0
            assertThrows(IllegalArgumentException::class.java) {
                GitHubUpdateTransport { uri ->
                        opened++
                        Connection(uri, 302, location = location)
                    }
                    .transfer(UpdateFixtures.manifest().apk.url, 100, ByteArrayOutputStream()) {}
            }
            assertEquals(1, opened)
        }
    }

    @Test
    fun oversizedResponsesAreRejectedWithAndWithoutContentLength() {
        listOf(101L, -1L).forEach { declared ->
            lateinit var c: Connection
            assertThrows(IllegalArgumentException::class.java) {
                GitHubUpdateTransport { uri ->
                        Connection(uri, body = ByteArray(101), length = declared).also { c = it }
                    }
                    .transfer(LATEST_RELEASE_API, 100, ByteArrayOutputStream()) {}
            }
            assertTrue(c.closed)
        }
    }

    @Test
    fun redirectLoopsAndHttpErrorsFailWithoutReturningPartialJson() {
        var count = 0
        assertThrows(IllegalStateException::class.java) {
            GitHubUpdateTransport { uri ->
                    count++
                    Connection(uri, 302, location = "https://github.com/loop")
                }
                .transfer(UpdateFixtures.manifest().apk.url, 100, ByteArrayOutputStream()) {}
        }
        assertEquals(6, count)
        assertThrows(UpdateHttpException::class.java) {
            GitHubUpdateTransport { uri -> Connection(uri, 403) }
                .transfer(LATEST_RELEASE_API, 100, ByteArrayOutputStream()) {}
        }
    }
}
