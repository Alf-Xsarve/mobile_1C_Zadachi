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
        if (requestCode == QR_REQUEST && resultCode == RESULT_OK)
            data?.getStringExtra("code")?.let { state.importCode(it) }
    }

    companion object {
        private const val QR_REQUEST = 700
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    override fun onRetainNonConfigurationInstance(): Any = state

    override fun onDestroy() {
        state.render = null
        if (!isChangingConfigurations) state.close()
        super.onDestroy()
    }
}
