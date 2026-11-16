package kg.devtasks.app.data

import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kg.devtasks.app.core.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class ApiReply(val code: Int, val text: String, val etag: String? = null)

interface Wire : AutoCloseable {
    fun call(
        url: String,
        method: String = "GET",
        body: String? = null,
        version: String? = null,
        xml: Boolean = false,
    ): ApiReply

    override fun close() {}
}

class HttpWire(login: String, password: String) : Wire {
    private val auth = Credentials.basic(login, password, Charsets.UTF_8)
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

    override fun call(
        url: String,
        method: String,
        body: String?,
        version: String?,
        xml: Boolean,
    ): ApiReply {
        val request =
            Request.Builder()
                .url(url)
                .header("Authorization", auth)
                .header("Accept", if (xml) "application/xml" else "application/json")
        if (version != null) request.header("If-Match", version)
        request.method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.newCall(request.build()).execute().use { r ->
            val max = if (xml) 16 * 1024 * 1024 else if (r.isSuccessful) 4 * 1024 * 1024 else 8192
            val input = r.body?.byteStream()
            val bytes = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            input?.use {
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    if (bytes.size() + n > max) {
                        if (!r.isSuccessful) break
                        throw UserError("Слишком большой ответ 1С. Уменьшите страницу.")
                    }
                    bytes.write(buf, 0, n)
                }
            }
            return ApiReply(
                r.code,
                bytes.toString("UTF-8").removePrefix("\uFEFF"),
                r.header("ETag"),
            )
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }
}

fun ApiReply.checked(): String {
    if (code in 200..299) return text
    val details =
        runCatching {
                Json.obj(text)["odata.error"].row()["message"].row()["value"].text().take(1500)
            }
            .getOrDefault("")
    val label =
        when (code) {
            401 -> "Неверный логин или пароль пользователя 1С."
            403 -> "Нет прав на чтение или изменение задач."
            404 -> "Задача или объект не найдены в 1С."
            412 -> "Задача изменена другим пользователем. Обновите данные."
            in 300..399 -> "Укажите конечный адрес OData: сервер перенаправляет запрос."
            else -> "Ошибка 1С: HTTP $code."
        }
    throw UserError(listOf(label, details).filter { it.isNotBlank() }.joinToString("\n"))
}

fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("'", "%27")

class TaskApi(val url: String, val mapping: Mapping, val sourceId: String, private val wire: Wire) :
    AutoCloseable {
    companion object {
        fun metadata(url: String, wire: Wire): MetadataModel =
            MetadataModel(wire.call(url + "\$metadata", xml = true).checked())

        fun validate(model: MetadataModel, m: Mapping) {
            m.validate()
            val type =
                model.sets[m.collection] ?: throw UserError("Коллекция отсутствует в метаданных.")
            if (
                model.property(type, "Ref_Key") != "Edm.Guid" ||
                    model.property(type, "DataVersion") != "Edm.String"
            )
                throw UserError(
                    "Объект должен иметь Ref_Key и DataVersion для безопасного изменения."
                )
            listOf(m.title, m.comment).forEach {
                if (model.property(type, it) != "Edm.String")
                    throw UserError("Поле $it должно быть строкой.")
            }
            val statusType = if (m.guidStatus) "Edm.Guid" else "Edm.String"
            if (model.property(type, m.status) != statusType)
                throw UserError("Неверный тип поля статуса.")
            if (m.description.isNotBlank() && model.property(type, m.description) != "Edm.String")
                throw UserError("Описание должно быть строковым реквизитом.")
            if (m.programmer.isNotBlank() && model.property(type, m.programmer) != "Edm.String")
                throw UserError("Нет строкового реквизита Программист.")
            val values =
                model.enums[m.enumName]
                    ?: throw UserError(
                        "Перечисление ${m.enumName} не опубликовано в метаданных OData."
                    )
            if (
                listOf(m.newValue, m.activeValue, m.doneValue, m.canceledValue).any {
                    it !in values
                }
            )
                throw UserError("Значения перечисления в этой базе отличаются от ожидаемых.")
        }
    }

    private fun endpoint(parameters: Map<String, String>): String {
        require(IDENTIFIER.matches(mapping.collection))
        return url +
            enc(mapping.collection) +
            parameters.entries.joinToString("&", if (parameters.isEmpty()) "" else "?") {
                "${it.key}=${enc(it.value)}"
            }
    }

    private fun rows(parameters: Map<String, String>): List<Row> {
        val parsed =
            runCatching { Json.obj(wire.call(endpoint(parameters)).checked()) }
                .getOrElse {
                    if (it is UserError) throw it
                    throw UserError("Вместо данных задач 1С вернула некорректный JSON.")
                }
        val list = parsed["value"]
        if (list !is List<*> || list.any { it !is Map<*, *> })
            throw UserError("В ответе нет списка value.")
        val result = list.map { it.row() }
        if (result.any { !GUID.matches(it["Ref_Key"].text()) })
            throw UserError("У задачи нет корректного Ref_Key.")
        return result
    }

    fun page(skip: Int = 0) =
        rows(
            mapOf(
                "\$format" to "json",
                "\$top" to "50",
                "\$skip" to "$skip",
                "\$orderby" to "Date desc,Ref_Key",
            )
        )

    fun read(key: String): TaskItem? {
        require(GUID.matches(key))
        val result =
            rows(mapOf("\$format" to "json", "\$top" to "2", "\$filter" to "Ref_Key eq guid'$key'"))
        if (result.size > 1 || result.any { !it["Ref_Key"].text().equals(key, true) })
            throw UserError("1С вернула неверный ключ задачи.")
        return result.singleOrNull()?.let { TaskItem(sourceId, key, it) }
    }

    fun batch(keys: List<String>): List<Row> {
        require(keys.size <= 20 && keys.all { GUID.matches(it) })
        if (keys.isEmpty()) return emptyList()
        val result =
            rows(
                mapOf(
                    "\$format" to "json",
                    "\$top" to "50",
                    "\$filter" to keys.joinToString(" or ") { "Ref_Key eq guid'$it'" },
                )
            )
        val requested = keys.map { it.lowercase() }.toSet()
        val returned = result.map { it["Ref_Key"].text().lowercase() }
        if (returned.any { it !in requested } || returned.distinct().size != returned.size)
            throw UserError("1С вернула неверные ключи задач.")
        return result
    }

    fun prepare(snapshot: TaskItem, actor: User, phase: Phase, now: Long): Pair<TaskItem, Pending> {
        if (snapshot.sourceId != sourceId || snapshot.unavailable)
            throw UserError("Задача недоступна.")
        val fresh =
            read(snapshot.key) ?: throw UserError("Задача удалена или недоступна пользователю 1С.")
        if (snapshot.version.isBlank() || fresh.version.isBlank())
            throw UserError("1С не вернула версию DataVersion. Изменение заблокировано.")
        if (fresh.version != snapshot.version) throw Conflict(fresh)
        if (fresh.raw["DeletionMark"] == true) throw UserError("Задача помечена на удаление.")
        if (fresh.phase(mapping) == phase) throw UserError("У задачи уже этот статус.")
        val comment = auditComment(fresh.raw[mapping.comment].text(), actor.login, phase, now)
        return fresh to
            Pending(
                sourceId,
                snapshot.key,
                actor.id,
                phase,
                mapping.value(phase),
                comment,
                fresh.version,
                now,
                actor.login,
            )
    }

    fun apply(p: Pending): TaskItem {
        require(p.sourceId == sourceId && GUID.matches(p.key) && p.version.isNotBlank())
        val payload =
            mapOf(mapping.status to p.status, mapping.comment to p.comment) +
                (if (p.phase == Phase.ACTIVE && mapping.programmer.isNotBlank())
                    mapOf(mapping.programmer to p.login)
                else emptyMap())
        // Статус и комментарий сохраняются одной условной операцией. Автоповторов нет.
        val result =
            try {
                wire.call(
                    url + enc(mapping.collection + "(guid'${p.key}')"),
                    "PATCH",
                    Json.encode(payload),
                    p.version,
                )
            } catch (_: Exception) {
                throw UnknownOutcome()
            }
        if (result.code == 412) {
            val current = read(p.key) ?: throw UserError("Задача недоступна.")
            throw Conflict(current)
        }
        if (result.code >= 500 || result.code == 408) throw UnknownOutcome()
        result.checked()
        return TaskItem(sourceId, p.key, payload + mapOf("Ref_Key" to p.key))
    }

    fun matches(p: Pending, t: TaskItem): Boolean =
        t.key == p.key &&
            t.sourceId == p.sourceId &&
            t.raw[mapping.status].text() == p.status &&
            t.raw[mapping.comment].text() == p.comment &&
            t.version.isNotBlank() &&
            t.version != p.version

    override fun close() {
        wire.close()
    }
}
