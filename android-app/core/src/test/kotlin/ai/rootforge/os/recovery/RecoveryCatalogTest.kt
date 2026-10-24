package ai.rootforge.os.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class RecoveryCatalogTest {
    private val searchJson = """
        [
          { "title" : "Xiaomi Redmi 8 (olive)", "desc" : "TWRP for Xiaomi Redmi 8", "url" : "/xiaomi/xiaomiredmi8.html" },
          { "title" : "OnePlus 6/6T (enchilada/fajita)", "desc" : "TWRP for OnePlus 6", "url" : "/oneplus/oneplus6.html" }
        ]
    """.trimIndent()

    private val twrpHtml = """
        <html><body>
        <a href="twrp-3.7.0_9-0-olive.img">twrp-3.7.0_9-0-olive.img</a>
        <a href="twrp-3.7.0_9-0-olive.img.md5">md5</a>
        <a href="twrp-3.7.0_9-0-olive.img.md5.asc">asc</a>
        <a href="../other/evil.img">skip</a>
        </body></html>
    """.trimIndent()

    @Test
    fun twrpSearchMatchesCodenameInTitle() {
        val client = RecoveryCatalogClient { _, _ -> error("no fetch") }
        val hits = client.matchTwrpSearch(searchJson, listOf("olive"))
        assertEquals(1, hits.size)
        assertEquals("olive", hits[0].codename)
        assertTrue(hits[0].pageUrl.startsWith("https://twrp.me/"))
        val two = client.matchTwrpSearch(searchJson, listOf("fajita"))
        assertEquals("fajita", two.single().codename)
    }

    @Test
    fun twrpDirectoryListsImgWithMd5AndReferer() {
        val md5Body = "d41d8cd98f00b204e9800998ecf8427e  twrp-3.7.0_9-0-olive.img\n"
        val client = RecoveryCatalogClient { url, _ ->
            when {
                url.endsWith(".md5") -> md5Body
                else -> error("unexpected $url")
            }
        }
        val offers = client.parseTwrpDirectory(twrpHtml, "olive", "Xiaomi Redmi 8 (olive)")
        val img = offers.single()
        assertEquals(RecoveryProject.TWRP, img.project)
        assertEquals("twrp-3.7.0_9-0-olive.img", img.filename)
        assertEquals("https://dl.twrp.me/olive/twrp-3.7.0_9-0-olive.img", img.safeDownloadUrl())
        assertEquals("https://dl.twrp.me/olive/", img.referer)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", img.md5)
        assertTrue(img.hasPgpSignature)
        assertEquals("3.7.0_9-0", img.version)
    }

    @Test
    fun pbrpDevicePageKeepsOfficialGithubAndDropsOthers() {
        val html = """
            <a href="https://github.com/PitchBlackRecoveryProject/android_device_xiaomi_olive-pbrp/releases">repo</a>
            <a href="https://github.com/PitchBlackRecoveryProject/android_device_xiaomi_olive-pbrp/releases/download/4.0/PBRP-olive-4.0.zip">zip</a>
            <a href="https://evil.example/payload.zip">no</a>
            <a href="javascript:alert(1)">no</a>
        """.trimIndent()
        val client = RecoveryCatalogClient { _, _ -> error("no network") }
        val offers = client.parsePbrpDevicePage(html, "olive", "https://pitchblackrecovery.com/olive")
        assertTrue(offers.any { it.filename == "PBRP-olive-4.0.zip" && it.payload == RecoveryPayload.ZIP })
        assertTrue(offers.none { it.downloadUrl.contains("evil.example") })
    }

    @Test
    fun githubReleaseAssetsAreFiltered() {
        val body = """
            {
              "tag_name": "4.0",
              "html_url": "https://github.com/PitchBlackRecoveryProject/android_device_xiaomi_olive-pbrp/releases/tag/4.0",
              "body": "sha256 ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
              "assets": [
                {"name": "PBRP-olive-4.0.zip", "browser_download_url": "https://github.com/PitchBlackRecoveryProject/android_device_xiaomi_olive-pbrp/releases/download/4.0/PBRP-olive-4.0.zip", "size": 40},
                {"name": "notes.txt", "browser_download_url": "https://github.com/PitchBlackRecoveryProject/x/notes.txt", "size": 1},
                {"name": "evil.zip", "browser_download_url": "http://github.com/PitchBlackRecoveryProject/x/evil.zip", "size": 1}
              ]
            }
        """.trimIndent()
        val client = RecoveryCatalogClient { _, _ -> error("no") }
        val offers = client.parseGithubReleases(body, "PitchBlackRecoveryProject/android_device_xiaomi_olive-pbrp", "olive", "https://pitchblackrecovery.com/olive")
        assertEquals(1, offers.size)
        assertEquals("PBRP-olive-4.0.zip", offers[0].filename)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", offers[0].sha256)
        assertTrue(offers[0].downloadUrl.startsWith("https://"))
    }

    @Test
    fun checksumParserReadsGnuAndBsdMd5() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", RecoveryChecksum.parseMd5File("d41d8cd98f00b204e9800998ecf8427e  twrp.img\n"))
        assertEquals(
            "d41d8cd98f00b204e9800998ecf8427e",
            RecoveryChecksum.parseMd5File("MD5 (twrp.img) = d41d8cd98f00b204e9800998ecf8427e\n"),
        )
    }

    @Test
    fun hostsRejectHttpAndOffProject() {
        assertFalse(RecoveryHosts.allowed("http://dl.twrp.me/olive/x.img", RecoveryProject.TWRP))
        assertFalse(RecoveryHosts.allowed("https://evil.example/x.img", RecoveryProject.TWRP))
        assertTrue(RecoveryHosts.allowed("https://dl.twrp.me/olive/x.img", RecoveryProject.TWRP))
        assertTrue(RecoveryHosts.allowed("https://github.com/PitchBlackRecoveryProject/x/a.zip", RecoveryProject.PITCHBLACK))
        assertFalse(RecoveryHosts.allowed("https://github.com/PitchBlackRecoveryProject/x/a.zip", RecoveryProject.TWRP))
        assertTrue(RecoveryHosts.allowed("https://pitchblack.tech/", RecoveryProject.PITCHBLACK))
        assertTrue(RecoveryHosts.allowed("https://www.pitchblack.tech/PBRP-olive.img", RecoveryProject.PITCHBLACK))
        assertFalse(RecoveryHosts.allowed("https://pitchblack.tech/", RecoveryProject.TWRP))
        assertFalse(RecoveryHosts.allowed("http://pitchblack.tech/", RecoveryProject.PITCHBLACK))
        assertFalse(RecoveryHosts.allowed("https://orangefox.download/", RecoveryProject.PITCHBLACK))
        assertFalse(RecoveryHosts.allowed("https://shrp.github.io/", RecoveryProject.PITCHBLACK))
        assertFalse(RecoveryHosts.allowed("https://forum.xda-developers.com/c/redwolf-recovery.10018/", RecoveryProject.TWRP))
    }

    @Test
    fun lookupCanBeLimitedToOneProject() {
        val fetched = mutableListOf<String>()
        val client = RecoveryCatalogClient { url, _ ->
            fetched += url
            when {
                url.endsWith("search.json") -> "[]"
                else -> error("unexpected $url")
            }
        }
        assertTrue(client.lookup(listOf("olive"), RecoveryProject.TWRP).isEmpty())
        assertTrue(fetched.any { it.contains("twrp.me") })
        assertTrue(fetched.none { it.contains("pitchblack") || it.contains("sourceforge") || it.contains("github.com") })
        fetched.clear()
        assertTrue(client.lookup(listOf("olive"), RecoveryProject.PITCHBLACK).isEmpty())
        assertTrue(fetched.any { it.contains("pitchblack") || it.contains("sourceforge") || it.contains("github.com") })
        assertTrue(fetched.none { it.contains("twrp.me") })
    }

    @Test
    fun titleCodenamesComeFromTheTrailingParentheses() {
        assertEquals(listOf("shiba"), RecoveryCatalogClient.extractCodenames("Google Pixel 8 (shiba)"))
        assertEquals(listOf("enchilada", "fajita"), RecoveryCatalogClient.extractCodenames("OnePlus 6/6T (enchilada/fajita)  "))
        assertEquals(listOf("baz"), RecoveryCatalogClient.extractCodenames("Foo (bar) (baz)"))
        assertTrue(RecoveryCatalogClient.extractCodenames("Foo (bar (baz))").isEmpty())
        assertTrue(RecoveryCatalogClient.extractCodenames("no parentheses").isEmpty())
        assertEquals(listOf("shiba"), RecoveryCatalogClient.extractCodenames("x".repeat(250) + " (shiba)"))
    }

    /** The title is remote text and the pattern is quadratic in its "(" count: a hostile one must not stall the lookup. */
    @Test
    fun aHostileTitleCannotStallTheCodenameRegex() {
        val started = System.nanoTime()
        assertTrue(RecoveryCatalogClient.extractCodenames("(".repeat(30_000)).isEmpty())
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("extractCodenames took ${ms}ms on a 30k-character title", ms < 1_000)
    }

    @Test
    fun fetcherRefusesHttp() {
        val r = runCatching { RecoveryFetcher.fetchText("http://example.org/x") { _, _ -> error("must not open") } }
        assertTrue(r.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun fetcherCapsSize() {
        val big = ByteArray(200)
        val r = runCatching {
            RecoveryFetcher.fetchText("https://example.org/c.json", maxBytes = 100) { _, _ -> ByteArrayInputStream(big) }
        }
        assertTrue(r.exceptionOrNull() is IOException)
    }
}
