package kg.devtasks.app

import kg.devtasks.app.core.*
import org.junit.Assert.*
import org.junit.Test

class ContactLinkTest {
    @Test
    fun whatsappPhoneUsesInternationalDigits() {
        assertEquals("https://wa.me/996555123456", materialLink("whatsapp", "+996 (555) 12-34-56"))
        assertEquals("https://wa.me/996555123456", materialLink("whatsapp", "00996555123456"))
        assertThrows(UserError::class.java) { materialLink("whatsapp", "0555123456") }
    }

    @Test
    fun telegramAcceptsPhoneUsernameAndOfficialLinks() {
        assertEquals("https://t.me/+996555123456", materialLink("telegram", "+996 555 123456"))
        assertEquals("https://t.me/client_dev", materialLink("telegram", "@client_dev"))
        assertEquals("https://t.me/client_dev", materialLink("telegram", "client_dev"))
        assertEquals("https://t.me/client_dev", materialLink("telegram", "http://t.me/client_dev"))
        assertEquals("https://t.me/+996555123456", materialLink("telegram", "t.me/+996555123456"))
    }

    @Test
    fun messengerSchemesBecomeSafeHttpsLinks() {
        assertEquals(
            "https://wa.me/996555123456",
            materialLink("whatsapp", "whatsapp://send?phone=%2B996555123456"),
        )
        assertEquals(
            "https://t.me/client_dev",
            materialLink("telegram", "tg://resolve?domain=client_dev"),
        )
        assertEquals(
            "https://t.me/+996555123456",
            materialLink("telegram", "tg://resolve?phone=996555123456"),
        )
    }

    @Test
    fun cannotMasqueradeUnrelatedSitesAsMessengerContacts() {
        listOf(
                "https://evil.test/client",
                "https://t.me.evil.test/client",
                "https://client@t.me/client",
                "javascript:alert(1)",
                "tg://resolve?domain=../../bad",
            )
            .forEach { assertThrows(UserError::class.java) { materialLink("telegram", it) } }
        assertThrows(UserError::class.java) {
            materialLink("whatsapp", "https://wa.me.evil.test/996555123456")
        }
    }

    @Test
    fun contactsHaveUsefulDefaultLabelsAndRoundTripMetadata() {
        val wa = TaskLink.create("task", "", "+996555123456", "whatsapp", "alice", "Алиса")
        assertEquals("WhatsApp: +996555123456", wa.name)
        assertEquals(wa, TaskLink.from("task", wa.id, wa.fields()))
        val tg = TaskLink.create("task", "Клиент", "@client_dev", "telegram", "alice", "Алиса")
        assertEquals("Клиент", tg.name)
        assertEquals(tg, TaskLink.from("task", tg.id, tg.fields()))
    }
}
