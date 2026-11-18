package kg.devtasks.app.data

import kg.devtasks.app.core.*

/** Pending links keep stable IDs, so a lost response can be retried without duplicates. */
class TaskLinks(private val owner: ServerTasks, private val changed: () -> Unit) {
    private val pending = mutableMapOf<String, MutableList<TaskLink>>()
    private var watched: String? = null
    private var epoch = 0
    private var closed = false
    var items: List<TaskLink> = emptyList()
        private set

    var busy = false
        private set

    var loading = false
        private set

    var error: String? = null
        private set

    fun pendingFor(taskId: String): List<TaskLink> = pending[taskId].orEmpty().toList()

    fun watch(taskId: String?) {
        val g = ++epoch
        watched = taskId
        items = emptyList()
        error = null
        loading = false
        val uid = owner.account?.uid ?: return
        if (taskId == null) return
        val key = "links:$uid:$taskId"
        fun parse(row: Row) = row["items"].rows().map { TaskLink.from(taskId, it["id"].text(), it) }
        owner.storage.read(key)?.let { items = runCatching { parse(it) }.getOrDefault(emptyList()) }
        val bearer = owner.token()
        loading = true
        owner.work(
            { owner.api.request("GET", "/api/tasks/$taskId/links", bearer) },
            {
                if (!closed && epoch == g && watched == taskId) {
                    loading = false
                    try {
                        items = parse(it)
                        owner.storage.write(key, it)
                    } catch (e: Exception) {
                        error = SharedApi.message(e)
                    }
                    changed()
                }
            },
            {
                if (!closed && epoch == g) {
                    loading = false
                    error = SharedApi.message(it)
                    if (it is SharedApiError && it.status == 401) owner.failed(it) else changed()
                }
            },
        )
    }

    fun add(
        taskId: String,
        name: String,
        address: String,
        kind: String,
        authorName: String,
        publishNow: Boolean,
    ): Boolean {
        val uid = owner.account?.uid ?: return false
        if (busy) return false
        return try {
            val link = TaskLink.create(taskId, name, address, kind, uid, authorName)
            if (pendingFor(taskId).size >= 20)
                throw UserError("Сначала отправьте добавленные ссылки.")
            pending.getOrPut(taskId) { mutableListOf() }.add(link)
            error = null
            if (publishNow) publish(taskId) else changed()
            true
        } catch (e: Exception) {
            error = SharedApi.message(e)
            changed()
            false
        }
    }

    fun removePending(taskId: String, id: String) {
        if (!busy) {
            pending[taskId]?.removeAll { it.id == id }
            error = null
            changed()
        }
    }

    fun discard(taskId: String) {
        pending.remove(taskId)
        error = null
    }

    fun publish(taskId: String, done: (Boolean) -> Unit = {}) {
        if (busy || owner.account == null) return
        val queue = pendingFor(taskId)
        if (queue.isEmpty()) {
            done(true)
            return
        }
        val bearer = owner.token()
        val g = epoch
        busy = true
        error = null
        changed()
        owner.work(
            {
                owner.api.request(
                    "POST",
                    "/api/tasks/$taskId/links",
                    bearer,
                    mapOf(
                        "items" to
                            queue.map {
                                mapOf(
                                    "id" to it.id,
                                    "name" to it.name,
                                    "url" to it.url,
                                    "kind" to it.kind,
                                )
                            }
                    ),
                )
            },
            {
                if (!closed && epoch == g) {
                    pending[taskId]?.removeAll { p -> queue.any { it.id == p.id } }
                    busy = false
                    if (watched == taskId) watch(taskId)
                    done(true)
                    changed()
                }
            },
            {
                if (!closed && epoch == g) {
                    busy = false
                    error = SharedApi.message(it)
                    if (it is SharedApiError && it.status == 401) owner.failed(it)
                    done(false)
                    changed()
                }
            },
        )
    }

    fun reset() {
        epoch++
        watched = null
        items = emptyList()
        pending.clear()
        busy = false
        loading = false
        error = null
    }

    fun close() {
        closed = true
        reset()
    }
}
