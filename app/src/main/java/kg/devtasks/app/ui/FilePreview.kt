package kg.devtasks.app.ui

import android.app.*
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.*
import java.io.File

object FilePreview {
    fun show(a: Activity, name: String, bytes: ByteArray) {
        val body =
            LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 16, 16, 16)
            }
        val dialog =
            AlertDialog.Builder(a)
                .setTitle(name)
                .setView(body)
                .setPositiveButton("Закрыть", null)
                .create()
        var cleanup: () -> Unit = {}
        try {
            if (name.endsWith(".pdf", true)) {
                val file = File.createTempFile("preview-", ".pdf", a.cacheDir)
                file.writeBytes(bytes)
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val pdf =
                    try {
                        PdfRenderer(fd)
                    } catch (e: Exception) {
                        fd.close()
                        file.delete()
                        throw e
                    }
                val image = ImageView(a)
                image.adjustViewBounds = true
                val scroll = ScrollView(a)
                scroll.addView(image)
                body.addView(scroll, LinearLayout.LayoutParams(-1, 800))
                val buttons = LinearLayout(a)
                body.addView(buttons)
                var page = 0
                var bitmap: Bitmap? = null
                fun render() {
                    pdf.openPage(page).use { p ->
                        val width = 1000
                        val height = (width.toLong() * p.height / p.width).coerceIn(1, 3000).toInt()
                        val next = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        next.eraseColor(Color.WHITE)
                        p.render(next, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        image.setImageBitmap(next)
                        bitmap?.recycle()
                        bitmap = next
                    }
                    dialog.setTitle("$name (${page+1}/${pdf.pageCount})")
                }
                buttons.addView(
                    Button(a).apply {
                        text = "Назад"
                        setOnClickListener {
                            if (page > 0) {
                                page--
                                render()
                            }
                        }
                    }
                )
                buttons.addView(
                    Button(a).apply {
                        text = "Далее"
                        setOnClickListener {
                            if (page + 1 < pdf.pageCount) {
                                page++
                                render()
                            }
                        }
                    }
                )
                cleanup = {
                    pdf.close()
                    fd.close()
                    file.delete()
                    bitmap?.recycle()
                }
                render()
            } else {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                if (options.outWidth > 0) {
                    var sample = 1
                    while (
                        options.outWidth / sample > 2048 || options.outHeight / sample > 2048
                    ) sample *= 2
                    val bitmap =
                        BitmapFactory.decodeByteArray(
                            bytes,
                            0,
                            bytes.size,
                            BitmapFactory.Options().apply { inSampleSize = sample },
                        ) ?: error("Изображение повреждено")
                    body.addView(
                        ImageView(a).apply {
                            adjustViewBounds = true
                            setImageBitmap(bitmap)
                        }
                    )
                    cleanup = { bitmap.recycle() }
                } else {
                    var text =
                        if (name.endsWith(".docx", true)) {
                            var result = ""
                            java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
                                var entries=0
                                while (entries++<512) {
                                    val entry = zip.nextEntry ?: break
                                    if (entry.name == "word/document.xml") {
                                        val output = java.io.ByteArrayOutputStream()
                                        val buf = ByteArray(8192)
                                        while (output.size() < 2000000) {
                                            val n =
                                                zip.read(
                                                    buf,
                                                    0,
                                                    minOf(buf.size, 2000000 - output.size()),
                                                )
                                            if (n < 0) break
                                            output.write(buf, 0, n)
                                        }
                                        val xml = output.toString("UTF-8")
                                        result =
                                            Regex("<w:t[^>]*>(.*?)</w:t>")
                                                .findAll(xml)
                                                .joinToString(" ") {
                                                    android.text.Html.fromHtml(it.groupValues[1], 0)
                                                        .toString()
                                                }
                                        break
                                    }
                                }
                            }
                            result
                        } else if (
                            name.substringAfterLast('.').lowercase() in
                                listOf("txt", "csv", "json", "xml", "log", "md")
                        )
                            bytes.take(1000000).toByteArray().toString(Charsets.UTF_8)
                        else
                            "Предпросмотр этого формата пока недоступен. Сохраните файл на телефон и откройте подходящим приложением."
                    val scroll = ScrollView(a)
                    scroll.addView(
                        TextView(a).apply {
                            this.text = text
                            setTextIsSelectable(true)
                            setPadding(8, 8, 8, 8)
                        }
                    )
                    body.addView(scroll)
                }
            }
            dialog.setOnDismissListener { cleanup() }
            dialog.show()
        } catch (e: Exception) {
            cleanup()
            Toast.makeText(
                    a,
                    "Не удалось открыть предпросмотр: файл повреждён или формат не поддерживается",
                    Toast.LENGTH_LONG,
                )
                .show()
        }
    }
}
