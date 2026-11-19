package kg.devtasks.app.data

import java.io.File
import kg.devtasks.app.core.*

data class Upload(val id: String = uid(), val name: String, val file: File)

class TaskFiles(private val owner: ServerTasks, private val changed: () -> Unit) {
    val pending = mutableMapOf<String, MutableList<Upload>>()
    var items: List<Row> = emptyList()
        private set

    var busy = false
        private set

    var error: String? = null
        private set

    private var epoch = 0

    fun watch(task: String?) {
        val g = ++epoch
        items = emptyList()
        if (task == null || owner.account == null) return
        val token = owner.token()
        owner.work(
            { owner.api.request("GET", "/api/tasks/$task/files", token) },
            {
                if (g == epoch) {
                    items = it["items"].rows()
                    error = null
                    changed()
                }
            },
            {
                if (g == epoch) {
                    error = SharedApi.message(it)
                    changed()
                }
            },
        )
    }

    fun add(task: String, upload: Upload) {
        pending.getOrPut(task) { mutableListOf() }.add(upload)
        changed()
    }

    fun publish(task: String, done: (Boolean) -> Unit) {
        val list = pending[task].orEmpty().toList()
        if (list.isEmpty()) {
            done(true)
            return
        }
        if (busy) return
        val g = epoch
        val token = owner.token()
        busy = true
        changed()
        owner.work(
            { list.forEach { owner.api.file(task, it.id, token, it.file, it.name) } },
            {
                if (g == epoch) {
                    list.forEach { it.file.delete() }
                    pending.remove(task)
                    busy = false
                    error = null
                    done(true)
                }
            },
            {
                if (g == epoch) {
                    busy = false
                    error = SharedApi.message(it)
                    done(false)
                    changed()
                }
            },
        )
    }

    fun discard(task: String) {
        pending.remove(task)?.forEach { it.file.delete() }
    }

    fun reset() {
        epoch++
        pending.values.flatten().forEach { it.file.delete() }
        pending.clear()
        items = emptyList()
        busy = false
        error = null
    }
}
