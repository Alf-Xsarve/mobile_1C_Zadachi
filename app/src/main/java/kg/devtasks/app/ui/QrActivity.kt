package kg.devtasks.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowInsets
import android.widget.LinearLayout
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.*

/** Камера работает только на этом экране. Результат не сохраняется и не журналируется. */
class QrActivity : Activity() {
    private var camera: DecoratedBarcodeView? = null
    private var captured = false
    private var resumed = false
    private var torch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showPermission()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else if (savedInstanceState == null)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 701)
    }

    private fun content(): Pair<Ui, LinearLayout> {
        val ui = Ui(this, "yellow", true, 1f, true)
        val root = ui.column(16).apply { setBackgroundColor(ui.bg) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(
                    bars.left + ui.dp(16),
                    bars.top + ui.dp(12),
                    bars.right + ui.dp(16),
                    bars.bottom + ui.dp(12),
                )
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft + ui.dp(16),
                    insets.systemWindowInsetTop + ui.dp(12),
                    insets.systemWindowInsetRight + ui.dp(16),
                    insets.systemWindowInsetBottom + ui.dp(12),
                )
            }
            insets
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        setContentView(root)
        root.requestApplyInsets()
        return ui to root
    }

    private fun showPermission() {
        val (ui, root) = content()
        root.addView(ui.text("Сканирование QR-кода", 23f, true))
        root.addView(
            ui.text(
                "Нужен доступ к камере. Если доступ запрещён, разрешите его в настройках приложения или добавьте базу вручную.",
                15f,
            )
        )
        root.addView(
            ui.button("Разрешить камеру") {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 701)
            }
        )
        root.addView(
            ui.button("Настройки приложения", secondary = true) {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$packageName"),
                    )
                )
            }
        )
        root.addView(ui.button("Ввести вручную", secondary = true) { finish() })
    }

    private fun startCamera() {
        if (camera != null || captured) return
        val (ui, root) = content()
        root.addView(ui.text("Подключение по QR", 23f, true))
        root.addView(
            ui.text(
                "Наведите камеру на QR-код базы 1С. После считывания проверим подключение.",
                14f,
                color = ui.muted,
            )
        )
        val preview =
            DecoratedBarcodeView(this).apply {
                barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                setStatusText("")
                decodeSingle(
                    object : BarcodeCallback {
                        override fun barcodeResult(result: BarcodeResult?) {
                            if (captured || result?.text.isNullOrBlank()) return
                            captured = true
                            pause()
                            setResult(
                                RESULT_OK,
                                Intent().putExtra("code", result!!.text.take(16385)),
                            )
                            finish()
                        }
                    }
                )
                barcodeView.addStateListener(
                    object : CameraPreview.StateListener {
                        override fun previewSized() {}

                        override fun previewStarted() {}

                        override fun previewStopped() {}

                        override fun cameraClosed() {}

                        override fun cameraError(error: Exception) {
                            if (isFinishing || isDestroyed) return
                            pause()
                            camera = null
                            torch = false
                            val (u, body) = content()
                            body.addView(u.text("Не удалось открыть камеру", 23f, true))
                            body.addView(
                                u.text(
                                    "Закройте другие приложения, использующие камеру, и попробуйте ещё раз."
                                )
                            )
                            body.addView(u.button("Повторить") { startCamera() })
                            body.addView(u.button("Назад", secondary = true) { finish() })
                        }
                    }
                )
            }
        camera = preview
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            val light = ui.button("Включить фонарик", secondary = true) {}
            light.setOnClickListener {
                torch = !torch
                if (torch) camera?.setTorchOn() else camera?.setTorchOff()
                light.text = if (torch) "Выключить фонарик" else "Включить фонарик"
            }
            root.addView(light)
        }
        root.addView(ui.button("Отмена", secondary = true) { finish() })
        if (resumed) preview.resume()
    }

    override fun onRequestPermissionsResult(
        code: Int,
        permissions: Array<out String>,
        results: IntArray,
    ) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 701) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
            else showPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
            if (!captured) {
                camera?.resume()
                if (torch) camera?.setTorchOn()
            }
        }
    }

    override fun onPause() {
        resumed = false
        camera?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        camera?.pause()
        camera = null
        super.onDestroy()
    }
}
