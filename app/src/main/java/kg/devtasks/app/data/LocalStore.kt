package kg.devtasks.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.Normalizer
import java.util.Locale
import kg.devtasks.app.core.*

class LocalStore(context: Context) : SQLiteOpenHelper(context, "devtasks.db", null, 1) {
    private val vault = Vault()
    private val passwords = PasswordHash()

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE accounts(id TEXT PRIMARY KEY, login TEXT NOT NULL UNIQUE, name TEXT NOT NULL, salt BLOB NOT NULL, hash BLOB NOT NULL, rounds INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE sources(id TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES accounts(id), fingerprint TEXT NOT NULL, data BLOB NOT NULL, UNIQUE(owner,fingerprint))"
        )
        db.execSQL(
            "CREATE TABLE tasks(source TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE, key TEXT NOT NULL, data BLOB NOT NULL, PRIMARY KEY(source,key))"
        )
        db.execSQL(
            "CREATE TABLE pending(source TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE, key TEXT NOT NULL, data BLOB NOT NULL, PRIMARY KEY(source,key))"
        )
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,data BLOB NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        error("Неизвестная версия базы. Данные не удалены.")
    }

    private fun canonical(login: String) =
        Normalizer.normalize(login.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    fun register(login: String, name: String, password: String, repeat: String): User {
        val normalized = canonical(login)
        if (!Regex("^[\\p{L}\\p{N}_.-]{3,40}$").matches(normalized))
            throw UserError("Логин: 3–40 букв, цифр, точек, дефисов или подчёркиваний.")
        if (name.trim().isEmpty() || name.length > 80)
            throw UserError("Введите имя до 80 символов.")
        if (password.length !in 8..128) throw UserError("Пароль приложения: от 8 до 128 символов.")
        if (password != repeat) throw UserError("Пароли не совпадают.")
        if (findUser(normalized) != null)
            throw UserError("Такой логин уже зарегистрирован на телефоне.")
        val p = passwords.create(password)
        val user = User(uid(), normalized, name.trim())
        val values =
            ContentValues().apply {
                put("id", user.id)
                put("login", user.login)
                put("name", user.name)
                put("salt", p.salt)
                put("hash", p.hash)
                put("rounds", p.iterations)
            }
        writableDatabase.insertOrThrow("accounts", null, values)
        return user
    }

    private fun findUser(login: String): User? =
        readableDatabase
            .rawQuery("SELECT id,login,name FROM accounts WHERE login=?", arrayOf(login))
            .use { c ->
                if (c.moveToFirst()) User(c.getString(0), c.getString(1), c.getString(2)) else null
            }

    fun login(login: String, password: String): User {
        val normalized = canonical(login)
        val proof =
            readableDatabase
                .rawQuery(
                    "SELECT salt,hash,rounds FROM accounts WHERE login=?",
                    arrayOf(normalized),
                )
                .use { c ->
                    if (c.moveToFirst()) PasswordProof(c.getBlob(0), c.getBlob(1), c.getInt(2))
                    else null
                }
        if (proof == null || !passwords.verify(password, proof))
            throw UserError("Неверный логин или пароль приложения.")
        return findUser(normalized)!!
    }

    fun user(id: String): User? =
        readableDatabase
            .rawQuery("SELECT id,login,name FROM accounts WHERE id=?", arrayOf(id))
            .use {
                if (it.moveToFirst()) User(it.getString(0), it.getString(1), it.getString(2))
                else null
            }

    fun setting(key: String): String? =
        readableDatabase.rawQuery("SELECT data FROM settings WHERE key=?", arrayOf(key)).use {
            if (it.moveToFirst()) vault.open(it.getBlob(0), "settings:$key") else null
        }

    fun setting(key: String, value: String?) {
        if (value == null) {
            writableDatabase.delete("settings", "key=?", arrayOf(key))
            return
        }
        writableDatabase.insertWithOnConflict(
            "settings",
            null,
            ContentValues().apply {
                put("key", key)
                put("data", vault.seal(value, "settings:$key"))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun sources(owner: String): List<Source> =
        readableDatabase
            .rawQuery("SELECT id,data FROM sources WHERE owner=? ORDER BY rowid", arrayOf(owner))
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        Source.from(Json.obj(vault.open(c.getBlob(1), "source:${c.getString(0)}")))
                    )
                }
            }

    fun saveSource(source: Source) {
        source.mapping.validate()
        val exists =
            readableDatabase
                .rawQuery("SELECT owner FROM sources WHERE id=?", arrayOf(source.id))
                .use { if (it.moveToFirst()) it.getString(0) else null }
        if (exists != null && exists != source.owner)
            throw UserError("Эта база принадлежит другому профилю.")
        val values =
            ContentValues().apply {
                put("id", source.id)
                put("owner", source.owner)
                put("fingerprint", source.fingerprint)
                put("data", vault.seal(Json.encode(source.json()), "source:${source.id}"))
            }
        if (exists == null) writableDatabase.insertOrThrow("sources", null, values)
        else
            writableDatabase.update(
                "sources",
                values,
                "id=? AND owner=?",
                arrayOf(source.id, source.owner),
            )
    }

    private fun owns(owner: String, source: String): Boolean =
        readableDatabase
            .rawQuery("SELECT 1 FROM sources WHERE id=? AND owner=?", arrayOf(source, owner))
            .use { it.moveToFirst() }

    fun removeSource(owner: String, source: String) {
        if (!owns(owner, source)) throw UserError("База не найдена.")
        writableDatabase.delete("sources", "id=? AND owner=?", arrayOf(source, owner))
    }

    fun tasks(owner: String): List<TaskItem> =
        readableDatabase
            .rawQuery(
                "SELECT t.source,t.key,t.data FROM tasks t JOIN sources s ON t.source=s.id WHERE s.owner=?",
                arrayOf(owner),
            )
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        TaskItem.from(
                            Json.obj(
                                vault.open(c.getBlob(2), "task:${c.getString(0)}:${c.getString(1)}")
                            )
                        )
                    )
                }
            }

    private fun writeTask(t: TaskItem) {
        writableDatabase.insertWithOnConflict(
            "tasks",
            null,
            ContentValues().apply {
                put("source", t.sourceId)
                put("key", t.key)
                put("data", vault.seal(Json.encode(t.json()), "task:${t.sourceId}:${t.key}"))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    fun merge(owner: String, source: String, rows: List<Row>): List<TaskItem> {
        if (!owns(owner, source)) throw UserError("База недоступна этому пользователю.")
        val sourceConfig = sources(owner).first { it.id == source }
        val actor = user(owner)!!
        val old = tasks(owner).filter { it.sourceId == source }.associateBy { it.key }
        val values =
            rows.mapNotNull { r ->
                val key = r["Ref_Key"].text().lowercase()
                if (!GUID.matches(key)) null
                else
                    (old[key] ?: TaskItem(source, key, r)).copy(
                        raw = r,
                        updated = System.currentTimeMillis(),
                        unavailable = false,
                        taken =
                            old[key]?.taken == true ||
                                r[sourceConfig.mapping.programmer].text().equals(actor.login, true),
                        completed =
                            sourceConfig.mapping.phase(r[sourceConfig.mapping.status]) ==
                                Phase.DONE &&
                                (old[key]?.completed == true ||
                                    r[sourceConfig.mapping.programmer]
                                        .text()
                                        .equals(actor.login, true)),
                    )
            }
        transaction { values.forEach(::writeTask) }
        return values
    }

    fun pending(owner: String): List<Pending> =
        readableDatabase
            .rawQuery(
                "SELECT p.source,p.key,p.data FROM pending p JOIN sources s ON p.source=s.id WHERE s.owner=?",
                arrayOf(owner),
            )
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        Pending.from(
                            Json.obj(
                                vault.open(
                                    c.getBlob(2),
                                    "pending:${c.getString(0)}:${c.getString(1)}",
                                )
                            )
                        )
                    )
                }
            }

    fun begin(owner: String, p: Pending) {
        if (!owns(owner, p.sourceId) || owner != p.actorId)
            throw UserError("Другой профиль пользователя.")
        writableDatabase.insertOrThrow(
            "pending",
            null,
            ContentValues().apply {
                put("source", p.sourceId)
                put("key", p.key)
                put("data", vault.seal(Json.encode(p.json()), "pending:${p.sourceId}:${p.key}"))
            },
        )
    }

    fun discard(owner: String, p: Pending) {
        if (!owns(owner, p.sourceId)) throw UserError("Другой профиль пользователя.")
        writableDatabase.delete("pending", "source=? AND key=?", arrayOf(p.sourceId, p.key))
    }

    fun confirm(owner: String, p: Pending, task: TaskItem) {
        if (
            !owns(owner, p.sourceId) ||
                owner != p.actorId ||
                task.sourceId != p.sourceId ||
                task.key != p.key
        )
            throw UserError("Несовпадение задачи.")
        val before = tasks(owner).find { it.id == task.id }
        val saved =
            task.copy(
                taken = task.taken || before?.taken == true || p.phase == Phase.ACTIVE,
                completed =
                    if (p.phase == Phase.DONE) true
                    else if (p.phase == Phase.ACTIVE || p.phase == Phase.NEW) false
                    else task.completed,
            )
        transaction {
            writeTask(saved)
            discard(owner, p)
        }
    }

    fun unavailable(owner: String, t: TaskItem) {
        if (!owns(owner, t.sourceId)) throw UserError("Другой профиль пользователя.")
        writeTask(t.copy(unavailable = true))
    }

    fun clearUntracked(owner: String) {
        transaction {
            val protected = pending(owner).map { "${it.sourceId}:${it.key}" }.toSet()
            tasks(owner)
                .filter { !it.taken && !it.completed && it.id !in protected }
                .forEach {
                    writableDatabase.delete(
                        "tasks",
                        "source=? AND key=?",
                        arrayOf(it.sourceId, it.key),
                    )
                }
        }
    }

    private fun transaction(block: () -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
