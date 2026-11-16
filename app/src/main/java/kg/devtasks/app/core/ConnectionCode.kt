package kg.devtasks.app.core

import java.net.URI

// Не data class: пароль из QR не должен попадать в автоматически созданный toString.
class ConnectionCode
private constructor(val name: String, val url: String, val login: String, val password: String) {
    companion object {
        fun parse(code: String): ConnectionCode {
            if (code.length > 16384) throw UserError("QR-код слишком большой.")
            val row =
                runCatching {
                        val text = code.trim().removePrefix("\uFEFF")
                        val tokens = org.json.JSONTokener(text)
                        require(
                            tokens.nextValue() is org.json.JSONObject &&
                                tokens.nextClean() == '\u0000'
                        )
                        Json.obj(text)
                    }
                    .getOrElse { throw UserError("В QR-коде нет настроек подключения к 1С.") }
            if (
                (row["type"] != null &&
                    row["type"] !in listOf("devtasks_1c_connection", "accounting_1c_connection")) ||
                    (row["version"] != null && row["version"] != 1) ||
                    listOf("odataUrl", "username", "password").any { row[it] !is String } ||
                    (row["name"] != null && row["name"] !is String)
            )
                throw UserError("Нужен QR подключения: odataUrl, username, password, версия 1.")
            val url = Source.normalizeUrl(row["odataUrl"] as String)
            val login = (row["username"] as String).trim()
            val password = row["password"] as String
            Source.credentials(login, password)
            if (login.length > 256 || password.length > 4096)
                throw UserError("Данные подключения слишком длинные.")
            val uri = URI(url)
            val fallback =
                uri.path.removeSuffix("/odata/standard.odata/").substringAfterLast('/').ifBlank {
                    uri.host
                }
            val name = row["name"].text().trim().ifBlank { fallback }.take(60)
            return ConnectionCode(name, url, login, password)
        }
    }
}
