package kg.devtasks.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import kg.devtasks.app.ui.*

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class MainActivity : Activity() {
    lateinit var state: AppState
    private var shown: String? = null
    private var pushStarted = false
    private var notificationPermissionAsked = false
    private var fileTask: String? = null
    private var savingFile: Pair<String, String>? = null

    fun fileSection(body: android.widget.LinearLayout, task: String, draft: Boolean) {
        val files = state.cloud.files
        val title = android.widget.TextView(this)
        title.text = "Файлы и фотографии (до 10 МБ)"
        body.addView(title)
        fun button(label: String, action: () -> Unit) {
            body.addView(
                android.widget.Button(this).apply {
                    text = label
                    isEnabled = !state.cloud.busy && !files.busy
                    setOnClickListener { action() }
                }
            )
        }
        fun pick(images: Boolean) {
            fileTask = task
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (images) "image/*" else "*/*"
                },
                701,
            )
        }
        button("Выбрать фотографию") { pick(true) }
        button("Выбрать документ") { pick(false) }
        files.pending[task].orEmpty().forEach { file ->
            button("Убрать: ${file.name}") {
                files.pending[task]?.remove(file)
                file.file.delete()
                render()
            }
        }
        if (!draft) {
            button("Обновить вложения") { files.watch(task) }
            if (files.pending[task].orEmpty().isNotEmpty())
                button("Отправить выбранные файлы") { files.publish(task) { files.watch(task) } }
            files.items.forEach { row ->
                button("Просмотреть: ${row["name"]}") {
                    val token = state.cloud.token()
                    state.job(
                        { state.cloud.api.file(task, row["id"].toString(), token) },
                        { bytes ->
                            kg.devtasks.app.ui.FilePreview.show(this, row["name"].toString(), bytes)
                        },
                        {
                            android.widget.Toast.makeText(
                                    this,
                                    it.message,
                                    android.widget.Toast.LENGTH_LONG,
                                )
                                .show()
                        },
                    )
                }
                button("Сохранить: ${row["name"]}") {
                    savingFile = task to row["id"].toString()
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_TITLE, row["name"].toString())
                        },
                        702,
                    )
                }
            }
        }
        files.error?.let { body.addView(android.widget.TextView(this).apply { text = it }) }
    }

    fun openWebLink(address: String) {
        try {
            val url = kg.devtasks.app.core.webLink(address)
            startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
            )
        } catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(
                    this,
                    "Нет приложения для открытия веб-ссылок.",
                    android.widget.Toast.LENGTH_LONG,
                )
                .show()
        } catch (_: Exception) {
            android.widget.Toast.makeText(
                    this,
                    "Не удалось открыть ссылку HTTP или HTTPS.",
                    android.widget.Toast.LENGTH_LONG,
                )
                .show()
        }
    }

    fun scan() {
        if (state.user == null || state.busy || state.editor?.busy == true) return
        currentFocus?.let {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(it.windowToken, 0)
        }
        startActivityForResult(Intent(this, QrActivity::class.java), QR_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && requestCode == 701) {
            val uri = data?.data ?: return
            val task = fileTask ?: return
            val owner = state.cloud.account?.uid ?: return
            state.job(
                {
                    var name = "Документ"
                    contentResolver
                        .query(
                            uri,
                            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null,
                        )
                        ?.use { if (it.moveToFirst()) name = it.getString(0) }
                    name =
                        name
                            .replace('/', '_')
                            .replace('\\', '_')
                            .filterNot { it.isISOControl() }
                            .take(200)
                            .ifBlank { "Документ" }
                    val file = java.io.File.createTempFile("upload-", ".tmp", cacheDir)
                    try {
                        contentResolver.openInputStream(uri)!!.use { input ->
                            file.outputStream().use { output ->
                                val buf = ByteArray(65536)
                                var total = 0
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > 10485760)
                                        throw kg.devtasks.app.core.UserError(
                                            "Максимальный размер файла — 10 МБ."
                                        )
                                    output.write(buf, 0, n)
                                }
                                if (total == 0) throw kg.devtasks.app.core.UserError("Файл пуст.")
                            }
                        }
                        kg.devtasks.app.data.Upload(name = name, file = file)
                    } catch (e: Exception) {
                        file.delete()
                        throw e
                    }
                },
                { upload ->
                    if (
                        owner == state.cloud.account?.uid &&
                            (state.cloud.draft?.id == task || state.cloud.selectedId == task)
                    )
                        state.cloud.files.add(task, upload)
                    else upload.file.delete()
                },
                {
                    android.widget.Toast.makeText(
                            this,
                            it.message,
                            android.widget.Toast.LENGTH_LONG,
                        )
                        .show()
                },
            )
            return
        }
        if (resultCode == RESULT_OK && requestCode == 702) {
            val uri = data?.data ?: return
            val file = savingFile ?: return
            val token = state.cloud.token()
            state.job(
                {
                    val bytes = state.cloud.api.file(file.first, file.second, token)
                    contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
                },
                {
                    android.widget.Toast.makeText(
                            this,
                            "Файл сохранён",
                            android.widget.Toast.LENGTH_SHORT,
                        )
                        .show()
                },
                {
                    android.widget.Toast.makeText(
                            this,
                            it.message,
                            android.widget.Toast.LENGTH_LONG,
                        )
                        .show()
                },
            )
            return
        }
        if (requestCode == QR_REQUEST && resultCode == RESULT_OK)
            data?.getStringExtra("code")?.let { state.importCode(it) }
    }

    companion object {
        private const val QR_REQUEST = 700
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileTask = savedInstanceState?.getString("fileTask")
        savedInstanceState?.getString("saveTask")?.let { task ->
            savedInstanceState.getString("saveId")?.let { savingFile = task to it }
        }
        state = lastNonConfigurationInstance as? AppState ?: AppState(applicationContext)
        state.render = { render() }
        render()
        if (android.os.Build.VERSION.SDK_INT >= 33)
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                if (!state.back()) finish()
            }
    }

    fun render() {
        if (isFinishing || isDestroyed) return
        if (!state.boot && state.user != null)
            intent.getStringExtra("push_kind")?.let { kind ->
                val cloud = state.cloud
                if (cloud.busy || cloud.files.busy || cloud.links.busy || cloud.draft != null)
                    return@let
                intent.removeExtra("push_kind")
                if (kind == "common") {
                    state.page = "cloud"
                    cloud.selectedId = null
                    window.decorView.post { cloud.listen() }
                }
            }
        if (state.cloud.account != null && !state.boot && !pushStarted) {
            pushStarted = true
            kg.devtasks.app.data.TaskMessaging.start(applicationContext)
            if (
                android.os.Build.VERSION.SDK_INT >= 33 &&
                    !notificationPermissionAsked &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionAsked = true
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 703)
            }
        }
        if (state.cloud.account == null) pushStarted = false
        val focus = currentFocus as? EditText
        val tag = focus?.tag
        val selection = focus?.selectionStart ?: 0
        val dark =
            state.theme == "dark" ||
                (state.theme == "system" && resources.configuration.uiMode and 48 == 32)
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar else R.style.AppTheme)
        val key =
            "${state.user?.id}:${state.page}:${state.tab}:${state.detailId}:${state.cloud.selectedId}:${state.cloud.draft?.id}"
        val oldScroll =
            if (key == shown)
                findViewById<android.view.ViewGroup>(android.R.id.content)
                    ?.findViewWithTag<android.widget.ScrollView>("scroll")
                    ?.scrollY
            else null
        val oldList =
            if (key == shown)
                findViewById<android.view.ViewGroup>(android.R.id.content)
                    ?.findViewWithTag<android.widget.ListView>("tasks")
            else null
        val first = oldList?.firstVisiblePosition
        val top = oldList?.getChildAt(0)?.top ?: 0
        val ui = Ui(this, state.style, dark, state.scale, state.animations)
        val root = Screens(this, ui).render()
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars =
                    insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else
                v.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            insets
        }
        window.statusBarColor = ui.bg
        window.navigationBarColor = ui.bg
        window.decorView.systemUiVisibility =
            if (dark) 0
            else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(root)
        root.requestApplyInsets()
        if (key != shown) ui.reveal(root)
        shown = key
        root.post {
            if (oldScroll != null)
                root.findViewWithTag<android.widget.ScrollView>("scroll")?.scrollTo(0, oldScroll)
            if (first != null)
                root
                    .findViewWithTag<android.widget.ListView>("tasks")
                    ?.setSelectionFromTop(first, top)
        }
        if (tag != null)
            root.findViewWithTag<EditText>(tag)?.let {
                it.requestFocus()
                it.setSelection(selection.coerceIn(0, it.text.length))
            }
    }

    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        if (!state.back()) super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("fileTask", fileTask)
        outState.putString("saveTask", savingFile?.first)
        outState.putString("saveId", savingFile?.second)
        super.onSaveInstanceState(outState)
    }

    override fun onRetainNonConfigurationInstance(): Any = state

    override fun onStart() {
        super.onStart()
        state.cloud.foreground(true)
    }

    override fun onStop() {
        state.cloud.foreground(false)
        super.onStop()
    }

    override fun onDestroy() {
        state.render = null
        if (!isChangingConfigurations) state.close()
        super.onDestroy()
    }
}
