package kg.devtasks.app

import kg.devtasks.app.core.*
import org.junit.Assert.*
import org.junit.Test

class TaskLinkTest {
    @Test
    fun normalizesAddressesAndKeepsQueryAndFragment() {
        assertEquals(
            "https://example.test/file.pdf?q=1#page=2",
            webLink(" example.test/file.pdf?q=1#page=2 "),
        )
        assertEquals("http://10.0.2.2:8080/help", webLink("http://10.0.2.2:8080/help"))
    }

    @Test
    fun refusesExecutableSchemesAndEmbeddedCredentials() {
        listOf(
                "javascript:alert(1)",
                "file:///data/data/private",
                "content://app/x",
                "intent://somewhere",
                "ftp://example.test",
                "https://user:password@example.test/",
                "https://example.test/\nsecret",
                "",
                "x".repeat(4097),
            )
            .forEach { assertThrows(UserError::class.java) { webLink(it) } }
    }

    @Test
    fun createsDifferentMaterialKindsAndDefaultsNameToHost() {
        listOf("web", "image", "file").forEach { kind ->
            val link =
                TaskLink.create(
                    "task",
                    "",
                    "https://example.test/shared/photo.jpg",
                    kind,
                    "alice",
                    "Алиса",
                )
            assertEquals("example.test", link.name)
            assertEquals(kind, link.kind)
            assertEquals(link, TaskLink.from("task", link.id, link.fields()))
        }
    }

    @Test
    fun validatesMetadataFromServer() {
        val good =
            TaskLink.create("task", "Инструкция", "https://example.test/", "file", "alice", "Алиса")
        assertThrows(UserError::class.java) { good.copy(url = "javascript:alert(1)").validate() }
        assertThrows(UserError::class.java) { good.copy(kind = "executable").validate() }
        assertThrows(UserError::class.java) { good.copy(name = "n".repeat(161)).validate() }
        assertThrows(UserError::class.java) { good.copy(id = "../x").validate() }
    }
}
