package kg.devtasks.app.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun webLink(raw: String): String {
    var value = raw.trim()
    if (value.length > 4096 || value.any { it == '\n' || it == '\r' })
        throw UserError("Укажите корректную ссылку HTTP или HTTPS.")
    if (!Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(value)) value = "https://$value"
    val url = value.toHttpUrlOrNull()
    if (url == null || url.username.isNotEmpty() || url.password.isNotEmpty())
        throw UserError("Укажите ссылку HTTP или HTTPS без логина и пароля в адресе.")
    return url.toString()
}

data class TaskLink(
    val id: String,
    val taskId: String,
    val name: String,
    val url: String,
    val kind: String,
    val authorId: String,
    val authorName: String,
) {
    val kindLabel
        get() =
            when (kind) {
                "file" -> "Файл"
                "image" -> "Изображение"
                "whatsapp" -> "WhatsApp"
                "telegram" -> "Telegram"
                else -> "Ссылка"
            }

    fun fields(): Row =
        mapOf(
            "name" to name,
            "url" to url,
            "kind" to kind,
            "authorId" to authorId,
            "authorName" to authorName,
        )

    fun validate() {
        if (
            !GUID.matches(id) ||
                taskId.isBlank() ||
                '/' in taskId ||
                authorId.isBlank() ||
                name.isBlank() ||
                name.length > 160 ||
                authorName.length !in 1..80 ||
                kind !in listOf("web", "file", "image", "whatsapp", "telegram")
        )
            throw UserError("Проверьте название и вид ссылки.")
        if (materialLink(kind, url) != url) throw UserError("Некорректный адрес ссылки.")
    }

    companion object {
        fun create(
            taskId: String,
            name: String,
            address: String,
            kind: String,
            uid: String,
            author: String,
        ): TaskLink {
            val url = materialLink(kind, address)
            val label = name.trim().ifBlank { materialDefaultName(kind, url) }
            return TaskLink(kg.devtasks.app.core.uid(), taskId, label, url, kind, uid, author)
                .also { it.validate() }
        }

        fun from(taskId: String, id: String, row: Row) =
            TaskLink(
                    id,
                    taskId,
                    row["name"].text(),
                    row["url"].text(),
                    row["kind"].text(),
                    row["authorId"].text(),
                    row["authorName"].text(),
                )
                .also { it.validate() }
    }
}
