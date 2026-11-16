package kg.devtasks.app

import android.graphics.Bitmap
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.*
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kg.devtasks.app.core.*
import okhttp3.mockwebserver.*
import org.hamcrest.Matchers.*
import org.junit.*
import org.junit.runner.RunWith

const val K = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
val metadata =
    """<Edmx><DataServices><Schema xmlns="urn:edm" Namespace="S"><EntityType Name="Task_ТехЗаданияДляПрограммистов"><Key><PropertyRef Name="Ref_Key"/></Key><Property Name="Ref_Key" Type="Edm.Guid"/><Property Name="DataVersion" Type="Edm.String"/><Property Name="Description" Type="Edm.String"/><Property Name="Number" Type="Edm.String"/><Property Name="Date" Type="Edm.DateTime"/><Property Name="Описание" Type="Edm.String"/><Property Name="Статус" Type="Edm.String"/><Property Name="Программист" Type="Edm.String"/><Property Name="Комментарий" Type="Edm.String"/></EntityType><EnumType Name="СтадияДоработокПрограммистовV2"><Member Name="ВПроцессе"/><Member Name="Выполнено"/><Member Name="Отменено"/><Member Name="Свободно"/></EnumType><EntityContainer Name="Data"><EntitySet Name="Task_ТехЗаданияДляПрограммистов" EntityType="S.Task_ТехЗаданияДляПрограммистов"/></EntityContainer></Schema></DataServices></Edmx>"""

class FakeBase(title: String, val login: String, val password: String) : AutoCloseable {
    val server = MockWebServer()
    val writes = CopyOnWriteArrayList<Row>()
    val auth = CopyOnWriteArrayList<String>()
    @Volatile var drop = false
    @Volatile
    var task: Row =
        mapOf(
            "Ref_Key" to K,
            "DataVersion" to "v1",
            "Description" to title,
            "Number" to "DEV-042",
            "Date" to "2026-11-16T09:20:00",
            "Описание" to "Обновить печатную форму и проверить результат на тестовой базе.",
            "Статус" to "Свободно",
            "Программист" to "",
            "Комментарий" to "Комментарий из 1С",
        )

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    auth += request.getHeader("Authorization").orEmpty()
                    if (request.path.orEmpty().contains("metadata"))
                        return MockResponse()
                            .setBody(metadata)
                            .addHeader("Content-Type", "application/xml")
                    if (request.method == "PATCH") {
                        if (request.getHeader("If-Match") != task["DataVersion"])
                            return MockResponse().setResponseCode(412)
                        val r = Json.obj(request.body.readUtf8())
                        writes += r
                        task = task + r + mapOf("DataVersion" to "v${writes.size+1}")
                        return if (drop)
                            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        else MockResponse().setResponseCode(204)
                    }
                    return MockResponse()
                        .setBody(Json.encode(mapOf("value" to listOf(task))))
                        .addHeader("Content-Type", "application/json")
                }
            }
        server.start()
    }

    val url
        get() = server.url("/base/odata/standard.odata/").toString()

    override fun close() {
        runCatching { server.shutdown() }
    }

    fun checkAuth() {
        Assert.assertTrue(auth.isNotEmpty())
        Assert.assertTrue(
            auth.all {
                it ==
                    "Basic " + Base64.getEncoder().encodeToString("$login:$password".toByteArray())
            }
        )
    }
}

@RunWith(AndroidJUnit4::class)
class FlowTest {
    lateinit var scenario: ActivityScenario<MainActivity>
    lateinit var first: FakeBase
    lateinit var second: FakeBase
    val inst
        get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun start() {
        inst.targetContext.deleteDatabase("devtasks.db")
        first = FakeBase("Исправить печатную форму счёта", "oneC-a", "password-a")
        second = FakeBase("Задача другой базы", "oneC-b", "password-b")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitText("Вход в приложение")
    }

    @After
    fun end() {
        scenario.close()
        first.close()
        second.close()
        Thread.sleep(150)
    }

    fun waitText(text: String) {
        val until = System.currentTimeMillis() + 20000
        var failure: Throwable? = null
        while (System.currentTimeMillis() < until) {
            try {
                onView(allOf(withText(text), not(isAssignableFrom(EditText::class.java))))
                    .check(matches(isDisplayed()))
                return
            } catch (e: Throwable) {
                failure = e
                Thread.sleep(100)
            }
        }
        throw AssertionError("Missing: $text", failure)
    }

    fun click(text: String) {
        val view = onView(allOf(withText(text), not(isAssignableFrom(EditText::class.java))))
        try {
            view.check(matches(isDisplayingAtLeast(95)))
        } catch (_: AssertionError) {
            view.perform(scrollTo())
        }
        view.perform(click())
    }

    fun type(hint: String, text: String) {
        onView(withContentDescription(hint)).perform(scrollTo(), replaceText(text))
    }

    fun register() {
        click("Создать профиль")
        type("Ваше имя", "Егор")
        type("Логин приложения", "dev.egor")
        type("Пароль приложения", "secret-123")
        type("Повторите пароль", "secret-123")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        click("Зарегистрироваться")
        waitText("Все задачи")
        Assert.assertEquals(0, first.server.requestCount)
    }

    fun add(base: FakeBase, name: String) {
        onView(withContentDescription("Добавить базу")).perform(click())
        type("Название базы", name)
        type("Адрес OData", base.url)
        type("Логин пользователя 1С", base.login)
        type("Пароль пользователя 1С", base.password)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        click("Подключить базу")
        waitText("Подключения")
        waitText("▣ $name")
    }

    fun connect() {
        register()
        onView(withContentDescription("Раздел Настройки")).perform(click())
        click("▣ Мои базы 1С · 0")
        add(first, "Главная база")
        add(second, "Вторая база")
        onView(withContentDescription("Назад")).perform(click())
        onView(withContentDescription("Раздел Все")).perform(click())
        waitText("Исправить печатную форму счёта")
        waitText("Задача другой базы")
    }

    fun shot(name: String) {
        Thread.sleep(250)
        inst.waitForIdleSync()
        val bitmap = inst.uiAutomation.takeScreenshot()
        val resolver = inst.targetContext.contentResolver
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values =
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        "Pictures/DevTasksQA",
                    )
                }
            val uri =
                resolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values,
                )!!
            resolver.openOutputStream(uri)!!.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        bitmap.recycle()
    }

    @Test
    fun multiBaseClaimCompleteAndOfflineCache() {
        connect()
        shot("tasks-yellow")
        click("Исправить печатную форму счёта")
        click("Взять в работу")
        onView(withText("Сохранить")).perform(click())
        waitText("@dev.egor")
        Assert.assertEquals(1, first.writes.size)
        Assert.assertEquals("ВПроцессе", first.writes[0]["Статус"])
        Assert.assertEquals("dev.egor", first.writes[0]["Программист"])
        Assert.assertTrue(
            first.writes[0]["Комментарий"]
                .text()
                .startsWith("Комментарий из 1С\nВ процессе: dev.egor")
        )
        Assert.assertTrue(second.writes.isEmpty())
        shot("task-details")
        click("✓ Отметить выполненной")
        onView(withText("Сохранить")).perform(click())
        waitText("Выполнено")
        onView(withContentDescription("Назад")).perform(click())
        onView(withContentDescription("Раздел Готово")).perform(click())
        waitText("Исправить печатную форму счёта")
        onView(withText("Задача другой базы")).check(doesNotExist())
        shot("tasks-completed")
        first.checkAuth()
        second.checkAuth()
        var count = 0
        scenario.onActivity {
            count = it.state.tasks.size
            val db = it.state.store.readableDatabase
            db.rawQuery("SELECT data FROM sources", null).use { c ->
                while (c.moveToNext()) Assert.assertFalse(
                    String(c.getBlob(0)).contains("password-a")
                )
            }
            db.rawQuery("SELECT data FROM tasks", null).use { c ->
                while (c.moveToNext()) Assert.assertFalse(
                    String(c.getBlob(0)).contains("Исправить")
                )
            }
        }
        Assert.assertEquals(2, count)
        first.close()
        second.close()
        scenario.close()
        Thread.sleep(150)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitText("Все задачи")
        onView(withContentDescription("Раздел Готово")).perform(click())
        waitText("Исправить печатную форму счёта")
        shot("tasks-offline")
    }

    @Test
    fun conflictDoesNotOverwriteAndLostResponseIsVerified() {
        connect()
        click("Исправить печатную форму счёта")
        first.task =
            first.task +
                mapOf("DataVersion" to "v99", "Комментарий" to "Изменил другой программист")
        click("Взять в работу")
        onView(withText("Сохранить")).perform(click())
        waitText(
            "Задача изменилась в 1С. Данные обновлены; проверьте их перед повторным действием."
        )
        Assert.assertTrue(first.writes.isEmpty())
        first.drop = true
        click("Взять в работу")
        onView(withText("Сохранить")).perform(click())
        waitText("Проверить результат в 1С")
        Assert.assertEquals(1, first.writes.size)
        click("Проверить результат в 1С")
        waitText("@dev.egor")
        waitText("✓ Отметить выполненной")
        Assert.assertEquals(1, first.writes.size)
        Assert.assertTrue(
            first.writes[0]["Комментарий"].text().startsWith("Изменил другой программист\n")
        )
    }

    @Test
    fun designVariantsAndLocalProfileIsolation() {
        connect()
        onView(withContentDescription("Раздел Настройки")).perform(click())
        click("Синий")
        shot("tasks-style-blue")
        click("Графит")
        onView(withText(startsWith("Тема:"))).perform(scrollTo(), click())
        onView(withText("Тёмная")).perform(click())
        shot("tasks-style-graphite")
        click("Выйти из профиля")
        onView(withText("Выйти")).perform(click())
        waitText("Вход в приложение")
        click("Создать профиль")
        type("Ваше имя", "Другой")
        type("Логин приложения", "other.dev")
        type("Пароль приложения", "other-123")
        type("Повторите пароль", "other-123")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        click("Зарегистрироваться")
        waitText("Все задачи")
        scenario.onActivity {
            Assert.assertTrue(it.state.sources.isEmpty())
            Assert.assertTrue(it.state.tasks.isEmpty())
        }
        waitText("Подключим ваши базы?")
    }
}
