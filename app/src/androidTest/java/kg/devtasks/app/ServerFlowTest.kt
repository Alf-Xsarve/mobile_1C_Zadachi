package kg.devtasks.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kg.devtasks.app.core.*
import kg.devtasks.app.data.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerFlowTest {
    private val inst
        get() = InstrumentationRegistry.getInstrumentation()

    private lateinit var server: MockWebServer
    private lateinit var client: ServerTasks
    private val taskId = UUID.randomUUID().toString()
    private val userId = UUID.randomUUID().toString()
    private val tasks = mutableListOf<Row>()
    private val links = mutableListOf<Row>()
    @Volatile private var offline = false
    @Volatile private var revoked = false
    private lateinit var scope: String
    private val profile
        get() = mapOf("id" to userId, "email" to "dev@example.test", "name" to "Разработчик")

    private val token = "t".repeat(43)

    private fun main(action: () -> Unit) = inst.runOnMainSync(action)

    private fun until(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            var ok = false
            main { ok = check() }
            if (ok) return
            Thread.sleep(50)
        }
        fail("Не дождались результата API")
    }

    @Before
    fun start() {
        scope = UUID.randomUUID().toString()
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse {
                    fun reply(row: Row, code: Int = 200) =
                        MockResponse()
                            .setResponseCode(code)
                            .setBody(Json.encode(row))
                            .setHeader("Content-Type", "application/json")
                    if (offline)
                        return reply(mapOf("code" to "offline", "message" to "Нет связи"), 503)
                    val path = r.requestUrl!!.encodedPath
                    if (path.endsWith("/register") || path.endsWith("/login"))
                        return reply(
                            mapOf(
                                "token" to token,
                                "expiresAt" to "2099-01-01T00:00:00Z",
                                "user" to profile,
                            )
                        )
                    if (revoked)
                        return reply(
                            mapOf("code" to "unauthorized", "message" to "Войдите снова"),
                            401,
                        )
                    if (r.getHeader("Authorization") != "Bearer $token")
                        return MockResponse().setResponseCode(401)
                    if (path.endsWith("/me")) return reply(profile)
                    if (path.endsWith("/logout")) return MockResponse().setResponseCode(204)
                    if (path.endsWith("/links")) {
                        if (r.method == "POST") {
                            links.addAll(
                                Json.obj(r.body.readUtf8())["items"].rows().map {
                                    it + mapOf("authorId" to userId, "authorName" to "Разработчик")
                                }
                            )
                            return reply(mapOf("status" to "ok"))
                        }
                        return reply(mapOf("items" to links.toList()))
                    }
                    if (r.method == "GET")
                        return reply(mapOf("items" to tasks.toList(), "nextCursor" to null))
                    if (path.endsWith("/claim") || path.endsWith("/complete")) {
                        val t =
                            tasks.first() +
                                mapOf(
                                    "status" to if (path.endsWith("/claim")) "active" else "done",
                                    "assigneeId" to userId,
                                    "assigneeName" to "Разработчик",
                                )
                        tasks[0] = t
                        return reply(t)
                    }
                    val body = Json.obj(r.body.readUtf8())
                    val t =
                        body +
                            mapOf(
                                "status" to "new",
                                "authorId" to userId,
                                "authorName" to "Разработчик",
                                "assigneeId" to null,
                                "createdAt" to "2026-11-18T05:00:00Z",
                            )
                    tasks.add(t)
                    return reply(t, 201)
                }
            }
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        main {
            client =
                ServerTasks(
                    inst.targetContext,
                    {},
                    SharedApi(
                        server
                            .url("/")
                            .newBuilder()
                            .host("127.0.0.1")
                            .build()
                            .toString()
                            .trimEnd('/')
                    ),
                    scope,
                )
        }
    }

    @After
    fun stop() {
        main { client.close() }
        server.close()
    }

    private fun register() {
        main {
            client.authenticate(
                true,
                "dev@example.test",
                "long-password-123",
                "Разработчик",
                "long-password-123",
            )
        }
        until { client.account != null && !client.busy && !client.loading }
    }

    @Test
    fun registrationCreateContactsClaimCompleteAndLogout() {
        register()
        main {
            client.draft = CloudDraft(taskId, "Проверить печатную форму", "Описание")
            assertTrue(
                client.links.add(
                    taskId,
                    "Клиент",
                    "+996555123456",
                    "whatsapp",
                    "Разработчик",
                    false,
                )
            )
            client.create()
        }
        until {
            !client.busy &&
                !client.links.busy &&
                client.draft == null &&
                client.items.size == 1 &&
                !client.links.loading
        }
        main {
            assertEquals("https://wa.me/996555123456", client.links.items.single().url)
            client.change(client.items.single(), false)
        }
        until { !client.busy && !client.loading && client.items.single().status == "active" }
        main {
            client.filter = "mine"
            assertEquals(1, client.visible().size)
            client.change(client.items.single(), true)
        }
        until { !client.busy && !client.loading && client.items.single().status == "done" }
        main {
            client.signOut()
            assertNull(client.account)
            assertTrue(client.items.isEmpty())
        }
    }

    @Test
    fun encryptedSessionRestoresOfflineAndRevokedSessionRequiresLogin() {
        register()
        main {
            client.draft = CloudDraft(taskId, "Задача в кеше", "Описание")
            client.create()
        }
        until { !client.busy && !client.loading && client.items.size == 1 }
        offline = true
        main {
            client.close()
            client =
                ServerTasks(
                    inst.targetContext,
                    {},
                    SharedApi(
                        server
                            .url("/")
                            .newBuilder()
                            .host("127.0.0.1")
                            .build()
                            .toString()
                            .trimEnd('/')
                    ),
                    scope,
                )
            assertNotNull(client.account)
            assertEquals(1, client.items.size)
            assertTrue(client.fromCache)
        }
        until { !client.busy && client.error != null }
        main {
            assertTrue(client.ready)
            assertEquals("Задача в кеше", client.items.single().title)
        }
        offline = false
        revoked = true
        main { client.listen() }
        until { client.account == null }
        main {
            assertFalse(client.ready)
            assertTrue(client.items.isEmpty())
        }
    }
}
