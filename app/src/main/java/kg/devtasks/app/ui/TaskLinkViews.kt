package kg.devtasks.app.ui

import android.app.AlertDialog
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.*
import kg.devtasks.app.MainActivity
import kg.devtasks.app.core.*

class TaskLinkViews(private val a: MainActivity, private val u: Ui) {
    private val c
        get() = a.state.cloud

    fun decorate(text: TextView): TextView {
        val value = text.text.toString()
        val spans = SpannableString(value)
        Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).findAll(value).forEach { match ->
            var url = match.value.trimEnd('.', ',', ';', ':', '!')
            while (url.endsWith(')') && url.count { it == ')' } > url.count { it == '(' }) url =
                url.dropLast(1)
            if (runCatching { webLink(url) }.isSuccess) {
                spans.setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            a.openWebLink(url)
                        }
                    },
                    match.range.first,
                    match.range.first + url.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        text.text = spans
        text.movementMethod = LinkMovementMethod.getInstance()
        text.setLinkTextColor(if (u.dark) 0xFF9DB7FF.toInt() else 0xFF3968F5.toInt())
        return text
    }

    fun section(parent: LinearLayout, taskId: String, draft: Boolean) {
        parent.addView(u.gap(10))
        parent.addView(u.text("Материалы и контакты", 18f, true))
        val actions = u.row()
        listOf("file" to "+ Файл", "image" to "+ Изображение", "web" to "+ Ссылка").forEach {
            (kind, label) ->
            actions.addView(
                u.button(label, !c.busy && !c.links.busy, true) { dialog(taskId, kind, !draft) }
                    .apply {
                        minHeight = u.dp(48)
                        textSize = 12f * u.scale
                        setPadding(u.dp(4), u.dp(8), u.dp(4), u.dp(8))
                    },
                LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(u.dp(2), 0, u.dp(2), 0) },
            )
        }
        parent.addView(actions)
        val contacts = u.row()
        listOf("whatsapp" to "+ WhatsApp", "telegram" to "+ Telegram").forEach { (kind, label) ->
            contacts.addView(
                u.button(label, !c.busy && !c.links.busy, true) { dialog(taskId, kind, !draft) }
                    .apply {
                        minHeight = u.dp(48)
                        textSize = 13f * u.scale
                        setPadding(u.dp(8), u.dp(8), u.dp(8), u.dp(8))
                        setTextColor(
                            if (kind == "whatsapp")
                                (if (u.dark) 0xFF63D9A0.toInt() else 0xFF147D51.toInt())
                            else (if (u.dark) 0xFF90C9F9.toInt() else 0xFF2479B5.toInt())
                        )
                    },
                LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(u.dp(2), 0, u.dp(2), 0) },
            )
        }
        parent.addView(contacts)
        u.note(parent, c.links.error)
        if (c.links.busy) parent.addView(u.text("Сохраняем ссылки…", 12f, color = u.muted))
        if (!draft && c.links.loading)
            parent.addView(u.text("Загружаем материалы…", 12f, color = u.muted))
        val saved = if (draft) emptyList() else c.links.items.filter { it.taskId == taskId }
        val pending = c.links.pendingFor(taskId)
        if (saved.isEmpty() && pending.isEmpty())
            parent.addView(
                u.text(
                    "Добавьте ссылку на материал или контакт клиента. Для номера телефона укажите код страны.",
                    13f,
                    color = u.muted,
                )
            )
        saved.forEach { parent.addView(card(it, false)) }
        pending.forEach { parent.addView(card(it, true)) }
        if (!draft && pending.isNotEmpty())
            parent.addView(
                u.button("Повторить отправку ссылок", !c.links.busy && !c.busy) {
                    c.links.publish(taskId)
                }
            )
    }

    private fun card(link: TaskLink, pending: Boolean): View {
        val card =
            u.card(10).apply {
                layoutParams =
                    LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, u.dp(4), 0, u.dp(4)) }
            }
        val title = u.row()
        title.addView(
            u.text(link.name, 15f, true).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        title.addView(u.icon("↗", "Открыть: ${link.name}") { a.openWebLink(link.url) })
        if (pending)
            title.addView(
                u.icon("×", "Убрать: ${link.name}") { c.links.removePending(link.taskId, link.id) }
                    .apply { isEnabled = !c.links.busy && !c.busy }
            )
        card.addView(title)
        card.addView(
            u.text(link.url, 12f, color = u.muted).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextIsSelectable(true)
            }
        )
        card.addView(
            u.text(
                "${link.kindLabel} · ${link.authorName}" + if (pending) " · не отправлено" else "",
                11f,
                color = u.muted,
            )
        )
        return card
    }

    private fun dialog(taskId: String, kind: String, publishNow: Boolean) {
        val body = u.column(16)
        val contact = kind in listOf("whatsapp", "telegram")
        val name =
            u.input(
                if (contact) "Имя клиента (необязательно)" else "Название материала (необязательно)"
            ) {}
        val address =
            u.input(
                    if (kind == "whatsapp") "Номер телефона или ссылка"
                    else if (kind == "telegram") "Номер, @username или ссылка" else "Адрес ссылки"
                ) {}
                .apply {
                    inputType =
                        android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_VARIATION_URI
                }
        body.addView(name)
        body.addView(address)
        body.addView(
            u.text(
                when (kind) {
                    "whatsapp" ->
                        "Например: +996555123456 или https://wa.me/996555123456. Контакт увидят пользователи задачи."
                    "telegram" ->
                        "Номер с кодом страны, @username или ссылка t.me. Доступ по номеру зависит от настроек приватности клиента."
                    else -> "У других пользователей должен быть доступ к материалу по этой ссылке."
                },
                12f,
                color = u.muted,
            )
        )
        val dialog =
            AlertDialog.Builder(a)
                .setTitle(
                    when (kind) {
                        "file" -> "Ссылка на файл"
                        "image" -> "Ссылка на изображение"
                        "whatsapp" -> "Контакт WhatsApp"
                        "telegram" -> "Контакт Telegram"
                        else -> "Добавить ссылку"
                    }
                )
                .setView(body)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Добавить", null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (
                    c.links.add(
                        taskId,
                        name.text.toString(),
                        address.text.toString(),
                        kind,
                        c.profileName,
                        publishNow,
                    )
                )
                    dialog.dismiss()
                else address.error = c.links.error
            }
        }
        dialog.show()
    }
}
