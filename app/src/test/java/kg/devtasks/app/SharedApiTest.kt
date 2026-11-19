package kg.devtasks.app

import kg.devtasks.app.core.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class SharedApiTest {
    @Test
    fun bearerAndJsonAndCursor() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setBody("""{"items":[],"nextCursor":null}"""))
            val api =
                SharedApi(
                    server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
                )
            val reply =
                api.request(
                    "GET",
                    "/api/tasks",
                    "test-token",
                    query = mapOf("cursor" to "a+b/c=", "limit" to "100"),
                )
            assertTrue(reply["items"].rows().isEmpty())
            val request = server.takeRequest()
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
            assertEquals("a+b/c=", request.requestUrl!!.queryParameter("cursor"))
        }
    }

    @Test
    fun conflictAndRevocationAndRedirectAreNotSuccess() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val api =
                SharedApi(
                    server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
                )
            for (code in listOf(401, 409, 503)) {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(code)
                        .setBody("""{"code":"test","message":"Ошибка"}""")
                )
                val error =
                    runCatching { api.request("POST", "/api/tasks/a/claim", "secret") }
                        .exceptionOrNull()
                assertTrue(error is SharedApiError)
                assertEquals(code, (error as SharedApiError).status)
                assertEquals("Ошибка", error.message)
            }
            server.enqueue(
                MockResponse().setResponseCode(302).addHeader("Location", "https://example.test/")
            )
            assertTrue(
                runCatching { api.request("GET", "/api/auth/me", "secret") }.exceptionOrNull()
                    is SharedApiError
            )
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun parsesServerNullAssigneeAndTimestamp() {
        val task =
            SharedApi.task(
                Json.obj(
                    """{"id":"abc","title":"Тест","status":"new","assigneeId":null,"assigneeName":null,"createdAt":"2026-11-18T05:00:00+00:00"}"""
                )
            )
        assertEquals("", task.assigneeId)
        assertTrue(task.canClaim("me"))
        assertTrue(task.createdAt > 0)
    }

    @Test
    fun apiOnlyAllowsHttpsExceptLoopbackFixtures() {
        assertTrue(runCatching { SharedApi("http://example.test") }.isFailure)
        assertTrue(runCatching { SharedApi("https://user:password@example.test") }.isFailure)
        assertTrue(runCatching { SharedApi("https://example.test/wrong") }.isFailure)
    }

    @Test
    fun uploadsAndDownloadsOriginalBytes() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val api = SharedApi(server.url("/").newBuilder().host("127.0.0.1").build().toString())
            val id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            val bytes = byteArrayOf(0, 1, 2, -1)
            val file = java.io.File.createTempFile("binary-test", ".bin")
            try {
                file.writeBytes(bytes)
                server.enqueue(MockResponse().setBody("{\"status\":\"ok\"}"))
                api.file(id, id, "secret", file, "Фото.png")
                val sent = server.takeRequest()
                assertEquals("PUT", sent.method)
                assertEquals("Bearer secret", sent.getHeader("Authorization"))
                assertEquals("Фото.png", sent.requestUrl!!.queryParameter("name"))
                assertArrayEquals(bytes, sent.body.readByteArray())
                server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
                assertArrayEquals(bytes, api.file(id, id, "secret"))
            } finally {
                file.delete()
            }
        }
    }
}
