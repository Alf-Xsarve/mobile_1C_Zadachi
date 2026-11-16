package kg.devtasks.app

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kg.devtasks.app.core.CloudDraft
import kg.devtasks.app.data.FirebaseTasks
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Работает только с локальными эмуляторами demo-devtasks, не с рабочим Firebase. */
@RunWith(AndroidJUnit4::class)
class FirebaseFlowTest {
    @Before
    fun requireEmulators() {
        Assume.assumeTrue(
            "Нужны локальные Firebase Emulator: -Pandroid.testInstrumentationRunnerArguments.firebaseEmulators=true",
            InstrumentationRegistry.getArguments().getString("firebaseEmulators") == "true",
        )
    }

    private val inst
        get() = InstrumentationRegistry.getInstrumentation()

    private fun client(label: String): Pair<FirebaseApp, FirebaseTasks> {
        val app =
            FirebaseApp.initializeApp(
                inst.targetContext,
                FirebaseOptions.Builder()
                    .setProjectId("demo-devtasks")
                    .setApplicationId("1:123456789:android:devtasks")
                    .setApiKey("fake-key-for-local-emulator")
                    .build(),
                "test-$label-${System.nanoTime()}",
            )
        FirebaseAuth.getInstance(app).useEmulator("10.0.2.2", 9099)
        val db = FirebaseFirestore.getInstance(app)
        db.firestoreSettings =
            FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build()
        db.useEmulator("10.0.2.2", 8080)
        return app to FirebaseTasks(inst.targetContext, {}, app)
    }

    private fun waitFor(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30000
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            inst.runOnMainSync { ready = check() }
            if (ready) return
            Thread.sleep(100)
        }
        fail("Не дождались обновления Firebase Emulator")
    }

    private fun register(c: FirebaseTasks, label: String) {
        val result = CountDownLatch(1)
        var success = false
        inst.runOnMainSync {
            c.authenticate(
                true,
                "user-${System.nanoTime()}@example.test",
                "password-123",
                label,
                "password-123",
            ) {
                success = it
                result.countDown()
            }
        }
        assertTrue(result.await(30, TimeUnit.SECONDS))
        assertTrue(c.error, success)
    }

    @Test
    fun twoAccountsSeeSameTasksAndOnlyOneCanClaim() {
        val (appA, a) = client("alice")
        val (appB, b) = client("bob")
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            register(a, "Алиса")
            register(b, "Борис")
            val title = "Общая задача ${System.nanoTime()}"
            inst.runOnMainSync {
                a.draft =
                    CloudDraft(title = title, description = "Проверка общего списка и исполнителя")
                a.create()
            }
            waitFor {
                !a.busy && a.items.any { it.title == title } && b.items.any { it.title == title }
            }
            val task = a.items.first { it.title == title }
            assertEquals("Алиса", task.authorName)
            inst.runOnMainSync {
                a.change(task, false)
                b.change(task, false)
            }
            waitFor {
                !a.busy &&
                    !b.busy &&
                    a.items.first { it.id == task.id }.status == "active" &&
                    b.items.first { it.id == task.id }.status == "active"
            }
            val assigned = a.items.first { it.id == task.id }
            assertEquals(assigned.assigneeId, b.items.first { it.id == task.id }.assigneeId)
            val winner = if (assigned.assigneeId == a.account!!.uid) a else b
            val loser = if (winner === a) b else a
            assertTrue(assigned.assigneeName in listOf("Алиса", "Борис"))
            inst.runOnMainSync { loser.change(assigned, true) }
            waitFor { !loser.busy && loser.error != null }
            inst.runOnMainSync { winner.change(assigned, true) }
            waitFor {
                !winner.busy &&
                    a.items.first { it.id == task.id }.status == "done" &&
                    b.items.first { it.id == task.id }.status == "done"
            }

            // Проверяем настоящий экран на данных локального Firebase.
            inst.targetContext.deleteDatabase("devtasks.db")
            scenario = ActivityScenario.launch(MainActivity::class.java)
            var initialState: AppState? = null
            scenario.onActivity { initialState = it.state }
            waitFor { !initialState!!.boot }
            scenario.onActivity { activity ->
                activity.state.close()
                activity.state =
                    AppState(activity.applicationContext) { context, changed ->
                        FirebaseTasks(context, changed, appA)
                    }
                activity.state.render = { activity.render() }
                activity.state.localAuth = true
                activity.render()
            }
            var activityState: AppState? = null
            scenario.onActivity { activityState = it.state }
            waitFor { !activityState!!.boot }
            waitFor {
                activityState!!.user != null && !activityState!!.boot && activityState!!.cloud.ready
            }
            assertTrue(activityState!!.user!!.id.startsWith("firebase:"))
            scenario.onActivity {
                it.state.page = "cloud"
                it.render()
            }
            waitFor { activityState!!.cloud.items.any { it.id == task.id } }
            inst.waitForIdleSync()
            screenshot("firebase-common-tasks")
            onView(withText(title)).perform(click())
            onView(withText("Общие задачи"))
                .check(
                    androidx.test.espresso.assertion.ViewAssertions.matches(
                        androidx.test.espresso.matcher.ViewMatchers.isDisplayed()
                    )
                )
            onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("Назад"))
                .check(
                    androidx.test.espresso.assertion.ViewAssertions.matches(
                        androidx.test.espresso.matcher.ViewMatchers.isDisplayed()
                    )
                )
            screenshot("firebase-task-assignee")
        } finally {
            scenario?.close()
            a.close()
            b.close()
            appA.delete()
            appB.delete()
        }
    }

    private fun screenshot(name: String) {
        inst.waitForIdleSync()
        Thread.sleep(300)
        val resolver = inst.targetContext.contentResolver
        val values =
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DevTasksQA")
            }
        val uri =
            resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        val bitmap = inst.uiAutomation.takeScreenshot()
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
