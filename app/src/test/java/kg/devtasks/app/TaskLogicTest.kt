package kg.devtasks.app

import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.Base64
import kg.devtasks.app.core.*
import kg.devtasks.app.data.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

const val KEY = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
val actor = User("user-1", "dev.egor", "Егор")

fun sampleRow(version: String = "v1", status: String = "Свободно"): Row =
    mapOf(
        "Ref_Key" to KEY,
        "DataVersion" to version,
        "Description" to "Задача",
        "Описание" to "Описание задачи",
        "Статус" to status,
        "Программист" to "",
        "Комментарий" to "Важный комментарий\n  ",
        "Date" to "2026-11-16T10:00:00",
    )

val schema =
    """<Edmx><DataServices><Schema xmlns="urn:edm" Namespace="StandardODATA"><EntityType Name="Task_ТехЗаданияДляПрограммистов"><Key><PropertyRef Name="Ref_Key"/></Key><Property Name="Ref_Key" Type="Edm.Guid"/><Property Name="DataVersion" Type="Edm.String"/><Property Name="Description" Type="Edm.String"/><Property Name="Описание" Type="Edm.String"/><Property Name="Статус" Type="Edm.String"/><Property Name="Комментарий" Type="Edm.String"/><Property Name="Программист" Type="Edm.String"/></EntityType><EnumType Name="СтадияДоработокПрограммистовV2"><Member Name="ВПроцессе"/><Member Name="Выполнено"/><Member Name="Отменено"/><Member Name="Свободно"/></EnumType><EntityContainer Name="Data"><EntitySet Name="Task_ТехЗаданияДляПрограммистов" EntityType="StandardODATA.Task_ТехЗаданияДляПрограммистов"/></EntityContainer></Schema></DataServices></Edmx>"""

class FakeWire : Wire {
    var current = sampleRow()
    var patches = 0
    var failAfterWrite = false
    var replyCode = 204
    var lastBody: Row = emptyMap()
    var match: String? = null
    var requested = ""

    override fun call(
        url: String,
        method: String,
        body: String?,
        version: String?,
        xml: Boolean,
    ): ApiReply {
        requested = url
        if (xml) return ApiReply(200, schema)
        if (method == "GET") return ApiReply(200, Json.encode(mapOf("value" to listOf(current))))
        patches++
        lastBody = Json.obj(body!!)
        match = version
        if (replyCode == 412) return ApiReply(412, "")
        if (replyCode in 200..299) {
            current = current + lastBody + mapOf("DataVersion" to "v2")
            if (failAfterWrite) throw SocketTimeoutException()
        }
        return ApiReply(replyCode, "")
    }
}

fun api(wire: Wire) =
    TaskApi("http://example.test/base/odata/standard.odata/", programmerMapping(), "source-1", wire)

fun snapshot() = TaskItem("source-1", KEY, sampleRow())

class TaskLogicTest {
    @Test
    fun profileUrlNormalizedAndCredentialsNotInUrl() {
        assertEquals(
            "https://example.test/base/odata/standard.odata/",
            Source.normalizeUrl("https://EXAMPLE.test/base/odata/standard.odata"),
        )
        assertThrows(UserError::class.java) {
            Source.normalizeUrl("https://user:pass@example.test/base/odata/standard.odata/")
        }
        assertThrows(UserError::class.java) {
            Source.normalizeUrl("https://example.test/base/odata/standard.odata/?x=1")
        }
        assertThrows(UserError::class.java) { Source.normalizeUrl("file:///x") }
    }

    @Test
    fun metadataEnumerationsComeFromMembersNotOrdinalNumbers() {
        val m = MetadataModel(schema)
        assertEquals(
            listOf("ВПроцессе", "Выполнено", "Отменено", "Свободно"),
            m.enums["СтадияДоработокПрограммистовV2"],
        )
        TaskApi.validate(m, programmerMapping())
    }

    @Test
    fun missingEnumerationOrPropertyStopsWrites() {
        assertThrows(UserError::class.java) {
            TaskApi.validate(
                MetadataModel(schema.replace("Name=\"Комментарий\"", "Name=\"Other\"")),
                programmerMapping(),
            )
        }
        assertThrows(UserError::class.java) {
            TaskApi.validate(
                MetadataModel(
                    schema.replace("Member Name=\"ВПроцессе\"", "Member Name=\"Другое\"")
                ),
                programmerMapping(),
            )
        }
    }

    @Test
    fun metadataRejectsExternalXmlEntities() {
        assertThrows(UserError::class.java) {
            MetadataModel("<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///secret'>]><x/>")
        }
    }

    @Test
    fun allFourStatusesMapToCorrectLabels() {
        val m = programmerMapping()
        assertEquals(Phase.NEW, m.phase("Свободно"))
        assertEquals(Phase.ACTIVE, m.phase("ВПроцессе"))
        assertEquals(Phase.DONE, m.phase("Выполнено"))
        assertEquals(Phase.CANCELED, m.phase("Отменено"))
        assertEquals(Phase.OTHER, m.phase("unknown"))
    }

    @Test
    fun duplicateGuidInDifferentBasesIsNotSameTask() {
        val a = snapshot()
        val b = a.copy(sourceId = "source-2")
        assertNotEquals(a.id, b.id)
        assertEquals(a.id, TaskItem.from(Json.obj(Json.encode(a.json()))).id)
    }

    @Test
    fun claimPreservesCommentAndUsesApplicationLogin() {
        val wire = FakeWire()
        val client = api(wire)
        val (_, p) = client.prepare(snapshot(), actor, Phase.ACTIVE, 123456789L)
        assertTrue(p.comment.startsWith(sampleRow()["Комментарий"].text()))
        assertTrue(p.comment.contains("В процессе: dev.egor"))
        client.apply(p)
        assertEquals("ВПроцессе", wire.lastBody["Статус"])
        assertEquals("dev.egor", wire.lastBody["Программист"])
        assertEquals(p.comment, wire.lastBody["Комментарий"])
        assertEquals("v1", wire.match)
        assertEquals(1, wire.patches)
    }

    @Test
    fun statusAndCommentAreOnePatchAndNoSystemTaskCompletionFlag() {
        val wire = FakeWire()
        val client = api(wire)
        val p = client.prepare(snapshot(), actor, Phase.DONE, 123L).second
        client.apply(p)
        assertEquals(setOf("Статус", "Комментарий"), wire.lastBody.keys)
        assertFalse(wire.lastBody.containsKey("Executed"))
        assertFalse(wire.lastBody.containsKey("ДатаВыполненияДоработки"))
        assertEquals(1, wire.patches)
    }

    @Test
    fun changedSnapshotRefusesPatch() {
        val wire = FakeWire().apply { current = sampleRow("v99") }
        assertThrows(Conflict::class.java) {
            api(wire).prepare(snapshot(), actor, Phase.ACTIVE, 1000)
        }
        assertEquals(0, wire.patches)
    }

    @Test
    fun emptyVersionRefusesPatch() {
        val wire = FakeWire().apply { current = sampleRow("") }
        assertThrows(UserError::class.java) {
            api(wire).prepare(snapshot(), actor, Phase.ACTIVE, 1000)
        }
        assertEquals(0, wire.patches)
    }

    @Test
    fun alreadyInProgressDoesNotAppendCommentAgain() {
        val wire = FakeWire().apply { current = sampleRow(status = "ВПроцессе") }
        val t = snapshot().copy(raw = wire.current)
        assertThrows(UserError::class.java) { api(wire).prepare(t, actor, Phase.ACTIVE, 1000) }
        assertEquals(0, wire.patches)
    }

    @Test
    fun timeoutNeverRepeatsPatchAndCanBeVerified() {
        val wire = FakeWire().apply { failAfterWrite = true }
        val client = api(wire)
        val p = client.prepare(snapshot(), actor, Phase.ACTIVE, 1000).second
        assertThrows(UnknownOutcome::class.java) { client.apply(p) }
        assertEquals(1, wire.patches)
        assertTrue(client.matches(p, client.read(KEY)!!))
        assertEquals(1, wire.patches)
    }

    @Test
    fun unknownServerFailurePreservesUncertainty() {
        val wire = FakeWire().apply { replyCode = 500 }
        val client = api(wire)
        val p = client.prepare(snapshot(), actor, Phase.ACTIVE, 1000).second
        assertThrows(UnknownOutcome::class.java) { client.apply(p) }
        assertEquals(1, wire.patches)
    }

    @Test
    fun conditionalConflictReturnsFreshTask() {
        val wire = FakeWire()
        val client = api(wire)
        val p = client.prepare(snapshot(), actor, Phase.ACTIVE, 1000).second
        wire.replyCode = 412
        wire.current = sampleRow("v9", "Выполнено")
        val e = assertThrows(Conflict::class.java) { client.apply(p) }
        assertEquals("v9", e.latest.version)
        assertEquals(1, wire.patches)
    }

    @Test
    fun exactGuidAndEncodedQuotesUsedForReading() {
        val wire = FakeWire()
        api(wire).read(KEY)
        assertTrue(wire.requested.contains("%27"))
        val decoded = URLDecoder.decode(wire.requested, "UTF-8")
        assertTrue(decoded.contains("Ref_Key eq guid'$KEY'"))
        assertFalse(decoded.contains("substringof"))
    }

    @Test
    fun ignoredServerFilterDoesNotUpdateWrongTask() {
        val wire =
            FakeWire().apply {
                current = sampleRow() + mapOf("Ref_Key" to "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
            }
        assertThrows(UserError::class.java) { api(wire).read(KEY) }
        assertEquals(0, wire.patches)
    }

    @Test
    fun pendingSurvivesSerialization() {
        val p = api(FakeWire()).prepare(snapshot(), actor, Phase.ACTIVE, 1000).second
        assertEquals(p, Pending.from(Json.obj(Json.encode(p.json()))))
    }

    @Test
    fun confirmationRequiresCorrectSourceAndChangedVersion() {
        val wire = FakeWire()
        val client = api(wire)
        val p = client.prepare(snapshot(), actor, Phase.DONE, 1000).second
        assertFalse(client.matches(p, snapshot()))
        client.apply(p)
        val actual = client.read(KEY)!!
        assertTrue(client.matches(p, actual))
        assertFalse(client.matches(p, actual.copy(sourceId = "other")))
    }

    @Test
    fun passwordProofIsSaltedAndRussianPasswordsWork() {
        val h = PasswordHash(1000)
        val a = h.create("сложный-пароль")
        val b = h.create("сложный-пароль")
        assertFalse(a.hash.contentEquals(b.hash))
        assertTrue(h.verify("сложный-пароль", a))
        assertFalse(h.verify("wrong", a))
        assertEquals(32, a.hash.size)
    }

    @Test
    fun actualHttpPatchHasIfMatchUtf8AuthAndOnlyIntendedFields() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(204))
        val m = programmerMapping()
        val wire = HttpWire("Пользователь1С", "пароль1С")
        val c = TaskApi(server.url("/base/odata/standard.odata/").toString(), m, "source-1", wire)
        val p =
            Pending(
                "source-1",
                KEY,
                "u",
                Phase.ACTIVE,
                "ВПроцессе",
                "В процессе: programmer",
                "AAAAAA==",
                1000,
                "programmer",
            )
        try {
            c.apply(p)
            val r = server.takeRequest()
            assertEquals("PATCH", r.method)
            assertEquals("AAAAAA==", r.getHeader("If-Match"))
            assertEquals(
                "Basic " +
                    Base64.getEncoder().encodeToString("Пользователь1С:пароль1С".toByteArray()),
                r.getHeader("Authorization"),
            )
            assertEquals("programmer", Json.obj(r.body.readUtf8())["Программист"])
            assertEquals(1, server.requestCount)
        } finally {
            c.close()
            server.shutdown()
        }
    }

    @Test
    fun redirectIsNotFollowed() {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setResponseCode(302).addHeader("Location", "http://127.0.0.1:9/foreign")
        )
        val wire = HttpWire("user", "pass")
        try {
            assertEquals(302, wire.call(server.url("/base").toString()).code)
            assertEquals(1, server.requestCount)
        } finally {
            wire.close()
            server.shutdown()
        }
    }
}
