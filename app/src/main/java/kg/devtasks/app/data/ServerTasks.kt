package kg.devtasks.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.time.Instant
import java.util.concurrent.Executors
import kg.devtasks.app.core.*

/** Shared PostgreSQL tasks. Every state callback runs on the Android main thread. */
class ServerTasks(
    context: Context,
    private val changed: () -> Unit,
    val api: SharedApi = SharedApi(),
    storageScope: String = api.baseUrl,
) {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    internal val storage = SharedStorage(context, storageScope)
    private var session: Row? =
        storage.read("session")?.takeIf {
            runCatching { Instant.parse(it["expiresAt"].text()).isAfter(Instant.now()) }
                .getOrDefault(false)
        }
    val configured = true
    val account
        get() = session?.get("user")?.row()?.let(SharedApi::account)

    val profileName
        get() = account?.displayName.orEmpty()

    val links = TaskLinks(this, changed)
    val files = TaskFiles(this, changed)
    var ready = account != null
        private set

    var items: List<CloudTask> = emptyList()
        private set

    var loading = false
        private set

    var busy = false
        private set

    var fromCache = false
        private set

    var error: String? = null
    var query = ""
    var filter = "all"
    var selectedId: String? = null
        set(value) {
            field = value
            links.watch(value)
            files.watch(value)
        }

    var draft: CloudDraft? = null
    var registering = false
    var email = ""
    var name = ""
    var password = ""
    var repeat = ""
    var limit = 100L
        private set

    var hasMore = false
        private set

    private var generation = 0
    private var closed = false
    private var foreground = true

    fun foreground(value: Boolean) {
        foreground = value
    }

    private val poll =
        object : Runnable {
            override fun run() {
                if (closed) return
                if (foreground && ready && !busy && !loading && !links.busy && draft == null)
                    listen(silent = true)
                main.postDelayed(this, 15000)
            }
        }

    init {
        restoreCache()
        main.post { if (!closed && account != null) connect() }
        main.postDelayed(poll, 15000)
    }

    private fun restoreCache() {
        val uid = account?.uid ?: return
        storage.read("tasks:$uid")?.let {
            items = it["items"].rows().map(SharedApi::task)
            fromCache = true
        }
    }

    internal fun token() =
        session?.get("token").text().ifBlank { throw UserError("Войдите в аккаунт.") }

    internal fun <T> work(task: () -> T, done: (T) -> Unit, fail: (Exception) -> Unit) {
        val g = generation
        if (closed) return
        pool.execute {
            val result = runCatching(task)
            main.post {
                if (!closed && generation == g)
                    result.fold(done) { fail(it as? Exception ?: Exception(it)) }
            }
        }
    }

    internal fun failed(e: Exception) {
        if (e is SharedApiError && e.status == 401) {
            generation++
            session = null
            runCatching { storage.write("session", null) }
            ready = false
            items = emptyList()
            selectedId = null
            draft = null
            links.reset()
            files.reset()
            loading = false
            busy = false
        }
        error = SharedApi.message(e)
        changed()
    }

    fun authenticate(
        register: Boolean,
        email: String,
        password: String,
        name: String,
        repeat: String,
        done: (Boolean) -> Unit = {},
    ) {
        if (busy) return
        if (
            !email.contains('@') ||
                password.length !in (if (register) 12 else 1)..256 ||
                (register && (name.trim().length !in 1..80 || password != repeat))
        ) {
            error =
                "Укажите email и пароль. При регистрации: имя, пароль от 12 символов и совпадающее подтверждение."
            changed()
            done(false)
            return
        }
        busy = true
        error = null
        changed()
        work(
            {
                val reply =
                    api.request(
                        "POST",
                        "/api/auth/${if(register) "register" else "login"}",
                        body =
                            mapOf(
                                "email" to email.trim(),
                                "password" to password,
                                "name" to name.trim(),
                            ),
                    )
                require(
                    reply["token"].text().isNotBlank() &&
                        reply["user"].row()["id"].text().isNotBlank()
                )
                reply
            },
            { reply ->
                try {
                    storage.write("session", reply)
                } catch (e: Exception) {
                    busy = false
                    error = "Не удалось сохранить сеанс на телефоне. Повторите вход."
                    changed()
                    done(false)
                    return@work
                }
                generation++
                links.reset()
                files.reset()
                items = emptyList()
                selectedId = null
                draft = null
                session = reply
                ready = true
                busy = false
                this.password = ""
                this.repeat = ""
                restoreCache()
                listen()
                done(true)
                changed()
            },
            {
                busy = false
                error = SharedApi.message(it)
                changed()
                done(false)
            },
        )
    }

    fun connect(done: (Boolean) -> Unit = {}) {
        if (closed || account == null || busy) return
        busy = true
        error = null
        changed()
        val bearer = token()
        val old = session!!
        work(
            {
                val user = api.request("GET", "/api/auth/me", bearer)
                old + ("user" to user)
            },
            {
                session = it
                runCatching { storage.write("session", it) }
                ready = true
                busy = false
                listen()
                done(true)
                changed()
            },
            {
                busy = false
                failed(it)
                done(ready)
            },
        )
    }

    /**
     * Refresh the loaded range from page one, so stale assignments cannot linger on older pages.
     */
    fun listen(loadMore: Boolean = false, silent: Boolean = false) {
        if (!ready || closed || loading || account == null) return
        val target = if (loadMore) limit + 100 else limit
        val bearer = token()
        val uid = account!!.uid
        loading = true
        if (!silent) {
            error = null
            changed()
        }
        work(
            {
                val rows = mutableListOf<Row>()
                var cursor = ""
                do {
                    val page =
                        api.request(
                            "GET",
                            "/api/tasks",
                            bearer,
                            query =
                                buildMap {
                                    put("limit", "100")
                                    if (cursor.isNotEmpty()) put("cursor", cursor)
                                },
                        )
                    rows += page["items"].rows()
                    cursor = page["nextCursor"].text()
                } while (cursor.isNotBlank() && rows.size < target)
                storage.write("tasks:$uid", mapOf("items" to rows))
                Triple(rows.map(SharedApi::task).distinctBy { it.id }, cursor.isNotBlank(), target)
            },
            { result ->
                val previous = items
                val wasCached = fromCache
                val previousError = error
                items = result.first
                hasMore = result.second
                limit = result.third
                fromCache = false
                loading = false
                error = null
                if (!silent || previous != items || wasCached || previousError != null) changed()
                if (silent && selectedId != null && !links.busy) links.watch(selectedId)
            },
            {
                loading = false
                fromCache = items.isNotEmpty()
                failed(it)
            },
        )
    }

    fun visible(): List<CloudTask> {
        val uid = account?.uid.orEmpty()
        return items.filter {
            (filter == "all" ||
                filter == "mine" && it.assigneeId == uid ||
                filter == "done" && it.status == "done" ||
                filter == "new" && it.status == "new") &&
                (query.isBlank() ||
                    "${it.title} ${it.description} ${it.authorName} ${it.assigneeName}"
                        .contains(query.trim(), true))
        }
    }

    private fun replace(task: CloudTask) {
        items =
            (listOf(task) + items.filterNot { it.id == task.id }).sortedByDescending {
                it.createdAt
            }
    }

    private fun cacheMutation(uid: String, row: Row): CloudTask {
        val key = "tasks:$uid"
        val old = storage.read(key)?.get("items").rows()
        storage.write(
            key,
            mapOf("items" to (listOf(row) + old.filterNot { it["id"] == row["id"] })),
        )
        return SharedApi.task(row)
    }

    fun create() {
        val d = draft ?: return
        if (busy || links.busy || !ready) return
        try {
            d.validate()
        } catch (e: Exception) {
            failed(e)
            return
        }
        busy = true
        error = null
        changed()
        val bearer = token()
        val uid = account!!.uid
        fun publish() {
            files.publish(d.id) uploaded@{ uploaded ->
                if (!uploaded) {
                    busy = false
                    changed()
                    return@uploaded
                }
                links.publish(d.id) { ok ->
                    busy = false
                    if (ok) {
                        draft = null
                        selectedId = d.id
                        listen()
                    }
                    changed()
                }
            }
        }
        if (d.created) {
            publish()
            return
        }
        work(
            {
                cacheMutation(
                    uid,
                    api.request(
                        "POST",
                        "/api/tasks",
                        bearer,
                        mapOf(
                            "id" to d.id,
                            "title" to d.title.trim(),
                            "description" to d.description,
                        ),
                    ),
                )
            },
            {
                replace(it)
                d.created = true
                publish()
            },
            {
                busy = false
                failed(it)
            },
        )
    }

    fun change(task: CloudTask, complete: Boolean) {
        if (busy || links.busy || !ready) return
        busy = true
        error = null
        changed()
        val bearer = token()
        val uid = account!!.uid
        work(
            {
                cacheMutation(
                    uid,
                    api.request(
                        "POST",
                        "/api/tasks/${task.id}/${if(complete) "complete" else "claim"}",
                        bearer,
                    ),
                )
            },
            {
                replace(it)
                busy = false
                listen()
                changed()
            },
            {
                busy = false
                failed(it)
                if (it is SharedApiError && it.status == 409) listen()
            },
        )
    }

    fun action(task: CloudTask, action: String, target: String? = null) {
        if (busy || files.busy || links.busy || !ready) return
        val bearer = token()
        val userId = account!!.uid
        busy = true
        error = null
        changed()
        work(
            {
                cacheMutation(
                    userId,
                    api.request(
                        "POST",
                        "/api/tasks/${task.id}/action",
                        bearer,
                        mapOf("action" to action, "target" to target),
                    ),
                )
            },
            {
                replace(it)
                busy = false
                listen()
                changed()
            },
            {
                busy = false
                failed(it)
            },
        )
    }

    fun users(done: (List<Row>) -> Unit) {
        val bearer = token()
        work(
            { api.request("GET", "/api/users", bearer)["items"].rows() },
            { done(it) },
            { failed(it) },
        )
    }

    fun signOut() {
        val bearer = session?.get("token").text()
        try {
            storage.write("session", null)
        } catch (e: Exception) {
            error = "Не удалось удалить сеанс с телефона. Повторите выход."
            changed()
            return
        }
        generation++
        session = null
        links.reset()
        files.reset()
        items = emptyList()
        ready = false
        busy = false
        loading = false
        selectedId = null
        draft = null
        query = ""
        filter = "all"
        error = null
        password = ""
        repeat = ""
        limit = 100
        hasMore = false
        // Local logout always succeeds, even without a network. Server sessions also expire.
        if (bearer.isNotBlank())
            pool.execute { runCatching { api.request("POST", "/api/auth/logout", bearer) } }
        changed()
    }

    fun discardDraft() {
        val d = draft ?: return
        draft = null
        files.discard(d.id)
        if (d.created) selectedId = d.id else links.discard(d.id)
    }

    fun close() {
        closed = true
        generation++
        main.removeCallbacks(poll)
        links.close()
        files.reset()
        pool.shutdown()
    }
}
