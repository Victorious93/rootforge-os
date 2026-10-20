package ai.rootforge.os.device

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardHelpersTest {
    @Test
    fun idsAreUniqueAndEveryEntryIsComplete() {
        val ids = BoardHelpers.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val names = BoardHelpers.all.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(BoardHelpers.all.any { it.kind == BoardHelperKind.RASPBERRY_PI })
        assertTrue(BoardHelpers.all.any { it.kind == BoardHelperKind.ESP32 })
        assertTrue(BoardHelpers.all.any { it.role == BoardHelperRole.LINUX_HOST_ADB_FASTBOOT })
        assertTrue(BoardHelpers.all.any { it.role == BoardHelperRole.USB_SERIAL_BRIDGE })
        assertTrue(BoardHelpers.all.any { it.role == BoardHelperRole.FASTBOOT_TIMING })
        for (helper in BoardHelpers.all) {
            assertTrue(helper.id, helper.summary.length > 40)
            assertTrue(helper.id, helper.inScope.lowercase().contains("not"))
        }
    }

    @Test
    fun everyHelperUrlIsHttpsUniqueAndOnAnAllowedHost() {
        val urls = BoardHelpers.urls()
        assertTrue("expected official helper pages", urls.size >= 5)
        assertEquals(urls.size, urls.toSet().size)
        for (helper in BoardHelpers.all) {
            for (page in listOf(helper.page) + helper.extras) {
                assertTrue(page.url, page.url.startsWith("https://"))
                assertFalse(page.url, page.url.contains(' '))
                assertFalse(
                    "${page.url} must not carry credentials",
                    page.url.substringAfter("https://").substringBefore('/').contains('@'),
                )
                val host = URI(page.url).host.orEmpty().lowercase()
                assertTrue(
                    "${page.url} host $host",
                    page.allowedHosts.any { host == it || host.endsWith(".$it") },
                )
            }
        }
    }

    @Test
    fun ethicsRefuseListNamesFrpAndScreenLock() {
        val text = (BoardHelpers.REFUSED.joinToString(" ") + " " + BoardHelpers.refusedText()).lowercase()
        assertTrue(text.contains("frp") || text.contains("factory reset protection"))
        assertTrue(text.contains("screen-lock") || text.contains("screen lock"))
        assertTrue(text.contains("will not"))
        assertFalse(text.contains("how to bypass"))
        for (helper in BoardHelpers.all) {
            val blob = (helper.summary + " " + helper.inScope).lowercase()
            assertFalse(helper.id, blob.contains("bypass frp"))
            assertFalse(helper.id, blob.contains("bypass screen"))
        }
    }

    @Test
    fun piCaptionUsesDeviceTreeAndDoesNotMatchAPc() {
        assertEquals(
            "Raspberry Pi 5 Model B Rev 1.0",
            HostPlatform.raspberryPiModel("Raspberry Pi 5 Model B Rev 1.0\u0000"),
        )
        assertNull(HostPlatform.raspberryPiModel("Generic x86_64"))
        assertNull(HostPlatform.raspberryPiModel(null))
        assertNull(HostPlatform.raspberryPiModel("  "))
        assertEquals(HostPlatform.PHONE_CAPTION, HostPlatform.hostCaption(androidHost = true))
        assertEquals(HostPlatform.COMPUTER_CAPTION, HostPlatform.hostCaption(null))
        assertEquals(
            HostPlatform.PI_CAPTION,
            HostPlatform.hostCaption("Raspberry Pi 4 Model B"),
        )
        assertTrue(HostPlatform.isHostComputer(HostPlatform.COMPUTER_CAPTION))
        assertTrue(HostPlatform.isHostComputer(HostPlatform.PI_CAPTION))
        assertFalse(HostPlatform.isHostComputer(HostPlatform.PHONE_CAPTION))
        assertNotNull(BoardHelpers.RASPBERRY_PI.page.url)
    }
}
