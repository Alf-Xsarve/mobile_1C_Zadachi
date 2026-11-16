package kg.devtasks.app.data

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import kg.devtasks.app.core.*

/** Только ссылки: бинарные файлы не загружаются и Firebase Storage не используется. */
class TaskLinks(app: FirebaseApp?, private val changed: () -> Unit) {
    private val auth = app?.let { FirebaseAuth.getInstance(it) }
    private val db = app?.let { FirebaseFirestore.getInstance(it) }
    private val pending = mutableMapOf<String, MutableList<TaskLink>>()
    private var listener: ListenerRegistration? = null
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

    private fun message(e: Exception): String =
        generateSequence(e as Throwable) { it.cause }
            .filterIsInstance<UserError>()
            .firstOrNull()
            ?.message
            ?: if (
                e is FirebaseFirestoreException &&
                    e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
            )
                "Нет доступа к ссылкам. Администратору нужно обновить правила Firestore."
            else
                "Не удалось сохранить или загрузить ссылки. Проверьте интернет и повторите попытку."

    fun watch(taskId: String?) {
        listener?.remove()
        listener = null
        watched = taskId
        items = emptyList()
        error = null
        loading = false
        val uid = auth?.currentUser?.uid ?: return
        val firestore = db ?: return
        if (taskId == null) return
        loading = true
        listener =
            firestore
                .collection("tasks")
                .document(taskId)
                .collection("links")
                .orderBy("createdAt")
                .addSnapshotListener { result, e ->
                    if (closed || watched != taskId || auth.currentUser?.uid != uid)
                        return@addSnapshotListener
                    loading = false
                    if (e != null) {
                        error = message(e)
                        changed()
                        return@addSnapshotListener
                    }
                    var invalid = false
                    items =
                        result?.documents.orEmpty().mapNotNull { doc ->
                            runCatching { TaskLink.from(taskId, doc.id, doc.data.orEmpty()) }
                                .getOrElse {
                                    invalid = true
                                    null
                                }
                        }
                    error =
                        if (invalid) "Некоторые ссылки имеют неверный формат и не показаны."
                        else null
                    changed()
                }
    }

    fun add(
        taskId: String,
        name: String,
        address: String,
        kind: String,
        authorName: String,
        publishNow: Boolean,
    ): Boolean {
        val uid = auth?.currentUser?.uid ?: return false
        if (busy) return false
        try {
            val data = TaskLink.create(taskId, name, address, kind, uid, authorName)
            if (pendingFor(taskId).size >= 20)
                throw UserError("Отправьте уже добавленные ссылки перед добавлением новых.")
            pending.getOrPut(taskId) { mutableListOf() }.add(data)
            error = null
            if (publishNow) publish(taskId) else changed()
            return true
        } catch (e: Exception) {
            error = message(e)
            changed()
            return false
        }
    }

    fun removePending(taskId: String, id: String) {
        if (busy) return
        pending[taskId]?.removeAll { it.id == id }
        error = null
        changed()
    }

    fun discard(taskId: String) {
        pending.remove(taskId)
        error = null
    }

    fun publish(taskId: String, done: (Boolean) -> Unit = {}) {
        val uid = auth?.currentUser?.uid ?: return
        val firestore = db ?: return
        if (busy) return
        val queue = pendingFor(taskId)
        if (queue.isEmpty()) {
            done(true)
            return
        }
        val g = ++epoch
        busy = true
        error = null
        changed()
        // Одна транзакция публикует весь набор. ID сохраняются при повторе запроса.
        firestore
            .runTransaction { tx ->
                val refs =
                    queue.map {
                        firestore
                            .collection("tasks")
                            .document(taskId)
                            .collection("links")
                            .document(it.id)
                    }
                val old = refs.map { tx.get(it) }
                queue.forEachIndexed { index, link ->
                    link.validate()
                    if (link.authorId != uid)
                        throw UserError("Ссылка относится к другому аккаунту.")
                    if (!old[index].exists())
                        tx.set(
                            refs[index],
                            link.fields() + ("createdAt" to FieldValue.serverTimestamp()),
                        )
                    else if (link.fields().any { (key, value) -> old[index].get(key) != value })
                        throw UserError("Ссылка уже существует с другими данными.")
                }
            }
            .addOnSuccessListener {
                if (closed || epoch != g || auth.currentUser?.uid != uid)
                    return@addOnSuccessListener
                pending[taskId]?.removeAll { p -> queue.any { it.id == p.id } }
                busy = false
                done(true)
                changed()
            }
            .addOnFailureListener {
                if (closed || epoch != g || auth.currentUser?.uid != uid)
                    return@addOnFailureListener
                busy = false
                error = message(it)
                done(false)
                changed()
            }
    }

    fun reset() {
        epoch++
        listener?.remove()
        listener = null
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
