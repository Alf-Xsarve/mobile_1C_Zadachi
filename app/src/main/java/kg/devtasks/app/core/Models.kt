package kg.devtasks.app.core

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

val GUID = Regex("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$")
val IDENTIFIER = Regex("^[\\p{L}_][\\p{L}\\p{N}_]*$")

class UserError(message: String) : Exception(message)

class Conflict(val latest: TaskItem) :
    Exception("Задача изменилась в 1С. Данные обновлены; проверьте их перед повторным действием.")

class UnknownOutcome :
    Exception(
        "Ответ 1С не получен. Изменение могло сохраниться. Обновите задачу для проверки; повторная отправка пока заблокирована."
    )

enum class Phase(val label: String) {
    NEW("Свободно"),
    ACTIVE("В процессе"),
    DONE("Выполнено"),
    CANCELED("Отменено"),
    OTHER("Другой статус"),
}

data class User(val id: String, val login: String, val name: String)

// Пароль 1С не попадает в автоматически создаваемый toString.
class Source(
    val id: String,
    val owner: String,
    val name: String,
    val url: String,
    val login: String,
    val password: String,
    val mapping: Mapping,
    val enabled: Boolean = true,
) {
    fun json(): Row =
        mapOf(
            "id" to id,
            "owner" to owner,
            "name" to name,
            "url" to url,
            "login" to login,
            "password" to password,
            "mapping" to mapping.json(),
            "enabled" to enabled,
        )

    val fingerprint: String
        get() = digest(url)

    companion object {
        fun normalizeUrl(raw: String): String {
            val s = raw.trim().let { if (it.endsWith("/odata/standard.odata")) "$it/" else it }
            val u = runCatching { URI(s) }.getOrNull()
            if (
                u == null ||
                    u.scheme?.lowercase() !in listOf("http", "https") ||
                    u.host.isNullOrBlank() ||
                    u.rawUserInfo != null ||
                    u.rawQuery != null ||
                    u.rawFragment != null ||
                    !u.path.endsWith("/odata/standard.odata/") ||
                    s.length > 2048
            )
                throw UserError("Нужен адрес вида https://server/base/odata/standard.odata/")
            return URI(u.scheme.lowercase(), null, u.host.lowercase(), u.port, u.path, null, null)
                .toASCIIString()
        }

        fun credentials(login: String, password: String) {
            if (login.isBlank() || ':' in login || '\n' in login || password.isEmpty())
                throw UserError("Введите логин и пароль пользователя 1С.")
        }

        fun from(r: Row) =
            Source(
                r["id"].text(),
                r["owner"].text(),
                r["name"].text(),
                r["url"].text(),
                r["login"].text(),
                r["password"].text(),
                Mapping.from(r["mapping"].row()),
                r["enabled"] != false,
            )
    }
}

data class Mapping(
    val collection: String,
    val title: String,
    val status: String,
    val comment: String,
    val description: String = "",
    val newValue: String,
    val activeValue: String,
    val doneValue: String,
    val guidStatus: Boolean = false,
    val programmer: String = "Программист",
    val enumName: String = "СтадияДоработокПрограммистовV2",
    val canceledValue: String = "Отменено",
) {
    fun json(): Row =
        mapOf(
            "collection" to collection,
            "title" to title,
            "status" to status,
            "comment" to comment,
            "description" to description,
            "newValue" to newValue,
            "activeValue" to activeValue,
            "doneValue" to doneValue,
            "guidStatus" to guidStatus,
            "programmer" to programmer,
            "enumName" to enumName,
            "canceledValue" to canceledValue,
        )

    fun phase(value: Any?): Phase =
        when (value.text()) {
            newValue -> Phase.NEW
            activeValue -> Phase.ACTIVE
            doneValue -> Phase.DONE
            canceledValue -> Phase.CANCELED
            else -> Phase.OTHER
        }

    fun value(phase: Phase) =
        when (phase) {
            Phase.NEW -> newValue
            Phase.ACTIVE -> activeValue
            Phase.DONE -> doneValue
            Phase.CANCELED -> canceledValue
            else -> throw UserError("Выберите известный статус.")
        }

    fun validate() {
        if (
            listOf(collection, title, status, comment).any { !IDENTIFIER.matches(it) } ||
                description.isNotEmpty() && !IDENTIFIER.matches(description)
        )
            throw UserError("Выберите поля из метаданных 1С.")
        if (setOf(title, status, comment).size != 3)
            throw UserError("Поля названия, статуса и комментария должны различаться.")
        if (
            listOf(newValue, activeValue, doneValue).any { it.isBlank() || it.length > 300 } ||
                setOf(newValue, activeValue, doneValue).size != 3
        )
            throw UserError("Нужны три различных значения статуса из 1С.")
        if (guidStatus && listOf(newValue, activeValue, doneValue).any { !GUID.matches(it) })
            throw UserError("Для ссылочного статуса нужны GUID элементов справочника.")
    }

    companion object {
        fun from(r: Row) =
            Mapping(
                r["collection"].text(),
                r["title"].text(),
                r["status"].text(),
                r["comment"].text(),
                r["description"].text(),
                r["newValue"].text(),
                r["activeValue"].text(),
                r["doneValue"].text(),
                r["guidStatus"] == true,
                r["programmer"].text().ifBlank { "Программист" },
                r["enumName"].text().ifBlank { "СтадияДоработокПрограммистовV2" },
                r["canceledValue"].text().ifBlank { "Отменено" },
            )
    }
}

data class TaskItem(
    val sourceId: String,
    val key: String,
    val raw: Row,
    val updated: Long = System.currentTimeMillis(),
    val taken: Boolean = false,
    val completed: Boolean = false,
    val unavailable: Boolean = false,
) {
    val id: String
        get() = "$sourceId:$key"

    fun title(m: Mapping) = raw[m.title].text().ifBlank { "Задача" }

    fun phase(m: Mapping) = m.phase(raw[m.status])

    val version: String
        get() = raw["DataVersion"].text()

    fun json(): Row =
        mapOf(
            "source" to sourceId,
            "key" to key,
            "raw" to raw,
            "updated" to updated,
            "taken" to taken,
            "completed" to completed,
            "unavailable" to unavailable,
        )

    companion object {
        fun from(r: Row) =
            TaskItem(
                r["source"].text(),
                r["key"].text(),
                r["raw"].row(),
                (r["updated"] as? Number)?.toLong() ?: 0,
                r["taken"] == true,
                r["completed"] == true,
                r["unavailable"] == true,
            )
    }
}

data class Pending(
    val sourceId: String,
    val key: String,
    val actorId: String,
    val phase: Phase,
    val status: String,
    val comment: String,
    val version: String,
    val created: Long,
    val login: String = "",
) {
    fun json(): Row =
        mapOf(
            "source" to sourceId,
            "key" to key,
            "actor" to actorId,
            "phase" to phase.name,
            "status" to status,
            "comment" to comment,
            "version" to version,
            "created" to created,
            "login" to login,
        )

    companion object {
        fun from(r: Row) =
            Pending(
                r["source"].text(),
                r["key"].text(),
                r["actor"].text(),
                Phase.valueOf(r["phase"].text()),
                r["status"].text(),
                r["comment"].text(),
                r["version"].text(),
                (r["created"] as Number).toLong(),
                r["login"].text(),
            )
    }
}

fun digest(text: String) =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
        "%02x".format(it)
    }

fun uid() = UUID.randomUUID().toString()

fun programmerMapping() =
    Mapping(
        "Task_ТехЗаданияДляПрограммистов",
        "Description",
        "Статус",
        "Комментарий",
        "Описание",
        "Свободно",
        "ВПроцессе",
        "Выполнено",
    )

fun timeText(time: Long) =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(time))

fun auditComment(previous: String, login: String, phase: Phase, time: Long): String {
    if (login.isBlank() || login.contains('\n')) throw UserError("Некорректный логин приложения.")
    val action =
        when (phase) {
            Phase.ACTIVE -> "В процессе"
            Phase.DONE -> "Выполнено"
            else -> phase.label
        }
    val stamp =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(time))
    return previous +
        (if (previous.isEmpty() || previous.endsWith("\n")) "" else "\n") +
        "$action: $login · $stamp"
}
