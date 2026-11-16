package kg.devtasks.app.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.*
import com.google.firebase.firestore.*
import kg.devtasks.app.core.*

/** Firebase отвечает только за общие задачи. Реквизиты OData сюда не отправляются. */
class FirebaseTasks(
    context: Context,
    private val changed: () -> Unit,
    appOverride: FirebaseApp? = null,
) {
    private val app =
        appOverride
            ?: FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?: FirebaseApp.initializeApp(context)
    private val auth = app?.let { FirebaseAuth.getInstance(it) }
    private val db = app?.let { FirebaseFirestore.getInstance(it) }
    val links = TaskLinks(app, changed)
    val configured
        get() = app != null

    val account
        get() = auth?.currentUser

    var profileName = ""
        private set

    var ready = false
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
        }

    var draft: CloudDraft? = null
    var registering = false
    var email = ""
    var name = ""
    var password = ""
    var repeat = ""
    var limit = 100L
        private set

    private var listener: ListenerRegistration? = null
    private var generation = 0
    private var closed = false

    init {
        if (account != null) connect()
    }

    private fun fail(e: Exception) {
        error =
            generateSequence(e as Throwable) { it.cause }
                .filterIsInstance<UserError>()
                .firstOrNull()
                ?.message
                ?: when (e) {
                    is UserError -> e.message
                    is FirebaseNetworkException -> "Нет связи с Firebase. Проверьте интернет."
                    is FirebaseAuthUserCollisionException ->
                        "Этот email уже зарегистрирован. Войдите в аккаунт."
                    is FirebaseAuthWeakPasswordException -> "Выберите более надёжный пароль."
                    is FirebaseAuthInvalidCredentialsException,
                    is FirebaseAuthInvalidUserException -> "Проверьте email и пароль."
                    is FirebaseAuthException ->
                        when (e.errorCode) {
                            "ERROR_OPERATION_NOT_ALLOWED" ->
                                "Вход по email и паролю ещё не включён. Сообщите администратору приложения."
                            else ->
                                "Не удалось войти в Firebase. Проверьте данные и повторите попытку."
                        }
                    is FirebaseFirestoreException ->
                        when (e.code) {
                            FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                                "Нет доступа к общим задачам. Обратитесь к администратору приложения."
                            FirebaseFirestoreException.Code.UNAVAILABLE ->
                                "Нет связи с Firebase. Сохранённые задачи можно просматривать; изменение требует интернета."
                            else ->
                                "Не удалось сохранить или загрузить задачу. Обновите список и проверьте результат."
                        }
                    else ->
                        "Не удалось подключиться к общим задачам. Проверьте интернет и повторите попытку."
                }
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
        val a = auth ?: return
        if (busy) return
        if (
            email.trim().isBlank() ||
                !email.contains('@') ||
                password.length < 8 ||
                (register && (name.trim().length !in 1..80 || password != repeat))
        ) {
            error =
                "Укажите email и пароль от 8 символов. При регистрации нужны имя и совпадающие пароли."
            changed()
            done(false)
            return
        }
        busy = true
        error = null
        val g = generation
        val operation =
            if (register) a.createUserWithEmailAndPassword(email.trim(), password)
            else a.signInWithEmailAndPassword(email.trim(), password)
        operation
            .addOnSuccessListener {
                if (closed || g != generation) return@addOnSuccessListener
                val user = a.currentUser!!
                if (register) {
                    user
                        .updateProfile(
                            UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()
                        )
                        .addOnCompleteListener { connect(done, name.trim()) }
                } else connect(done)
            }
            .addOnFailureListener {
                if (closed || g != generation) return@addOnFailureListener
                busy = false
                fail(it)
                done(false)
            }
        changed()
    }

    fun connect(done: (Boolean) -> Unit = {}, initialName: String? = null) {
        if (closed) return
        val user = account ?: return
        val firestore = db ?: return
        val g = ++generation
        listener?.remove()
        listener = null
        ready = false
        busy = true
        error = null
        val profile = firestore.collection("users").document(user.uid)
        fun accepted(display: String) {
            if (closed || g != generation) return
            profileName = display
            ready = true
            busy = false
            password = ""
            repeat = ""
            listen()
            done(true)
            changed()
        }
        fun online() {
            firestore
                .runTransaction { tx ->
                    val old = tx.get(profile)
                    if (old.exists())
                        old.getString("name") ?: throw UserError("Не удалось прочитать профиль.")
                    else {
                        val display =
                            (initialName
                                    ?: user.displayName
                                    ?: user.email?.substringBefore('@').orEmpty())
                                .trim()
                                .take(80)
                                .ifBlank { "Пользователь" }
                        tx.set(
                            profile,
                            mapOf("name" to display, "createdAt" to FieldValue.serverTimestamp()),
                        )
                        display
                    }
                }
                .addOnSuccessListener { display -> accepted(display) }
                .addOnFailureListener {
                    if (closed || g != generation) return@addOnFailureListener
                    busy = false
                    fail(it)
                    done(false)
                }
        }
        // Профиль неизменяемый: кеш позволяет открыть сохранённые задачи без сети.
        profile
            .get(com.google.firebase.firestore.Source.CACHE)
            .addOnSuccessListener {
                if (closed || g != generation) return@addOnSuccessListener
                val cached = it.getString("name")
                if (cached.isNullOrBlank()) online() else accepted(cached)
            }
            .addOnFailureListener { if (!closed && g == generation) online() }
        changed()
    }

    fun listen(loadMore: Boolean = false) {
        val firestore = db ?: return
        if (!ready || account == null || closed) return
        if (loadMore) limit += 100
        listener?.remove()
        loading = true
        error = null
        val g = generation
        listener =
            firestore
                .collection("tasks")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
                .addSnapshotListener(MetadataChanges.INCLUDE) { result, e ->
                    if (closed || g != generation) return@addSnapshotListener
                    loading = false
                    if (e != null) {
                        fail(e)
                        return@addSnapshotListener
                    }
                    items =
                        result?.documents.orEmpty().map { doc ->
                            CloudTask(
                                doc.id,
                                doc.getString("title").orEmpty(),
                                doc.getString("description").orEmpty(),
                                doc.getString("status").orEmpty(),
                                doc.getString("authorId").orEmpty(),
                                doc.getString("authorName").orEmpty(),
                                doc.getString("assigneeId").orEmpty(),
                                doc.getString("assigneeName").orEmpty(),
                                doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L,
                            )
                        }
                    fromCache = result?.metadata?.isFromCache == true
                    changed()
                }
        changed()
    }

    fun visible(): List<CloudTask> {
        val uid = account?.uid.orEmpty()
        val search = query.trim()
        return items.filter { task ->
            (filter == "all" ||
                (filter == "mine" && task.assigneeId == uid) ||
                (filter == "done" && task.status == "done") ||
                (filter == "new" && task.status == "new")) &&
                (search.isBlank() ||
                    "${task.title} ${task.description} ${task.authorName} ${task.assigneeName}"
                        .contains(search, true))
        }
    }

    fun create() {
        val d = draft ?: return
        val user = account ?: return
        val firestore = db ?: return
        if (busy || links.busy || !ready) return
        fun publishLinks() {
            links.publish(d.id) { ok ->
                if (closed) return@publish
                busy = false
                if (ok) {
                    draft = null
                    selectedId = d.id
                }
                changed()
            }
        }
        if (d.created) {
            busy = true
            error = null
            changed()
            publishLinks()
            return
        }
        try {
            d.validate()
        } catch (e: Exception) {
            fail(e)
            return
        }
        val values =
            mapOf(
                "title" to d.title.trim(),
                "description" to d.description,
                "status" to "new",
                "authorId" to user.uid,
                "authorName" to profileName,
                "assigneeId" to "",
                "assigneeName" to "",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        val ref = firestore.collection("tasks").document(d.id)
        val g = generation
        busy = true
        error = null
        changed()
        firestore
            .runTransaction { tx ->
                val old = tx.get(ref)
                if (!old.exists()) tx.set(ref, values)
                else if (
                    old.getString("authorId") != user.uid ||
                        old.getString("title") != d.title.trim() ||
                        old.getString("description") != d.description
                )
                    throw UserError("Задача уже существует. Обновите список.")
            }
            .addOnSuccessListener {
                if (closed || g != generation) return@addOnSuccessListener
                d.created = true
                publishLinks()
            }
            .addOnFailureListener {
                if (closed || g != generation) return@addOnFailureListener
                busy = false
                fail(it)
            }
    }

    fun change(task: CloudTask, complete: Boolean) {
        val user = account ?: return
        val firestore = db ?: return
        if (busy || links.busy || !ready) return
        val ref = firestore.collection("tasks").document(task.id)
        val g = generation
        busy = true
        error = null
        changed()
        firestore
            .runTransaction { tx ->
                val row = tx.get(ref)
                val status = row.getString("status")
                val assigned = row.getString("assigneeId").orEmpty()
                if (!row.exists()) throw UserError("Задача больше недоступна.")
                if (complete) {
                    if (status == "done" && assigned == user.uid) return@runTransaction
                    if (status != "active" || assigned != user.uid)
                        throw UserError("Завершить задачу может только её исполнитель.")
                    tx.update(
                        ref,
                        mapOf("status" to "done", "updatedAt" to FieldValue.serverTimestamp()),
                    )
                } else {
                    if (status == "active" && assigned == user.uid) return@runTransaction
                    if (status != "new" || assigned.isNotEmpty())
                        throw UserError("Задачу уже взял другой пользователь. Список обновлён.")
                    tx.update(
                        ref,
                        mapOf(
                            "status" to "active",
                            "assigneeId" to user.uid,
                            "assigneeName" to profileName,
                            "updatedAt" to FieldValue.serverTimestamp(),
                        ),
                    )
                }
            }
            .addOnSuccessListener {
                if (closed || g != generation) return@addOnSuccessListener
                busy = false
                changed()
            }
            .addOnFailureListener {
                if (closed || g != generation) return@addOnFailureListener
                busy = false
                fail(it)
            }
    }

    fun resetPassword() {
        if (email.trim().isBlank()) {
            error = "Введите email для восстановления пароля."
            changed()
            return
        }
        auth?.sendPasswordResetEmail(email.trim())?.addOnCompleteListener {
            error =
                "Если аккаунт с таким email существует, на него придёт письмо для восстановления пароля."
            changed()
        }
    }

    fun signOut() {
        generation++
        listener?.remove()
        listener = null
        auth?.signOut()
        links.reset()
        items = emptyList()
        profileName = ""
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
        changed()
    }

    fun close() {
        closed = true
        generation++
        listener?.remove()
        listener = null
        links.close()
    }

    fun discardDraft() {
        val current = draft ?: return
        draft = null
        if (current.created) selectedId = current.id else links.discard(current.id)
    }
}
