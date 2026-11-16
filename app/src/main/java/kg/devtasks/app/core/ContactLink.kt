package kg.devtasks.app.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private fun internationalPhone(raw: String): String {
    val value = raw.trim()
    if (!Regex("^[+0-9() .-]+$").matches(value))
        throw UserError("Укажите номер телефона с кодом страны, например +996555123456.")
    val digits =
        value.filter { it in '0'..'9' }.let { if (value.startsWith("00")) it.drop(2) else it }
    if (!Regex("^[1-9][0-9]{7,14}$").matches(digits))
        throw UserError("Нужен международный номер с кодом страны, например +996555123456.")
    return digits
}

/** Телефоны и имена пользователя превращаются в обычные HTTPS-ссылки мессенджеров. */
fun materialLink(kind: String, raw: String): String {
    if (kind !in listOf("whatsapp", "telegram")) return webLink(raw)
    val value = raw.trim()
    if (value.length > 4096) throw UserError("Контакт слишком длинный.")
    if (Regex("^[+0-9() .-]+$").matches(value) && value.any { it.isDigit() }) {
        val phone = internationalPhone(value)
        return if (kind == "whatsapp") "https://wa.me/$phone" else "https://t.me/+$phone"
    }
    if (kind == "telegram" && Regex("^@?[A-Za-z0-9_]{1,32}$").matches(value))
        return "https://t.me/${value.removePrefix("@")}"
    // Поддерживаем распространённые ссылки приложений, сохраняя в базе HTTPS-эквивалент.
    if (value.startsWith("tg://", true) || value.startsWith("whatsapp://", true)) {
        val uri =
            runCatching { java.net.URI(value) }.getOrNull()
                ?: throw UserError("Некорректная ссылка мессенджера.")
        val params =
            runCatching {
                    uri.rawQuery.orEmpty().split('&').associate {
                        val pair = it.split('=', limit = 2)
                        java.net.URLDecoder.decode(pair[0], "UTF-8") to
                            java.net.URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                    }
                }
                .getOrElse { throw UserError("Некорректная ссылка мессенджера.") }
        if (
            kind == "telegram" && uri.scheme.equals("tg", true) && uri.host.equals("resolve", true)
        ) {
            params["domain"]?.let {
                if (!Regex("^[A-Za-z0-9_]{1,32}$").matches(it))
                    throw UserError("Проверьте username Telegram.")
                return "https://t.me/$it"
            }
            params["phone"]?.let {
                return "https://t.me/+${internationalPhone(it)}"
            }
        }
        if (
            kind == "whatsapp" &&
                uri.scheme.equals("whatsapp", true) &&
                uri.host.equals("send", true)
        )
            params["phone"]?.let {
                return "https://wa.me/${internationalPhone(it)}"
            }
        throw UserError("Нужен номер, username или обычная ссылка мессенджера.")
    }
    val url = webLink(value).toHttpUrlOrNull()!!
    val host = url.host
    val valid =
        if (kind == "whatsapp")
            host == "wa.me" || host == "whatsapp.com" || host.endsWith(".whatsapp.com")
        else host == "t.me" || host == "telegram.me" || host.endsWith(".t.me")
    if (!valid)
        throw UserError(
            if (kind == "whatsapp") "Нужен номер с кодом страны или ссылка wa.me / whatsapp.com."
            else "Нужен номер, @username или ссылка t.me / telegram.me."
        )
    val result = url.newBuilder().scheme("https").port(443)
    if (
        kind == "whatsapp" &&
            host == "wa.me" &&
            url.pathSegments.size == 1 &&
            Regex("^[+0-9() .-]+$").matches(url.pathSegments[0])
    )
        result.setPathSegment(0, internationalPhone(url.pathSegments[0]))
    if (kind == "whatsapp" && url.queryParameter("phone") != null)
        result.setQueryParameter("phone", internationalPhone(url.queryParameter("phone")!!))
    if (
        kind == "telegram" &&
            url.pathSegments.size == 1 &&
            Regex("^[+][0-9]+$").matches(url.pathSegments[0])
    )
        result.setPathSegment(0, "+${internationalPhone(url.pathSegments[0])}")
    return result.build().toString()
}

fun materialDefaultName(kind: String, url: String): String {
    val address = url.toHttpUrlOrNull()!!
    val first = address.pathSegments.firstOrNull().orEmpty()
    return when (kind) {
        "whatsapp" -> {
            val phone =
                address.queryParameter("phone")
                    ?: first.takeIf { it.all { c -> c.isDigit() } && it.isNotBlank() }
            if (phone != null) "WhatsApp: +$phone" else "WhatsApp"
        }
        "telegram" ->
            when {
                first.length > 1 && first.startsWith('+') && first.drop(1).all { it.isDigit() } ->
                    "Telegram: $first"
                address.host.endsWith(".t.me") -> "Telegram: @${address.host.removeSuffix(".t.me")}"
                address.pathSegments.size == 1 && Regex("^[A-Za-z0-9_]{1,32}$").matches(first) ->
                    "Telegram: @$first"
                else -> "Telegram"
            }
        else -> address.host
    }
}
