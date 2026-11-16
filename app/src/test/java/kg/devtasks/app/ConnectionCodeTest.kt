package kg.devtasks.app

import kg.devtasks.app.core.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionCodeTest {
    private fun code(vararg changes: Pair<String, Any?>) =
        Json.encode(
            mapOf(
                "type" to "devtasks_1c_connection",
                "version" to 1,
                "name" to "База разработчиков",
                "odataUrl" to "https://example.test/base/odata/standard.odata/",
                "username" to " Пользователь 1С ",
                "password" to " пробелы:пароль ",
            ) + changes
        )

    @Test
    fun readsNamedConnectionWithoutChangingPassword() {
        val c = ConnectionCode.parse("\uFEFF" + code())
        assertEquals("База разработчиков", c.name)
        assertEquals("Пользователь 1С", c.login)
        assertEquals(" пробелы:пароль ", c.password)
        assertFalse(c.toString().contains(c.password))
    }

    @Test
    fun acceptsAccountingQrAndDerivesName() {
        val c =
            ConnectionCode.parse(
                code(
                    "type" to "accounting_1c_connection",
                    "name" to null,
                    "odataUrl" to "https://example.test/Neichural_agro/odata/standard.odata",
                )
            )
        assertEquals("Neichural_agro", c.name)
        assertTrue(c.url.endsWith("standard.odata/"))
    }

    @Test
    fun rejectsOtherQrFormatsAndWrongFieldTypes() {
        listOf(
                code("type" to "wifi"),
                code("version" to 2),
                code("version" to "1"),
                code("password" to 123),
                code("name" to 7),
                "not json",
                "[]",
                code() + "extra",
                "x".repeat(16385),
            )
            .forEach { assertThrows(UserError::class.java) { ConnectionCode.parse(it) } }
    }

    @Test
    fun rejectsInvalidAddressesAndCredentials() {
        listOf(
                code("odataUrl" to "file:///tmp/data"),
                code("odataUrl" to "https://u:p@example.test/base/odata/standard.odata/"),
                code("odataUrl" to "https://example.test/base/odata/standard.odata/?x=1"),
                code("username" to "bad:user"),
                code("password" to ""),
            )
            .forEach { assertThrows(UserError::class.java) { ConnectionCode.parse(it) } }
    }
}
