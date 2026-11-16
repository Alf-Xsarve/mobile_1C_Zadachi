package kg.devtasks.app.core

data class CloudTask(
    val id: String,
    val title: String,
    val description: String,
    val status: String,
    val authorId: String,
    val authorName: String,
    val assigneeId: String,
    val assigneeName: String,
    val createdAt: Long,
) {
    val label: String
        get() =
            when (status) {
                "new" -> "Свободно"
                "active" -> "В процессе"
                "done" -> "Выполнено"
                else -> "Неизвестный статус"
            }

    fun canClaim(uid: String) = uid.isNotBlank() && status == "new" && assigneeId.isBlank()

    fun canComplete(uid: String) = uid.isNotBlank() && status == "active" && assigneeId == uid
}

class CloudDraft(val id: String = uid(), var title: String = "", var description: String = "") {
    fun validate() {
        if (title.trim().length !in 3..160)
            throw UserError("Название задачи: от 3 до 160 символов.")
        if (description.length > 10000) throw UserError("Описание: до 10 000 символов.")
    }
}
