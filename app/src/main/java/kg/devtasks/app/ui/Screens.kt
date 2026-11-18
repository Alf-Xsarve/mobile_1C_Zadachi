package kg.devtasks.app.ui

import android.app.AlertDialog
import android.content.Context
import android.view.*
import android.widget.*
import kg.devtasks.app.*
import kg.devtasks.app.core.*

class Screens(val a: MainActivity, val u: Ui) {
    val s
        get() = a.state

    fun render(): View {
        val root = u.column().apply { setBackgroundColor(u.bg) }
        if (s.boot) {
            root.addView(u.header("Задачи / Dev", "Ваш рабочий ритм"))
            root.addView(u.skeleton(3))
            return root
        }
        if (s.user == null) return auth(root)
        when (s.page) {
            "cloud" -> CloudScreens(a, u).render(root)
            "editor" -> editor(root)
            "sources" -> sources(root)
            "detail" -> detail(root)
            else -> home(root)
        }
        return root
    }

    fun auth(root: LinearLayout): View {
        val content = u.column(24)
        content.addView(u.gap(24))
        content.addView(
            u.text("✓", 36f, true, u.onAccent).apply {
                gravity = Gravity.CENTER
                background = u.shape(u.accent, 20)
                layoutParams = LinearLayout.LayoutParams(u.dp(72), u.dp(72))
            }
        )
        content.addView(u.gap(20))
        content.addView(
            u.text(
                if (s.registering) "Ваш рабочий\nпрофиль" else "Хороший день\nдля новых решений",
                29f,
                true,
            )
        )
        content.addView(
            u.text("Задачи из разных баз 1С.\nВ одном удобном приложении.", 15f, false, u.muted)
        )
        content.addView(u.gap(16))
        val card = u.card(20).apply { layoutParams = LinearLayout.LayoutParams(-1, -2) }
        card.addView(
            u.text(if (s.registering) "Создать учётную запись" else "Вход в приложение", 20f, true)
        )
        if (s.registering) card.addView(u.input("Ваше имя", s.authName) { s.authName = it })
        card.addView(
            u.input(
                if (s.cloud.configured && !s.localAuth) "Email" else "Логин приложения",
                s.authLogin,
            ) {
                s.authLogin = it
            }
        )
        card.addView(u.input("Пароль приложения", s.authPassword, true) { s.authPassword = it })
        if (s.registering)
            card.addView(u.input("Повторите пароль", s.authRepeat, true) { s.authRepeat = it })
        if (s.busy) card.addView(u.text("Секунду, защищаем ваши данные…", 12f, false, u.muted))
        card.addView(
            u.button(if (s.registering) "Зарегистрироваться" else "Войти", !s.busy) {
                hideKeyboard()
                s.auth()
            }
        )
        card.addView(
            u.button(
                if (s.registering) "У меня уже есть профиль" else "Создать профиль",
                !s.busy,
                true,
            ) {
                s.registering = !s.registering
                s.error = null
                a.render()
            }
        )
        content.addView(card)
        if (s.cloud.configured)
            content.addView(
                u.button(
                    if (s.localAuth) "Войти через сервер" else "Войти в локальный профиль",
                    !s.busy,
                    true,
                ) {
                    s.localAuth = !s.localAuth
                    s.error = null
                    a.render()
                }
            )
        u.note(content, s.error ?: if (!s.localAuth) s.cloud.error else null)
        content.addView(u.gap(12))
        content.addView(
            u.text(
                if (s.cloud.configured && !s.localAuth)
                    "Общий аккаунт приложения: email и пароль. Подключения к базам 1С хранятся только на вашем телефоне."
                else
                    "Это отдельный профиль на этом телефоне. Реквизиты пользователей 1С вы добавите в подключениях к базам.",
                12f,
                false,
                u.muted,
            )
        )
        u.fill(root, u.scroll(content))
        return root
    }

    fun header(
        root: LinearLayout,
        title: String,
        sub: String = "",
        back: Boolean = true,
        actions: List<View> = emptyList(),
    ) {
        root.addView(u.header(title, sub, if (back) ({ s.back() }) else null, actions))
        u.note(root, s.error)
    }

    fun home(root: LinearLayout) {
        if (s.tab == "settings") {
            settings(root)
            nav(root)
            return
        }
        val title =
            when (s.tab) {
                "mine" -> "Взятые мной"
                "done" -> "Мои выполненные"
                else -> "Все задачи"
            }
        val sections = u.row().apply { setPadding(u.dp(12), 0, u.dp(12), 0) }
        sections.addView(
            u.button("Задачи 1С") {}
                .apply {
                    minHeight = u.dp(40)
                    textSize = 12f * u.scale
                    setPadding(u.dp(6), u.dp(6), u.dp(6), u.dp(6))
                },
            LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, u.dp(6), 0) },
        )
        sections.addView(
            u.button("Общие задачи", secondary = true) {
                    s.page = "cloud"
                    s.error = null
                    a.render()
                }
                .apply {
                    minHeight = u.dp(40)
                    textSize = 12f * u.scale
                    setPadding(u.dp(6), u.dp(6), u.dp(6), u.dp(6))
                },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        root.addView(sections)
        root.addView(
            u.header(
                title,
                actions =
                    listOf(
                        u.icon("↻", "Обновить задачи") { s.refresh() },
                        u.icon("▦", "Добавить базу по QR-коду") { a.scan() },
                    ),
                compact = true,
            )
        )
        val summary =
            u.card(7).apply {
                background = u.shape(if (u.dark) 0xFF33301C.toInt() else 0xFFFFF2B6.toInt(), 10)
                layoutParams =
                    LinearLayout.LayoutParams(-1, -2).apply {
                        setMargins(u.dp(12), 0, u.dp(12), u.dp(3))
                    }
            }
        val counts = u.row()
        fun count(number: Int, label: String) {
            counts.addView(
                u.text("$number $label", 11f, true).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, u.dp(2), 0, u.dp(2))
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
        }
        count(
            s.tasks.count { t ->
                s.sources.find { it.id == t.sourceId }?.let { t.phase(it.mapping) == Phase.NEW } ==
                    true
            },
            "свободно",
        )
        count(
            s.tasks.count { t ->
                t.taken &&
                    s.sources
                        .find { it.id == t.sourceId }
                        ?.let { t.phase(it.mapping) == Phase.ACTIVE } == true
            },
            "в работе",
        )
        count(
            s.tasks.count { t ->
                t.completed &&
                    s.sources
                        .find { it.id == t.sourceId }
                        ?.let { t.phase(it.mapping) == Phase.DONE } == true
            },
            "выполнено",
        )
        summary.addView(counts)
        root.addView(summary)
        val tools = u.column().apply { setPadding(u.dp(12), 0, u.dp(12), u.dp(3)) }
        val searchRow = u.row()
        var populate: (() -> Unit)? = null
        searchRow.addView(
            u.input("Поиск задач", s.query) {
                    s.query = it
                    populate?.invoke()
                }
                .apply {
                    textSize = 13f * u.scale
                    minimumHeight = u.dp(48)
                    setPadding(u.dp(12), u.dp(8), u.dp(12), u.dp(8))
                },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        searchRow.addView(u.icon("☷", "Фильтр по базе") { chooseSource() })
        tools.addView(searchRow)
        val filters = u.row()
        listOf(
                null to "Все",
                Phase.NEW to "Свободно",
                Phase.ACTIVE to "В работе",
                Phase.DONE to "Готово",
            )
            .forEach { (phase, label) ->
                filters.addView(
                    u.chip(label, if (s.filter == phase) u.ink else u.muted).apply {
                        setPadding(u.dp(12), u.dp(6), u.dp(12), u.dp(6))
                        minHeight = u.dp(40)
                        gravity = Gravity.CENTER
                        background = u.shape(if (s.filter == phase) u.accent else u.paper, 12)
                        setOnClickListener {
                            s.filter = phase
                            a.render()
                        }
                        layoutParams =
                            LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, u.dp(7), 0) }
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
        if (s.errors.isNotEmpty()) {
            root.addView(
                u.text(
                        "  Не удалось обновить базы: ${s.errors.size} · показаны сохранённые данные",
                        12f,
                        false,
                        0xFFB17725.toInt(),
                    )
                    .apply {
                        setPadding(u.dp(16), u.dp(8), u.dp(16), u.dp(8))
                        setOnClickListener {
                            AlertDialog.Builder(a)
                                .setTitle("Обновление баз")
                                .setMessage(
                                    s.errors.entries.joinToString("\n\n") { (id, e) ->
                                        "${s.sources.find{it.id==id}?.name}: $e"
                                    }
                                )
                                .setPositiveButton("Закрыть", null)
                                .show()
                        }
                    }
            )
        }
        u.note(root, s.error)
        val area = u.column()
        u.fill(root, area)
        fun fill() {
            area.removeAllViews()
            val values = s.visible()
            if (s.syncing && values.isEmpty()) {
                area.addView(u.skeleton(3))
                return
            }
            if (s.sources.isEmpty()) {
                area.addView(
                    empty(
                        "Подключим ваши базы?",
                        "Добавьте адреса OData, чтобы видеть задачи программистов из разных баз.",
                        "Добавить базу",
                    ) {
                        s.openEditor()
                    }
                )
                return
            }
            if (values.isEmpty()) {
                area.addView(
                    empty(
                        "Пока ничего нет",
                        if (s.tab == "all") "Обновите базы или измените фильтр поиска."
                        else "Здесь появятся задачи, которые вы возьмёте или выполните.",
                        "Обновить задачи",
                    ) {
                        s.refresh()
                    }
                )
                return
            }
            val list =
                ListView(a).apply {
                    tag = "tasks"
                    divider = null
                    dividerHeight = 0
                    clipToPadding = false
                    setPadding(0, 0, 0, u.dp(6))
                    adapter =
                        object : BaseAdapter() {
                            override fun getCount() = values.size

                            override fun getItem(position: Int) = values[position]

                            override fun getItemId(position: Int) = position.toLong()

                            override fun getView(
                                position: Int,
                                convertView: View?,
                                parent: ViewGroup?,
                            ): View {
                                val task = values[position]
                                val source = s.sources.first { it.id == task.sourceId }
                                return ((convertView as? TaskRow) ?: TaskRow(u)).apply {
                                    bind(
                                        task,
                                        source,
                                        s.pending.any {
                                            it.sourceId == task.sourceId && it.key == task.key
                                        },
                                        phaseColor(task.phase(source.mapping)),
                                    ) {
                                        s.detailId = task.id
                                        s.page = "detail"
                                        s.error = null
                                        hideKeyboard()
                                        a.render()
                                    }
                                }
                            }
                        }
                }
            u.fill(area, list)
            if (s.syncing) area.addView(u.text("  Обновление баз…", 12f, false, u.muted))
            if (s.sources.any { s.more[it.id] != false })
                area.addView(
                    u.button(if (s.syncing) "Обновляем…" else "Загрузить ещё", !s.syncing, true) {
                            s.refresh(true)
                        }
                        .apply {
                            minHeight = u.dp(44)
                            textSize = 13f * u.scale
                            setPadding(u.dp(12), u.dp(7), u.dp(12), u.dp(7))
                        }
                        .apply {
                            layoutParams =
                                LinearLayout.LayoutParams(-1, -2).apply {
                                    setMargins(u.dp(16), 0, u.dp(16), u.dp(5))
                                }
                        }
                )
        }
        populate = { fill() }
        fill()
        nav(root)
    }

    fun nav(root: LinearLayout) {
        val bar =
            u.row().apply {
                setPadding(u.dp(8), u.dp(3), u.dp(8), u.dp(3))
                background = u.shape(u.paper, 0)
            }
        listOf("all" to "Все", "mine" to "Мои", "done" to "Готово", "settings" to "Настройки")
            .forEach { (key, title) ->
                bar.addView(
                    u.button(title, secondary = s.tab != key) {
                            s.tab = key
                            s.query = ""
                            s.filter = null
                            s.error = null
                            a.render()
                        }
                        .apply {
                            contentDescription = "Раздел $title"
                            minHeight = u.dp(48)
                            setPadding(u.dp(3), u.dp(7), u.dp(3), u.dp(7))
                            textSize = 11f * u.scale
                        },
                    LinearLayout.LayoutParams(0, -2, 1f).apply {
                        setMargins(u.dp(3), 0, u.dp(3), 0)
                    },
                )
            }
        root.addView(bar)
    }

    fun phaseColor(p: Phase) =
        when (p) {
            Phase.NEW -> if (u.dark) 0xFFFFD342.toInt() else 0xFF94710A.toInt()
            Phase.ACTIVE -> 0xFF5080DD.toInt()
            Phase.DONE -> 0xFF258F79.toInt()
            Phase.CANCELED -> 0xFFB36878.toInt()
            else -> u.muted
        }

    fun empty(title: String, description: String, button: String, action: () -> Unit) =
        u.column(24).apply {
            addView(u.gap(12))
            addView(u.text("◇", 45f, true, u.muted).apply { gravity = Gravity.CENTER })
            addView(u.text(title, 22f, true).apply { gravity = Gravity.CENTER })
            addView(u.text(description, 14f, false, u.muted).apply { gravity = Gravity.CENTER })
            addView(u.gap())
            addView(u.button(button, action = action))
        }

    fun chooseSource() {
        val labels = arrayOf("Все базы") + s.sources.map { it.name }
        AlertDialog.Builder(a)
            .setTitle("Показать задачи")
            .setItems(labels) { _, index ->
                s.sourceFilter = if (index == 0) null else s.sources[index - 1].id
                a.render()
            }
            .show()
    }

    fun sources(root: LinearLayout) {
        header(
            root,
            "Подключения",
            "Все ваши базы 1С",
            actions =
                listOf(
                    u.icon("▦", "Добавить базу по QR-коду") { a.scan() },
                    u.icon("+", "Добавить базу") { s.openEditor() },
                ),
        )
        val content = u.column()
        content.addView(
            u.button("Сканировать QR-код", !s.busy && !s.syncing) { a.scan() }
                .apply {
                    layoutParams =
                        LinearLayout.LayoutParams(-1, -2).apply {
                            setMargins(u.dp(16), u.dp(5), u.dp(16), u.dp(5))
                        }
                }
        )
        s.sources.forEach { source ->
            val card = u.card()
            card.addView(u.text("▣ ${source.name}", 19f, true))
            card.addView(
                u.text(source.url, 12f, false, u.muted).apply { setTextIsSelectable(true) }
            )
            card.addView(u.text("Пользователь 1С: ${source.login}", 13f, false, u.muted))
            card.addView(u.text("На телефоне: ${s.tasks.count{it.sourceId==source.id}} задач", 13f))
            s.synced[source.id]?.let {
                card.addView(u.text("Обновлено ${timeText(it)}", 12f, false, u.muted))
            }
            if (s.errors[source.id] != null)
                card.addView(u.text(s.errors[source.id].orEmpty(), 12f, false, 0xFFB17725.toInt()))
            card.addView(
                u.button("Изменить подключение", !s.syncing && !s.busy, true) {
                    s.openEditor(source)
                }
            )
            card.addView(
                u.button("Удалить с телефона", !s.syncing && !s.busy && s.working.isEmpty(), true) {
                    AlertDialog.Builder(a)
                        .setTitle("Удалить подключение?")
                        .setMessage(
                            "База «${source.name}» и её локальные задачи будут удалены с этого телефона. В 1С ничего не изменится."
                        )
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Удалить") { _, _ -> s.removeSource(source) }
                        .show()
                }
            )
            content.addView(card)
        }
        if (s.sources.isEmpty())
            content.addView(
                empty(
                    "Пока без подключений",
                    "Можно добавить несколько баз. У каждой — свои реквизиты пользователя 1С.",
                    "Добавить первую базу",
                ) {
                    s.openEditor()
                }
            )
        else
            content.addView(
                u.button("+ Добавить ещё базу") { s.openEditor() }
                    .apply {
                        layoutParams =
                            LinearLayout.LayoutParams(-1, -2).apply {
                                setMargins(u.dp(16), u.dp(10), u.dp(16), u.dp(10))
                            }
                    }
            )
        u.fill(root, u.scroll(content).apply { tag = "scroll" })
    }

    fun editor(root: LinearLayout) {
        val e = s.editor ?: return
        header(
            root,
            if (e.existing) "Подключение" else "Новая база",
            "Задачи будут видны в общем списке",
        )
        val content = u.column(20)
        if (!e.existing) {
            content.addView(u.button("Сканировать QR-код", !e.busy) { a.scan() })
            content.addView(u.text("Или введите реквизиты вручную", 12f, color = u.muted))
        }
        val card = u.card(18).apply { layoutParams = LinearLayout.LayoutParams(-1, -2) }
        card.addView(u.text("Доступ к OData", 19f, true))
        card.addView(u.input("Название базы", e.name) { e.name = it })
        card.addView(u.input("Адрес OData", e.url) { e.url = it }.apply { isEnabled = !e.existing })
        card.addView(u.input("Логин пользователя 1С", e.login) { e.login = it })
        card.addView(u.input("Пароль пользователя 1С", e.password, true) { e.password = it })
        card.addView(
            u.text("Адрес должен заканчиваться на /odata/standard.odata/", 12f, false, u.muted)
        )
        content.addView(card)
        content.addView(u.gap())
        content.addView(u.text("Что будет подключено", 18f, true))
        content.addView(
            u.text(
                "ТехЗаданияДляПрограммистов\n\nСтатусы загрузятся из перечисления «СтадияДоработокПрограммистовV2». Для записи проверяется версия задачи.",
                13f,
                false,
                u.muted,
            )
        )
        u.note(content, e.error)
        if (e.busy) content.addView(u.skeleton(1))
        content.addView(
            u.button(if (e.busy) "Проверяем базу…" else "Подключить базу", !e.busy) {
                hideKeyboard()
                s.addSource()
            }
        )
        u.fill(root, u.scroll(content).apply { tag = "scroll" })
    }

    fun detail(root: LinearLayout) {
        val task = s.tasks.find { it.id == s.detailId }
        if (task == null) {
            header(root, "Задача")
            root.addView(
                empty("Задача недоступна", "Вернитесь к списку и обновите данные.", "Вернуться") {
                    s.page = "home"
                    a.render()
                }
            )
            return
        }
        val base = s.sources.first { it.id == task.sourceId }
        val phase = task.phase(base.mapping)
        header(
            root,
            "Задача",
            base.name,
            actions = listOf(u.icon("↻", "Обновить задачу") { s.refreshTask(task) }),
        )
        val content = u.column(16)
        val card = u.card(20).apply { layoutParams = LinearLayout.LayoutParams(-1, -2) }
        card.addView(u.chip(phase.label, phaseColor(phase)))
        card.addView(u.gap())
        card.addView(u.text(task.title(base.mapping), 25f, true))
        card.addView(
            u.text(
                "${base.name}  ·  № ${task.raw["Number"].text().ifBlank{"—"}}",
                12f,
                false,
                u.muted,
            )
        )
        card.addView(u.gap())
        val programmer = task.raw[base.mapping.programmer].text()
        card.addView(u.text("Исполнитель", 12f, false, u.muted))
        card.addView(u.text(if (programmer.isBlank()) "Не назначен" else "@$programmer", 16f, true))
        content.addView(card)
        fun block(title: String, text: String) {
            if (text.isBlank()) return
            val box =
                u.card(18).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, u.dp(12), 0, 0) }
                }
            box.addView(u.text(title, 16f, true))
            box.addView(u.text(text, 15f).apply { setTextIsSelectable(true) })
            content.addView(box)
        }
        block("Описание", task.raw[base.mapping.description].text())
        block("Комментарий", task.raw[base.mapping.comment].text())
        content.addView(u.gap(12))
        content.addView(
            u.text("Сохранено на телефоне · ${timeText(task.updated)}", 12f, false, u.muted)
        )
        val pending = s.pending.any { it.sourceId == task.sourceId && it.key == task.key }
        val busy = task.id in s.working
        if (task.unavailable) {
            block(
                "Нет доступа",
                "Задача удалена из 1С или недоступна текущему пользователю. На телефоне осталась сохранённая копия.",
            )
        } else if (pending) {
            block(
                "Нужно проверить результат",
                "После отправки связь прервалась. Чтобы не перезаписать задачу повторно, сначала проверьте результат в 1С.",
            )
            content.addView(u.button("Проверить результат в 1С", !busy) { s.refreshTask(task) })
            content.addView(
                u.button("Снять локальное ожидание", !busy, true) {
                    AlertDialog.Builder(a)
                        .setTitle("Вы проверили результат в 1С?")
                        .setMessage(
                            "Операция могла сохраниться на сервере. Эта кнопка только снимает локальную блокировку; она не отменяет изменение в 1С."
                        )
                        .setNegativeButton("Нет", null)
                        .setPositiveButton("Проверил, снять") { _, _ -> s.clearPending(task) }
                        .show()
                }
            )
        } else {
            val next = if (phase == Phase.ACTIVE) Phase.DONE else Phase.ACTIVE
            content.addView(
                u.button(
                    if (next == Phase.DONE) "✓ Отметить выполненной" else "Взять в работу",
                    !busy,
                ) {
                    confirm(task, next)
                }
            )
            content.addView(
                u.button("Другой статус", !busy, true) {
                    val phases =
                        listOf(Phase.NEW, Phase.ACTIVE, Phase.DONE, Phase.CANCELED).filter {
                            it != phase
                        }
                    AlertDialog.Builder(a)
                        .setTitle("Новый статус")
                        .setItems(phases.map { it.label }.toTypedArray()) { _, i ->
                            confirm(task, phases[i])
                        }
                        .show()
                }
            )
        }
        if (busy) content.addView(u.skeleton(1))
        u.fill(root, u.scroll(content).apply { tag = "scroll" })
    }

    fun confirm(task: TaskItem, phase: Phase) {
        if (!s.confirmChanges) {
            s.change(task, phase)
            return
        }
        AlertDialog.Builder(a)
            .setTitle(phase.label)
            .setMessage(
                if (phase == Phase.ACTIVE)
                    "Исполнитель: ${s.user!!.login}. Ваш логин будет добавлен в комментарий; прежний текст сохранится."
                else
                    "Изменить статус задачи в базе «${s.sources.find{it.id==task.sourceId}?.name}»?"
            )
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ -> s.change(task, phase) }
            .show()
    }

    fun settings(root: LinearLayout) {
        header(root, "Настройки", "Сделайте приложение своим", false)
        val content = u.column(18)
        val user = s.user!!
        val profile = u.card(18).apply { layoutParams = LinearLayout.LayoutParams(-1, -2) }
        profile.addView(u.text(user.name, 22f, true))
        profile.addView(u.text("@${user.login} · профиль приложения", 13f, false, u.muted))
        profile.addView(
            u.button("▣ Мои базы 1С · ${s.sources.size}", secondary = true) {
                s.page = "sources"
                s.error = null
                a.render()
            }
        )
        content.addView(profile)
        content.addView(u.gap())
        content.addView(u.text("Оформление", 20f, true))
        val styles = u.row()
        listOf("yellow" to "1С", "blue" to "Синий", "purple" to "Графит").forEach { (key, title) ->
            styles.addView(
                u.button(title, secondary = s.style != key) {
                    s.style = key
                    s.savePreferences()
                    a.render()
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(u.dp(3), 0, u.dp(3), 0) },
            )
        }
        content.addView(styles)
        content.addView(
            u.button(
                "Тема: " +
                    when (s.theme) {
                        "dark" -> "Тёмная"
                        "light" -> "Светлая"
                        else -> "Как на телефоне"
                    },
                secondary = true,
            ) {
                AlertDialog.Builder(a)
                    .setTitle("Тема приложения")
                    .setItems(arrayOf("Как на телефоне", "Светлая", "Тёмная")) { _, index ->
                        s.theme = listOf("system", "light", "dark")[index]
                        s.savePreferences()
                        a.render()
                    }
                    .show()
            }
        )
        val size = u.text("Размер текста: ${(s.scale*100).toInt()}%", 14f)
        content.addView(size)
        content.addView(
            SeekBar(a).apply {
                max = 12
                progress = ((s.scale - .85f) / .05f).toInt()
                contentDescription = "Размер текста"
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onStartTrackingTouch(bar: SeekBar?) {}

                        override fun onProgressChanged(bar: SeekBar?, p: Int, user: Boolean) {
                            size.text = "Размер текста: ${85+p*5}%"
                        }

                        override fun onStopTrackingTouch(bar: SeekBar) {
                            s.scale = .85f + bar.progress * .05f
                            s.savePreferences()
                            a.render()
                        }
                    }
                )
            }
        )
        fun switch(title: String, value: Boolean, change: (Boolean) -> Unit) {
            content.addView(
                Switch(a).apply {
                    text = title
                    textSize = 15f * u.scale
                    setTextColor(u.ink)
                    setPadding(0, u.dp(12), 0, u.dp(12))
                    isChecked = value
                    setOnCheckedChangeListener { _, v ->
                        change(v)
                        s.savePreferences()
                        a.render()
                    }
                }
            )
        }
        switch("Плавные анимации", s.animations) { s.animations = it }
        switch("Обновлять после входа", s.autoRefresh) { s.autoRefresh = it }
        switch("Подтверждать смену статуса", s.confirmChanges) { s.confirmChanges = it }
        content.addView(
            u.button("Очистить кеш задач 1С", secondary = true) {
                AlertDialog.Builder(a)
                    .setTitle("Очистить кеш задач 1С?")
                    .setMessage(
                        "Ваши взятые, выполненные задачи и незавершённые проверки останутся на телефоне."
                    )
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Очистить") { _, _ -> s.clearCache() }
                    .show()
            }
        )
        content.addView(
            u.button("Выйти из профиля", !s.busy, true) {
                AlertDialog.Builder(a)
                    .setTitle("Выйти?")
                    .setMessage(
                        "Ваши подключения и задачи сохранятся для следующего входа на этом телефоне."
                    )
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Выйти") { _, _ -> s.logout() }
                    .show()
            }
        )
        content.addView(
            u.text("Задачи / Dev · 1.3.0\nПодключения 1С хранятся на телефоне", 12f, false, u.muted)
        )
        u.fill(root, u.scroll(content).apply { tag = "scroll" })
    }

    fun hideKeyboard() {
        (a.getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(a.currentFocus?.windowToken, 0)
        a.currentFocus?.clearFocus()
    }
}
