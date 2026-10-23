package fr.hybridcoach

import fr.hybridcoach.garmin.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class GarminHttpTransportTest {
    private class RecordingConnection :
        HttpURLConnection(URL("https://personal.connectapi.garmin.com")) {
        var closed = false
        var openedOutput = false
        var errorBodyRead = false
        var status = 200
        val output = ByteArrayOutputStream()

        override fun connect() {}

        override fun disconnect() {
            closed = true
        }

        override fun usingProxy() = false

        override fun getResponseCode() = status

        override fun getInputStream(): InputStream =
            ByteArrayInputStream("synthetic-access-token".toByteArray())

        override fun getErrorStream(): InputStream {
            errorBodyRead = true
            return ByteArrayInputStream("private-error".toByteArray())
        }

        override fun getOutputStream(): ByteArrayOutputStream {
            openedOutput = true
            return output
        }
    }

    @Test
    fun tokenRequestAllowsTextAndJsonAndSendsAnEmptyAuthenticatedPost() {
        val connection = RecordingConnection()
        val transport = GarminHttpsTransport { uri ->
            assertEquals("https", uri.scheme)
            assertEquals("personal.connectapi.garmin.com", uri.host)
            assertNull(uri.userInfo)
            connection
        }
        val response =
            transport.execute(
                GarminHttpRequest(
                    "POST",
                    "/personalapitoken-service/personal-api/personal-api-key/token",
                    "ApiKey synthetic-key",
                )
            )
        assertEquals(200, response.status)
        assertEquals("synthetic-access-token", response.body)
        assertEquals("POST", connection.requestMethod)
        assertEquals("*/*", connection.getRequestProperty("Accept"))
        assertEquals("ApiKey synthetic-key", connection.getRequestProperty("Authorization"))
        assertTrue(connection.getRequestProperty("User-Agent").startsWith("HybridCoach/${BuildConfig.VERSION_NAME}"))
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertTrue(connection.openedOutput)
        assertEquals(0, connection.output.size())
        assertTrue(connection.closed)
    }

    @Test
    fun failedResponsesNeverExposeOrReadTheirRawErrorBody() {
        val connection = RecordingConnection().apply { status = 406 }
        val response =
            GarminHttpsTransport { connection }
                .execute(
                    GarminHttpRequest(
                        "GET",
                        "/userprofile-service/userprofile/settings",
                        "Bearer synthetic-token",
                    )
                )
        assertEquals(406, response.status)
        assertEquals("", response.body)
        assertFalse(connection.errorBodyRead)
        assertTrue(connection.closed)
    }
}
