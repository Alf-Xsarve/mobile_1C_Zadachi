package kg.devtasks.app

import kg.devtasks.app.core.*
import org.junit.Assert.*
import org.junit.Test

class CloudTaskTest {
    private fun task(status: String = "new", assigned: String = "") =
        CloudTask("id", "Задача", "", status, "author", "Автор", assigned, "Имя", 1)

    @Test
    fun freeTaskCanOnlyBeClaimedBySignedInUser() {
        assertTrue(task().canClaim("alice"))
        assertFalse(task().canClaim(""))
        assertFalse(task("active", "alice").canClaim("bob"))
    }

    @Test
    fun onlyCurrentAssigneeCanCompleteActiveTask() {
        assertTrue(task("active", "alice").canComplete("alice"))
        assertFalse(task("active", "alice").canComplete("bob"))
        assertFalse(task("done", "alice").canComplete("alice"))
    }

    @Test
    fun draftKeepsStableIdForRetryAndValidatesContent() {
        val d = CloudDraft(title = "Новая задача", description = "Описание")
        val id = d.id
        d.validate()
        d.validate()
        assertEquals(id, d.id)
        assertThrows(UserError::class.java) { CloudDraft(title = "  ").validate() }
        assertThrows(UserError::class.java) { CloudDraft(title = "a".repeat(161)).validate() }
        assertThrows(UserError::class.java) {
            CloudDraft(title = "Valid", description = "a".repeat(10001)).validate()
        }
    }
}
