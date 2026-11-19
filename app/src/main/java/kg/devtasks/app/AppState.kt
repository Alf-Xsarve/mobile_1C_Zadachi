package kg.devtasks.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import kg.devtasks.app.core.*
import kg.devtasks.app.data.*

data class Editor(
    val id: String = uid(),
    var name: String = "",
    var url: String = "",
    var login: String = "",
    var password: String = "",
    val existing: Boolean = false,
    var busy: Boolean = false,
    var error: String? = null,
)

class AppState(
    context: Context,
    cloudFactory: (Context, () -> Unit) -> ServerTasks = { app, changed ->
        ServerTasks(app, changed)
    },
) {
    val store = LocalStore(context.applicationContext)
    private val pool =
        object :
            java.util.concurrent.ThreadPoolExecutor(
                4,
                4,
                0L,
                java.util.concurrent.TimeUnit.MILLISECONDS,
                java.util.concurrent.LinkedBlockingQueue<Runnable>(),
            ) {
            override fun terminated() {
                store.close()
            }
        }
    private val main = Handler(Looper.getMainLooper())
    private val clients = ConcurrentHashMap<String, TaskApi>()
    private val locks = ConcurrentHashMap<String, Any>()
    @Volatile private var epoch = 0
    @Volatile private var closed = false
    var render: (() -> Unit)? = null
    var user: User? = null
        private set

    var sources: List<Source> = emptyList()
        private set

    var tasks: List<TaskItem> = emptyList()
        private set

    var pending: List<Pending> = emptyList()
        private set

    val errors = mutableMapOf<String, String>()
    val synced = mutableMapOf<String, Long>()
    var boot = true
    var busy = false
    var syncing = false
    var error: String? = null
    var registering = false
    var authName = ""
    var authLogin = ""
    var authPassword = ""
    var authRepeat = ""
    var tab = "all"
    var page = "home"
    var detailId: String? = null
    var query = ""
    var filter: Phase? = null
    var sourceFilter: String? = null
    var editor: Editor? = null
    var style = "yellow"
    var theme = "system"
    var scale = 1f
    var animations = true
    var autoRefresh = true
    var confirmChanges = true
    val offsets = mutableMapOf<String, Int>()
    val more = mutableMapOf<String, Boolean>()
    val working = mutableSetOf<String>()
    val cloud = cloudFactory(context.applicationContext) { notifyUi() }
    var localAuth = !cloud.configured

    init {
        job(
            {
                val uid = store.setting("session")
                uid?.takeUnless { it.startsWith("firebase:") }?.let { store.user(it) }
                    ?: cloud.account?.let {
                        store
                            .serverUser(
                                it.uid,
                                it.email.orEmpty(),
                                it.displayName?.ifBlank { null } ?: it.email.orEmpty(),
                            )
                            .also { user -> store.setting("session", user.id) }
                    }
            },
            { u ->
                boot = false
                if (u != null) openUser(u)
            },
            {
                boot = false
                error = "Не удалось открыть локальные данные. Данные не удалены."
            },
        )
    }

    fun notifyUi() {
        if (!closed) render?.invoke()
    }

    fun <T> job(
        task: () -> T,
        done: (T) -> Unit,
        fail: (Exception) -> Unit = { error = message(it) },
    ) {
        val generation = epoch
        pool.execute {
            if (closed || generation != epoch) return@execute
            val result = runCatching(task)
            main.post {
                if (!closed && generation == epoch) {
                    result.fold(done, { fail(it as? Exception ?: Exception(it)) })
                    notifyUi()
                }
            }
        }
    }

    fun message(e: Exception): String =
        when (e) {
            is UserError,
            is Conflict,
            is UnknownOutcome -> e.message.orEmpty()
            is java.net.SocketTimeoutException,
            is java.io.InterruptedIOException ->
                "1С не ответила вовремя. Сохранённые задачи доступны на телефоне."
            is java.net.ConnectException,
            is java.net.UnknownHostException ->
                "Нет связи с базой. Сохранённые задачи доступны на телефоне."
            is javax.net.ssl.SSLException -> "Не удалось проверить сертификат HTTPS."
            is android.database.sqlite.SQLiteConstraintException ->
                "Такое подключение или логин уже существуют."
            else -> "Не удалось выполнить действие. Повторите после проверки подключения."
        }

    fun auth() {
        if (busy) return
        if (cloud.configured && !localAuth) {
            if (cloud.busy) return
            busy = true
            error = null
            cloud.authenticate(registering, authLogin, authPassword, authName, authRepeat) { ok ->
                if (!ok) {
                    busy = false
                    error = cloud.error
                    notifyUi()
                } else {
                    val identity = cloud.account!!
                    job(
                        {
                            val u =
                                store.serverUser(
                                    identity.uid,
                                    identity.email.orEmpty(),
                                    cloud.profileName,
                                )
                            store.setting("session", u.id)
                            u
                        },
                        { u ->
                            busy = false
                            authPassword = ""
                            authRepeat = ""
                            openUser(u)
                        },
                        {
                            busy = false
                            error = message(it)
                        },
                    )
                }
            }
            notifyUi()
            return
        }
        busy = true
        error = null
        notifyUi()
        val registering = this.registering
        val name = authName
        val login = authLogin
        val pass = authPassword
        val repeat = authRepeat
        job(
            {
                val u =
                    if (registering) store.register(login, name, pass, repeat)
                    else store.login(login, pass)
                store.setting("session", u.id)
                u
            },
            { u ->
                busy = false
                authPassword = ""
                authRepeat = ""
                openUser(u)
            },
            {
                busy = false
                error = message(it)
            },
        )
    }

    private fun openUser(u: User) {
        user = u
        boot = true
        job(
            {
                val settings = store.setting("prefs:${u.id}")?.let { Json.obj(it) }.orEmpty()
                Triple(store.sources(u.id), store.tasks(u.id), settings)
            },
            { (sources, tasks, prefs) ->
                this.sources = sources
                this.tasks = tasks
                pending = store.pending(u.id)
                style = prefs["style"].text().ifBlank { "yellow" }
                theme = prefs["theme"].text().ifBlank { "system" }
                scale = ((prefs["scale"] as? Number)?.toFloat() ?: 1f).coerceIn(.85f, 1.45f)
                animations = prefs["animations"] != false
                autoRefresh = prefs["autoRefresh"] != false
                confirmChanges = prefs["confirm"] != false
                boot = false
                page = if (u.id.startsWith("api:")) "cloud" else "home"
                tab = "all"
                if (autoRefresh && sources.isNotEmpty()) refresh()
            },
            {
                boot = false
                error = message(it)
            },
        )
    }

    fun logout() {
        if (busy) return
        busy = true
        notifyUi()
        job(
            { store.setting("session", null) },
            {
                epoch++
                clients.values.forEach { it.close() }
                clients.clear()
                cloud.signOut()
                user = null
                registering = false
                authName = ""
                authRepeat = ""
                error = null
                query = ""
                filter = null
                sourceFilter = null
                sources = emptyList()
                tasks = emptyList()
                pending = emptyList()
                working.clear()
                errors.clear()
                synced.clear()
                offsets.clear()
                more.clear()
                syncing = false
                busy = false
                page = "home"
                tab = "all"
                detailId = null
                editor = null
                authPassword = ""
                error = null
            },
        )
    }

    fun savePreferences() {
        val actor = user ?: return
        val value =
            Json.encode(
                mapOf(
                    "style" to style,
                    "theme" to theme,
                    "scale" to scale,
                    "animations" to animations,
                    "autoRefresh" to autoRefresh,
                    "confirm" to confirmChanges,
                )
            )
        job({ store.setting("prefs:${actor.id}", value) }, {})
    }

    private fun api(source: Source): TaskApi =
        clients.getOrPut(source.id) {
            TaskApi(source.url, source.mapping, source.id, HttpWire(source.login, source.password))
        }

    private fun lock(source: Source) = locks.getOrPut(source.id) { Any() }

    private fun reload(actor: User) {
        job(
            { store.tasks(actor.id) to store.pending(actor.id) },
            { (values, changes) ->
                tasks = values
                pending = changes
            },
        )
    }

    fun openEditor(source: Source? = null) {
        editor =
            if (source == null) Editor()
            else Editor(source.id, source.name, source.url, source.login, source.password, true)
        page = "editor"
        error = null
        notifyUi()
    }

    fun addSource() {
        val actor = user ?: return
        val e = editor ?: return
        if (e.busy) return
        val address =
            try {
                Source.normalizeUrl(e.url).also {
                    Source.credentials(e.login, e.password)
                    if (e.name.isBlank() || e.name.length > 60)
                        throw UserError("Укажите короткое название базы.")
                }
            } catch (x: Exception) {
                e.error = message(x)
                notifyUi()
                return
            }
        val existing = sources.find { it.id == e.id }
        if (existing != null && address != existing.url) {
            e.error = "Для другого адреса добавьте отдельную базу."
            notifyUi()
            return
        }
        if (sources.any { it.id != e.id && it.url == address }) {
            e.error = "Эта база уже подключена."
            notifyUi()
            return
        }
        e.busy = true
        e.error = null
        notifyUi()
        val source =
            Source(
                e.id,
                actor.id,
                e.name.trim(),
                address,
                e.login.trim(),
                e.password,
                programmerMapping(),
            )
        job(
            {
                val wire = HttpWire(source.login, source.password)
                try {
                    val model = TaskApi.metadata(source.url, wire)
                    TaskApi.validate(model, source.mapping)
                    val check = TaskApi(source.url, source.mapping, source.id, wire)
                    val rows = check.page()
                    store.saveSource(source)
                    store.merge(actor.id, source.id, rows)
                    rows.size
                } finally {
                    wire.close()
                }
            },
            { size ->
                e.busy = false
                clients.remove(source.id)?.close()
                sources = store.sources(actor.id)
                reload(actor)
                offsets[source.id] = size
                more[source.id] = size == 50
                errors.remove(source.id)
                synced[source.id] = System.currentTimeMillis()
                page = "sources"
                editor = null
            },
            {
                e.busy = false
                e.error = message(it)
            },
        )
    }

    fun importCode(code: String) {
        if (user == null || editor?.busy == true) return
        try {
            val connection = ConnectionCode.parse(code)
            openEditor()
            editor!!.apply {
                name = connection.name
                url = connection.url
                login = connection.login
                password = connection.password
            }
            addSource()
        } catch (e: Exception) {
            error = message(e)
            notifyUi()
        }
    }

    fun removeSource(source: Source) {
        val actor = user ?: return
        if (syncing || working.isNotEmpty()) return
        busy = true
        notifyUi()
        job(
            {
                synchronized(lock(source)) {
                    clients.remove(source.id)?.close()
                    store.removeSource(actor.id, source.id)
                }
            },
            {
                busy = false
                sources = store.sources(actor.id)
                reload(actor)
                errors.remove(source.id)
                detailId = null
                page = "sources"
            },
            {
                busy = false
                error = message(it)
            },
        )
    }

    private fun reconcile(actor: User, source: Source, values: List<Row>) {
        val active = api(source)
        store.merge(actor.id, source.id, values)
        val byKey = values.associateBy { it["Ref_Key"].text().lowercase() }
        store
            .pending(actor.id)
            .filter { it.sourceId == source.id }
            .forEach { p ->
                val row = byKey[p.key.lowercase()] ?: return@forEach
                val task = TaskItem(source.id, p.key, row)
                if (active.matches(p, task)) store.confirm(actor.id, p, task)
                else if (task.version.isNotBlank() && task.version != p.version)
                    store.discard(actor.id, p)
            }
    }

    fun refresh(loadMore: Boolean = false) {
        val actor = user ?: return
        if (syncing) return
        val bases = sources.filter { it.enabled && (!loadMore || more[it.id] != false) }
        if (bases.isEmpty()) return
        syncing = true
        error = null
        var remaining = bases.size
        notifyUi()
        bases.forEach { source ->
            job(
                {
                    synchronized(lock(source)) {
                        val active = api(source)
                        val skip = if (loadMore) offsets[source.id] ?: 0 else 0
                        val values = active.page(skip)
                        reconcile(actor, source, values)
                        if (!loadMore) {
                            val have = values.map { it["Ref_Key"].text().lowercase() }.toSet()
                            val mine =
                                store.tasks(actor.id).filter {
                                    it.sourceId == source.id &&
                                        (it.taken || it.completed) &&
                                        it.key !in have
                                }
                            mine.chunked(20).forEach { chunk ->
                                val rows = active.batch(chunk.map { it.key })
                                reconcile(actor, source, rows)
                                val returned = rows.map { it["Ref_Key"].text().lowercase() }.toSet()
                                chunk
                                    .filter { it.key !in returned }
                                    .forEach { store.unavailable(actor.id, it) }
                            }
                        }
                        skip + values.size to (values.size == 50)
                    }
                },
                { (offset, hasMore) ->
                    offsets[source.id] = offset
                    more[source.id] = hasMore
                    errors.remove(source.id)
                    synced[source.id] = System.currentTimeMillis()
                    reload(actor)
                    remaining--
                    if (remaining == 0) syncing = false
                },
                {
                    errors[source.id] = message(it)
                    remaining--
                    if (remaining == 0) syncing = false
                    reload(actor)
                },
            )
        }
    }

    fun refreshTask(task: TaskItem) {
        val actor = user ?: return
        val source = sources.find { it.id == task.sourceId } ?: return
        if (!working.add(task.id)) return
        notifyUi()
        job(
            {
                synchronized(lock(source)) {
                    val row = api(source).read(task.key)
                    if (row == null) store.unavailable(actor.id, task)
                    else reconcile(actor, source, listOf(row.raw))
                }
            },
            {
                working.remove(task.id)
                reload(actor)
            },
            {
                working.remove(task.id)
                error = message(it)
                reload(actor)
            },
        )
    }

    fun change(task: TaskItem, phase: Phase) {
        val actor = user ?: return
        val source = sources.find { it.id == task.sourceId } ?: return
        if (
            working.contains(task.id) ||
                pending.any { it.sourceId == task.sourceId && it.key == task.key }
        )
            return
        working += task.id
        error = null
        notifyUi()
        job(
            {
                synchronized(lock(source)) {
                    val active = api(source)
                    val (fresh, p) = active.prepare(task, actor, phase, System.currentTimeMillis())
                    store.merge(actor.id, source.id, listOf(fresh.raw))
                    store.begin(actor.id, p)
                    try {
                        val accepted = active.apply(p)
                        val updated = runCatching { active.read(task.key) }.getOrNull()
                        val confirmed =
                            updated ?: fresh.copy(raw = (fresh.raw + accepted.raw) - "DataVersion")
                        store.confirm(actor.id, p, confirmed)
                    } catch (e: Exception) {
                        when (e) {
                            is UnknownOutcome -> {}
                            is Conflict -> {
                                store.discard(actor.id, p)
                                store.merge(actor.id, source.id, listOf(e.latest.raw))
                            }
                            else -> store.discard(actor.id, p)
                        }
                        throw e
                    }
                }
            },
            {
                working.remove(task.id)
                reload(actor)
            },
            {
                working.remove(task.id)
                if (it is Conflict)
                    runCatching { store.merge(actor.id, source.id, listOf(it.latest.raw)) }
                error = message(it)
                reload(actor)
            },
        )
    }

    fun clearPending(task: TaskItem) {
        val actor = user ?: return
        val p = pending.find { it.key == task.key && it.sourceId == task.sourceId } ?: return
        job({ store.discard(actor.id, p) }, { reload(actor) })
    }

    fun clearCache() {
        val actor = user ?: return
        job({ store.clearUntracked(actor.id) }, { reload(actor) })
    }

    fun visible(): List<TaskItem> {
        val byId = sources.associateBy { it.id }
        return tasks
            .filter { t ->
                val base = byId[t.sourceId] ?: return@filter false
                val phase = t.phase(base.mapping)
                val category =
                    when (tab) {
                        "mine" -> t.taken
                        "done" -> t.completed && phase == Phase.DONE
                        else -> !t.unavailable && t.raw["DeletionMark"] != true
                    }
                category &&
                    (filter == null || phase == filter) &&
                    (sourceFilter == null || sourceFilter == t.sourceId) &&
                    ("${t.title(base.mapping)} ${t.raw["Number"].text()} ${t.raw[base.mapping.description].text()} ${t.raw[base.mapping.comment].text()} ${t.raw[base.mapping.programmer].text()} ${base.name}")
                        .lowercase()
                        .contains(query.trim().lowercase())
            }
            .sortedWith(
                compareByDescending<TaskItem> {
                        it.phase(byId.getValue(it.sourceId).mapping) == Phase.ACTIVE
                    }
                    .thenByDescending { it.raw["Date"].text() }
                    .thenBy { it.title(byId.getValue(it.sourceId).mapping) }
            )
    }

    fun back(): Boolean {
        if (page == "cloud") {
            if (cloud.busy || cloud.links.busy || cloud.files.busy || cloud.files.busy) return true
            when {
                cloud.draft != null -> cloud.discardDraft()
                cloud.selectedId != null -> cloud.selectedId = null
                else -> page = "home"
            }
            cloud.error = null
            notifyUi()
            return true
        }
        if (editor?.busy == true) return true
        return when (page) {
            "detail" -> {
                detailId = null
                page = "home"
                error = null
                notifyUi()
                true
            }
            "editor" -> {
                editor = null
                page = "sources"
                error = null
                notifyUi()
                true
            }
            "sources" -> {
                page = "home"
                tab = "settings"
                error = null
                notifyUi()
                true
            }
            else -> false
        }
    }

    fun close() {
        closed = true
        epoch++
        render = null
        clients.values.forEach { it.close() }
        cloud.close()
        pool.shutdownNow()
        /* SQLite держит собственный пул до завершения фоновых транзакций. */ }
}
