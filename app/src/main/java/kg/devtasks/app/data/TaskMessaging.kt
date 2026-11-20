package kg.devtasks.app.data

import android.app.*
import android.content.*
import com.google.firebase.messaging.*
import kg.devtasks.app.MainActivity
import kg.devtasks.app.core.*

class TaskMessaging : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        register(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val session = SharedStorage(this, SharedApi().baseUrl).read("session") ?: return
        if(!runCatching {java.time.Instant.parse(session["expiresAt"].text()).isAfter(java.time.Instant.now())}.getOrDefault(false))return
        val user = session["user"].row()["id"].text()
        if (message.data["userId"] != user) return
        val kind = message.data["kind"]
        if (kind == "odata") {
            val store = LocalStore(this)
            val matches =
                try {
                    val owner = store.setting("session") ?: return
                    store.sources(owner).any {
                        it.enabled &&
                            it.url.trimEnd('/') == message.data["source"].orEmpty().trimEnd('/')
                    }
                } finally {
                    store.close()
                }
            if (!matches) return
        }
        val key = "push-seen:$user:${message.data["eventId"]}"
        val storage = SharedStorage(this, SharedApi().baseUrl)
        if (storage.read(key) != null) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("tasks", "Новые задачи", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val intent =
            PendingIntent.getActivity(
                this,
                message.data["eventId"].hashCode(),
                Intent(this, MainActivity::class.java)
                    .putExtra("push_kind", kind)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            Notification.Builder(this, "tasks")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(if (kind == "odata") "Новая задача в 1С" else "Новая общая задача")
                .setContentText("Откройте приложение, чтобы обновить список задач")
                .setContentIntent(intent)
                .setAutoCancel(true)
                .build()
        try {
            manager.notify(message.data["eventId"].hashCode(), notification)
            storage.write(key, mapOf("seen" to true))
        } catch (_: SecurityException) {}
    }

    companion object {
        fun register(context: Context, token: String) {
            Thread {
                    runCatching {
                        val api = SharedApi()
                        val session =
                            SharedStorage(context, api.baseUrl).read("session")
                                ?: return@runCatching
                        val store = LocalStore(context)
                        val sources =
                            try {
                                store
                                    .setting("session")
                                    ?.let {
                                        store
                                            .sources(it)
                                            .filter { source -> source.enabled }
                                            .map { source -> source.url.trimEnd('/') }
                                    }
                                    .orEmpty()
                            } finally {
                                store.close()
                            }
                        api.request(
                            "POST",
                            "/api/devices",
                            session["token"].text(),
                            mapOf("id" to uid(), "token" to token, "sources" to sources),
                        )
                    }
                }
                .start()
        }

        fun start(context: Context) {
            runCatching {
                FirebaseMessaging.getInstance().token.addOnSuccessListener {
                    register(context.applicationContext, it)
                }
            }
        }
    }
}
