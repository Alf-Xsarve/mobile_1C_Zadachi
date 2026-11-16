package kg.devtasks.app.ui

import android.text.TextUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import kg.devtasks.app.core.*

/** Компактная строка переиспользуется ListView при прокрутке. */
class TaskRow(private val u: Ui) : FrameLayout(u.a) {
    private val number = u.text("", 10f, true, u.muted)
    private val status = u.chip("")
    private val title = u.text("", 15f, true)
    private val description = u.text("", 12f, color = u.muted)
    private val sourceName = u.text("", 10f, color = u.muted)
    private val marker = u.text("", 10f, true, u.muted)

    init {
        setPadding(u.dp(12), u.dp(3), u.dp(12), u.dp(3))
        val card = u.column(12).apply { background = u.shape(u.paper, 14, true) }
        val top = u.row()
        top.addView(number, LinearLayout.LayoutParams(0, -2, 1f))
        status.textSize = 10f * u.scale
        status.setPadding(u.dp(7), u.dp(2), u.dp(7), u.dp(2))
        top.addView(status)
        card.addView(top)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        description.maxLines = 1
        description.ellipsize = TextUtils.TruncateAt.END
        number.maxLines = 1
        number.ellipsize = TextUtils.TruncateAt.END
        sourceName.maxLines = 1
        sourceName.ellipsize = TextUtils.TruncateAt.END
        listOf(number, title, description, sourceName, marker).forEach {
            it.setPadding(0, u.dp(2), 0, u.dp(2))
        }
        card.addView(title)
        card.addView(description)
        val bottom = u.row()
        sourceName.setPadding(0, u.dp(2), u.dp(8), u.dp(2))
        bottom.addView(sourceName, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(marker)
        card.addView(bottom)
        addView(card, LayoutParams(-1, -2))
        isFocusable = true
    }

    fun bind(task: TaskItem, source: Source, pending: Boolean, color: Int, open: () -> Unit) {
        val phase = task.phase(source.mapping)
        number.text = task.raw["Number"].text().let { if (it.isBlank()) "ЗАДАЧА" else "№ $it" }
        status.text = if (task.unavailable) "Недоступна" else phase.label
        status.setTextColor(color)
        status.background = u.shape((color and 0x00FFFFFF) or 0x18000000, 7)
        title.text = task.title(source.mapping)
        description.text = task.raw[source.mapping.description].text()
        description.visibility = if (description.text.isBlank()) GONE else VISIBLE
        sourceName.text = "▣ ${source.name}"
        marker.text =
            if (pending) "Проверить запись"
            else if (task.completed && phase == Phase.DONE) "✓ Готово"
            else if (task.taken) "Взята вами" else "Открыть ›"
        marker.setTextColor(if (pending) 0xFFB17725.toInt() else u.muted)
        setOnClickListener { open() }
    }
}
