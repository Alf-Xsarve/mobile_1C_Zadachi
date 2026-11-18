package kg.devtasks.app.core

import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class SharedApiError(val status: Int, val code: String, message: String) : IOException(message)

data class ServerAccount(val uid: String, val email: String, val displayName: String)

/** Only the application API receives this bearer token; OData uses separate credentials. */
class SharedApi(val baseUrl: String = "https://alf-mobile-tasks.duckdns.org") {
    private val base = baseUrl.toHttpUrl()
    private val http =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

    init {
        require(base.scheme == "https" || base.host in listOf("127.0.0.1", "::1", "localhost", "10.0.2.2"))
        require(
            base.username.isEmpty() &&
                base.password.isEmpty() &&
                base.encodedPath == "/" &&
                base.query == null
        )
    }

    fun request(
        method: String,
        path: String,
        token: String? = null,
        body: Row? = null,
        query: Map<String, String> = emptyMap(),
    ): Row {
        require(path.startsWith("/api/") && !path.contains(".."))
        val url =
            base
                .newBuilder()
                .encodedPath(path)
                .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
                .build()
        val request =
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .method(
                    method,
                    if (method == "GET") null
                    else
                        Json.encode(body ?: emptyMap<String, Any>())
                            .toRequestBody("application/json; charset=utf-8".toMediaType()),
                )
                .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val row = runCatching { Json.obj(raw) }.getOrDefault(emptyMap())
            if (!response.isSuccessful)
                throw SharedApiError(
                    response.code,
                    row["code"].text(),
                    row["message"].text().ifBlank {
                        when (response.code) {
                            401 -> "Сеанс завершён. Войдите снова."
                            409 -> "Данные изменились. Обновите задачу."
                            429 -> "Слишком много запросов. Повторите через минуту."
                            503 -> "Сервер временно недоступен или требует обновления."
                            else -> "Ошибка сервера (HTTP ${response.code})."
                        }
                    },
                )
            if (response.code != 204 && row.isEmpty()) throw IOException("Некорректный ответ API")
            return row
        }
    }

    companion object {
        fun account(row: Row) =
            ServerAccount(row["id"].text(), row["email"].text(), row["name"].text())

        fun task(row: Row) =
            CloudTask(
                row["id"].text(),
                row["title"].text(),
                row["description"].text(),
                row["status"].text(),
                row["authorId"].text(),
                row["authorName"].text(),
                row["assigneeId"].text(),
                row["assigneeName"].text(),
                runCatching { Instant.parse(row["createdAt"].text()).toEpochMilli() }
                    .getOrDefault(0L),
            )

        fun message(error: Throwable) =
            when (error) {
                is SharedApiError,
                is UserError -> error.message.orEmpty()
                is javax.net.ssl.SSLException -> "Не удалось проверить сертификат HTTPS сервера."
                else ->
                    "Нет связи с сервером. Сохранённые данные доступны; для изменения нужен интернет."
            }
    }
}
