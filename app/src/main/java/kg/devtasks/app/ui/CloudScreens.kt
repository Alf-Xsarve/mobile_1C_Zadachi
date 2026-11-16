package kg.devtasks.app.ui

import android.app.AlertDialog
import android.text.TextUtils
import android.view.View
import android.widget.*
import kg.devtasks.app.MainActivity
import kg.devtasks.app.core.*

class CloudScreens(private val a: MainActivity, private val u: Ui) {
    private val c
        get() = a.state.cloud

    fun render(root: LinearLayout) {
        root.addView(
            u.header(
                if (c.draft != null) "Новая задача" else "Общие задачи",
                c.account?.let { c.profileName.ifBlank { it.email.orEmpty() } }.orEmpty(),
                { a.state.back() },
                if (c.ready && c.draft == null && c.selectedId == null)
                    listOf(
                        u.icon("+", "Создать общую задачу") {
                            c.draft = CloudDraft()
                            c.error = null
                            a.render()
                        }
                    )
                else emptyList(),
                compact = true,
            )
        )
        if (!c.configured) {
            val body = u.column(20)
            body.addView(u.text("Общие задачи ещё не подключены", 22f, true))
            body.addView(
                u.text(
                    "Администратору нужно подключить Firebase для этой версии приложения. Задачи 1С доступны в своём разделе."
                )
            )
            body.addView(u.button("К задачам 1С") { a.state.back() })
            root.addView(body)
            return
        }
        if (c.account == null) {
            auth(root)
            return
        }
        u.note(root, c.error)
        if (!c.ready) {
            val body = u.column(20)
            body.addView(
                u.text(
                    if (c.busy) "Подключаем общие задачи…" else "Не удалось открыть общие задачи",
                    18f,
                    true,
                )
            )
            if (c.busy) body.addView(u.skeleton(2))
            else {
                body.addView(u.button("Повторить") { c.connect() })
                body.addView(u.button("Сменить облачный аккаунт", secondary = true) { c.signOut() })
            }
            u.fill(root, u.scroll(body))
            return
        }
        if (c.draft != null) {
            draft(root)
            return
        }
        if (c.selectedId != null) {
            detail(root)
            return
        }
        board(root)
    }

    private fun auth(root: LinearLayout) {
        val body = u.column(20)
        body.addView(
            u.text(
                if (c.registering) "Создайте общий аккаунт" else "Вход в общие задачи",
                23f,
                true,
            )
        )
        body.addView(
            u.text(
                "Все зарегистрированные пользователи видят задачи и их исполнителей. Для входа с другого телефона используйте тот же email.",
                14f,
                color = u.muted,
            )
        )
        if (c.registering) body.addView(u.input("Ваше имя в Firebase", c.name) { c.name = it })
        body.addView(u.input("Email Firebase", c.email) { c.email = it })
        body.addView(u.input("Пароль Firebase", c.password, true) { c.password = it })
        if (c.registering)
            body.addView(u.input("Повторите пароль Firebase", c.repeat, true) { c.repeat = it })
        u.note(body, c.error)
        body.addView(
            u.button(
                if (c.busy) "Подключение…"
                else if (c.registering) "Создать аккаунт Firebase" else "Войти в Firebase",
                !c.busy,
            ) {
                c.authenticate(c.registering, c.email, c.password, c.name, c.repeat)
            }
        )
        body.addView(
            u.button(
                if (c.registering) "Уже есть аккаунт Firebase" else "Регистрация в Firebase",
                !c.busy,
                true,
            ) {
                c.registering = !c.registering
                c.error = null
                a.render()
            }
        )
        if (!c.registering)
            body.addView(u.button("Восстановить пароль", !c.busy, true) { c.resetPassword() })
        u.fill(root, u.scroll(body))
    }

    private fun board(root: LinearLayout) {
        val tools = u.column().apply { setPadding(u.dp(12), 0, u.dp(12), 0) }
        var populate: (() -> Unit)? = null
        tools.addView(
            u.input("Поиск общих задач", c.query) {
                c.query = it
                populate?.invoke()
            }
        )
        val filters = u.row()
        listOf("all" to "Все", "new" to "Свободно", "mine" to "Мои", "done" to "Готово").forEach {
            (value, label) ->
            filters.addView(
                u.chip(label, if (c.filter == value) u.ink else u.muted).apply {
                    minHeight = u.dp(40)
                    gravity = android.view.Gravity.CENTER
                    background = u.shape(if (c.filter == value) u.accent else u.paper, 10)
                    setOnClickListener {
                        c.filter = value
                        a.render()
                    }
                    layoutParams =
                        LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, u.dp(6), 0) }
                }
            )
        }
        tools.addView(
            HorizontalScrollView(a).apply {
                isHorizontalScrollBarEnabled = false
                addView(filters)
            }
        )
        root.addView(tools)
        root.addView(
            u.text(
                if (c.fromCache) "  Сохранённые данные · для изменения нужен интернет"
                else "  Изменения появляются автоматически",
                11f,
                color = u.muted,
            )
        )
        val area = u.column()
        u.fill(root, area)
        fun fill() {
            area.removeAllViews()
            val rows = c.visible()
            if (c.loading && rows.isEmpty()) {
                area.addView(u.skeleton(3))
                return
            }
            if (rows.isEmpty()) {
                area.addView(
                    u.text("  Задач пока нет. Создайте первую кнопкой +.", 14f, color = u.muted)
                )
                return
            }
            val list =
                ListView(a).apply {
                    divider = null
                    tag = "tasks"
                    adapter =
                        object : BaseAdapter() {
                            override fun getCount() = rows.size

                            override fun getItem(position: Int) = rows[position]

                            override fun getItemId(position: Int) = position.toLong()

                            override fun getView(
                                position: Int,
                                convertView: View?,
                                parent: android.view.ViewGroup?,
                            ): View {
                                val task = rows[position]
                                return u.card(12).apply {
                                    addView(u.chip(task.label))
                                    addView(
                                        u.text(task.title, 15f, true).apply {
                                            maxLines = 2
                                            ellipsize = TextUtils.TruncateAt.END
                                        }
                                    )
                                    addView(
                                        u.text("Автор: ${task.authorName}", 11f, color = u.muted)
                                    )
                                    addView(
                                        u.text(
                                            if (task.assigneeId.isBlank()) "Исполнитель не назначен"
                                            else "Исполнитель: ${task.assigneeName}",
                                            12f,
                                            true,
                                        )
                                    )
                                    setOnClickListener {
                                        c.selectedId = task.id
                                        c.error = null
                                        a.render()
                                    }
                                }
                            }
                        }
                }
            u.fill(area, list)
        }
        populate = { fill() }
        fill()
        if (c.items.size.toLong() >= c.limit)
            root.addView(u.button("Загрузить ещё", !c.loading, true) { c.listen(true) })
        val actions = u.row().apply { setPadding(u.dp(12), 0, u.dp(12), 0) }
        actions.addView(
            u.button("Обновить", !c.busy, true) { c.listen() },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        actions.addView(
            u.button("Аккаунт", !c.busy, true) {
                AlertDialog.Builder(a)
                    .setTitle(c.profileName)
                    .setMessage(c.account?.email)
                    .setNegativeButton("Закрыть", null)
                    .setPositiveButton("Выйти из Firebase") { _, _ -> c.signOut() }
                    .show()
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        root.addView(actions)
    }

    private fun draft(root: LinearLayout) {
        val d = c.draft ?: return
        val body = u.column(16)
        body.addView(
            u.input("Название общей задачи", d.title) { d.title = it }.apply { isEnabled = !c.busy }
        )
        body.addView(
            u.input("Описание общей задачи", d.description) { d.description = it }
                .apply {
                    isSingleLine = false
                    minLines = 5
                    maxLines = 12
                    gravity = android.view.Gravity.TOP
                    inputType =
                        android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    isEnabled = !c.busy
                }
        )
        body.addView(
            u.text(
                "Задача станет доступна всем пользователям приложения. Её сможет взять один исполнитель.",
                13f,
                color = u.muted,
            )
        )
        body.addView(
            u.button(if (c.busy) "Сохраняем…" else "Создать задачу", !c.busy) { c.create() }
        )
        body.addView(u.button("Отмена", !c.busy, true) { a.state.back() })
        u.fill(root, u.scroll(body).apply { tag = "scroll" })
    }

    private fun detail(root: LinearLayout) {
        val task = c.items.firstOrNull { it.id == c.selectedId }
        if (task == null) {
            root.addView(u.text("Задача недоступна. Вернитесь к списку и обновите его."))
            return
        }
        val body = u.column(16)
        body.addView(u.chip(task.label))
        body.addView(u.text(task.title, 24f, true))
        body.addView(u.text("Автор: ${task.authorName}", 13f, color = u.muted))
        body.addView(
            u.text(
                if (task.assigneeId.isBlank()) "Исполнитель пока не назначен"
                else "Исполнитель: ${task.assigneeName}",
                16f,
                true,
            )
        )
        body.addView(u.text(task.description.ifBlank { "Описание не добавлено" }, 16f))
        if (task.createdAt > 0)
            body.addView(u.text("Создана ${timeText(task.createdAt)}", 12f, color = u.muted))
        val uid = c.account?.uid.orEmpty()
        if (task.canClaim(uid))
            body.addView(u.button("Взять общую задачу", !c.busy) { confirm(task, false) })
        if (task.canComplete(uid))
            body.addView(u.button("Завершить общую задачу", !c.busy) { confirm(task, true) })
        u.fill(root, u.scroll(body).apply { tag = "scroll" })
    }

    private fun confirm(task: CloudTask, complete: Boolean) {
        AlertDialog.Builder(a)
            .setTitle(if (complete) "Завершить задачу?" else "Взять задачу в работу?")
            .setMessage(
                if (complete) "Все пользователи увидят статус «Выполнено»."
                else "Вы будете указаны исполнителем для всех пользователей."
            )
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Подтвердить") { _, _ -> c.change(task, complete) }
            .show()
    }
}
