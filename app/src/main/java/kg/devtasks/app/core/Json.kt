package kg.devtasks.app.core

import org.json.JSONArray
import org.json.JSONObject

typealias Row = Map<String, Any?>

object Json {
    fun parse(text: String): Any? =
        unwrap(org.json.JSONTokener(text.removePrefix("\uFEFF")).nextValue())

    fun obj(text: String): Row {
        val v = parse(text)
        require(v is Map<*, *>) { "Некорректный JSON" }
        return v.row()
    }

    fun encode(v: Any?): String =
        when (v) {
            is Map<*, *> -> JSONObject(v).toString()
            is List<*> -> JSONArray(v).toString()
            else -> JSONArray(listOf(v)).toString().removeSurrounding("[", "]")
        }

    private fun unwrap(v: Any?): Any? =
        when (v) {
            JSONObject.NULL,
            null -> null
            is JSONObject -> v.keys().asSequence().associateWith { unwrap(v.get(it)) }
            is JSONArray -> (0 until v.length()).map { unwrap(v.get(it)) }
            else -> v
        }
}

fun Any?.row(): Row =
    (this as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

fun Any?.rows(): List<Row> = (this as? List<*>)?.map { it.row() }.orEmpty()

fun Any?.text() = this?.toString().orEmpty()

fun Any?.strings() = (this as? List<*>)?.filterIsInstance<String>().orEmpty()
