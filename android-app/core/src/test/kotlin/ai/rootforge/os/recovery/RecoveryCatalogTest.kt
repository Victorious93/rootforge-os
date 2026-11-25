package ai.rootforge.os.recovery

import ai.rootforge.os.core.FileDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
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

    /** A failed transfer used to delete the library file it was about to replace. */
    @Test
    fun aFailedDownloadLeavesTheExistingLibraryFile() {
        val dir = tempDir()
        val dest = File(dir, "twrp.img")
        val kept = "ANDROID!kept-image".toByteArray()
        dest.writeBytes(kept)
        val failed = runCatching {
            RecoveryFetcher.fetchToFile("https://dl.twrp.me/enchilada/twrp.img", dest) { _, _ ->
                throw IOException("connection reset")
            }
        }
        assertTrue(failed.exceptionOrNull() is IOException)
        assertArrayEquals(kept, dest.readBytes())
        dir.deleteRecursively()
    }

    @Test
    fun aRejectedBodyDoesNotReplaceTheExistingLibraryFile() {
        val dir = tempDir()
        val dest = File(dir, "twrp.img")
        val kept = "ANDROID!kept-image".toByteArray()
        dest.writeBytes(kept)
        val html = runCatching {
            RecoveryFetcher.downloadChecked("https://dl.twrp.me/enchilada/twrp.img", dest, label = "twrp.img") { _, _ ->
                ByteArrayInputStream("<html>not an image</html>".toByteArray())
            }
        }
        assertTrue(html.exceptionOrNull()?.message?.contains("not a recovery.img") == true)
        assertArrayEquals(kept, dest.readBytes())
        val mismatch = runCatching {
            RecoveryFetcher.downloadChecked(
                "https://dl.twrp.me/enchilada/twrp.img",
                dest,
                md5 = "0".repeat(32),
            ) { _, _ -> ByteArrayInputStream("ANDROID!new-image".toByteArray()) }
        }
        assertTrue(mismatch.exceptionOrNull() is IOException)
        assertArrayEquals(kept, dest.readBytes())
        assertTrue(dir.listFiles().orEmpty().none { it.name != "twrp.img" })
        dir.deleteRecursively()
    }

    @Test
    fun aCheckedDownloadReplacesTheLibraryFileOnlyAfterItMatches() {
        val dir = tempDir()
        val dest = File(dir, "twrp.img")
        dest.writeBytes("ANDROID!old-image".toByteArray())
        val fresh = "ANDROID!new-image".toByteArray()
        val n = RecoveryFetcher.downloadChecked(
            "https://dl.twrp.me/enchilada/twrp.img",
            dest,
            md5 = FileDigest.md5Hex(fresh),
            sha256 = FileDigest.sha256Hex(fresh),
        ) { _, _ -> ByteArrayInputStream(fresh) }
        assertEquals(fresh.size.toLong(), n)
        assertArrayEquals(fresh, dest.readBytes())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".part") })
        dir.deleteRecursively()
    }

    private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "rf-fetch-${System.nanoTime()}").also { it.mkdirs() }

    // --- Page formats as the live sites serve them (read from twrp.me, dl.twrp.me, pitchblackrecovery.com and
    // sourceforge.net on 2026-10-24; the fixtures below are short excerpts, not the whole pages).

    /** dl.twrp.me lists every file as a root-relative link to the file's own page, `/<codename>/<file>.html`. */
    private val liveTwrpDirectory = """
        <html><body>
        <a href="https://twrp.me/">Home</a> <a href="/public.asc">Public Key</a> <a href="https://github.com/TeamWin">GitHub</a>
        <table>
          <tr><td><a href="/enchilada/twrp-installer-3.7.0_11-0-enchilada.zip.html">twrp-installer-3.7.0_11-0-enchilada.zip</a></td><td><span class="filesize"><small>22.4M</small></span></td></tr>
          <tr><td><a href="/enchilada/twrp-3.7.0_11-0-enchilada.img.html">twrp-3.7.0_11-0-enchilada.img</a></td><td><span class="filesize"><small>64M</small></span></td></tr>
          <tr><td><a href="/enchilada/twrp-3.7.0_11-0-enchilada.img.tar.html">twrp-3.7.0_11-0-enchilada.img.tar</a></td></tr>
          <tr><td><a href="/enchilada/twrp-3.6.2_11-0-enchilada.img.html">twrp-3.6.2_11-0-enchilada.img</a></td></tr>
          <tr><td><a href="/guacamole/twrp-3.7.1_12-0-guacamole.img.html">another device</a></td></tr>
          <tr><td><a href="//evil.example/enchilada/twrp-9.img.html">no</a></td></tr>
          <tr><td><a href="/enchilada/../etc/twrp-9.img.html">no</a></td></tr>
          <tr><td><a href="https://dl.twrp.me/enchilada/twrp-9.img.html">no</a></td></tr>
        </table></body></html>
    """.trimIndent()

    @Test
    fun twrpDirectoryReadsTheListingTheSiteServes() {
        val client = RecoveryCatalogClient { url, _ -> error("the listing needs no other fetch: $url") }
        val offers = client.parseTwrpDirectory(liveTwrpDirectory, "enchilada", "OnePlus 6 (enchilada)")
        assertEquals(
            listOf(
                "twrp-installer-3.7.0_11-0-enchilada.zip",
                "twrp-3.7.0_11-0-enchilada.img",
                "twrp-3.6.2_11-0-enchilada.img",
            ),
            offers.map { it.filename },
        )
        val img = offers[1]
        assertEquals(RecoveryPayload.IMAGE, img.payload)
        assertEquals(RecoveryPayload.ZIP, offers[0].payload)
        assertEquals("https://dl.twrp.me/enchilada/twrp-3.7.0_11-0-enchilada.img", img.safeDownloadUrl())
        // the file's own page is what the Page button opens and what the image host wants as the Referer
        assertEquals("https://dl.twrp.me/enchilada/twrp-3.7.0_11-0-enchilada.img.html", img.pageUrl)
        assertEquals(img.pageUrl, img.referer)
        assertEquals("3.7.0_11-0", img.version)
        assertTrue(offers.all { it.codename == "enchilada" && it.project == RecoveryProject.TWRP })
    }

    @Test
    fun twrpSearchKeepsTheSpellingTheTitleUses() {
        val json = """[{ "title" : "ASUS ROG Phone 6/6 Pro (Qualcomm ONLY) (AI2201)", "desc" : "x", "url" : "/asus/rogphone6.html" }]"""
        val client = RecoveryCatalogClient { _, _ -> error("no fetch") }
        assertEquals("AI2201", client.matchTwrpSearch(json, listOf("ai2201")).single().codename)
        assertEquals("AI2201", client.matchTwrpSearch(json, listOf("AI2201")).single().codename)
        assertEquals(listOf("AI2201"), RecoveryCatalogClient.titleCodenames("ASUS ROG Phone 6/6 Pro (Qualcomm ONLY) (AI2201)"))
        // the old lower-casing entry point is unchanged
        assertEquals(listOf("ai2201"), RecoveryCatalogClient.extractCodenames("ASUS ROG Phone 6/6 Pro (Qualcomm ONLY) (AI2201)"))
    }

    /** dl.twrp.me/AI2201/ is a page and dl.twrp.me/ai2201/ is a 404, so the lookup must ask for the first. */
    @Test
    fun twrpLookupAsksForTheDirectoryInTheSitesCase() {
        val asked = mutableListOf<String>()
        val client = RecoveryCatalogClient { url, _ ->
            asked += url
            when (url) {
                RecoveryCatalogClient.TWRP_SEARCH ->
                    """[{ "title" : "ASUS ROG Phone 6/6 Pro (Qualcomm ONLY) (AI2201)", "desc" : "x", "url" : "/asus/rogphone6.html" }]"""
                "https://dl.twrp.me/AI2201/" ->
                    """<a href="/AI2201/twrp-3.7.1_12-0-AI2201.img.html">twrp-3.7.1_12-0-AI2201.img</a>"""
                else -> throw IOException("HTTP 404 from ${url.substringAfter("https://").substringBefore('/')}")
            }
        }
        // the phone reports its codename in lower case; the site spells it in upper case
        val offers = client.lookupTwrp(listOf("ai2201"))
        assertEquals("https://dl.twrp.me/AI2201/twrp-3.7.1_12-0-AI2201.img", offers.single().downloadUrl)
        assertTrue(asked.none { it.contains("/ai2201/") })
    }

    @Test
    fun twrpLookupStillReadsTheDirectoryWhenSearchJsonIsGarbage() {
        val asked = mutableListOf<String>()
        val client = RecoveryCatalogClient { url, _ ->
            asked += url
            when (url) {
                RecoveryCatalogClient.TWRP_SEARCH -> "<html>not json"
                "https://dl.twrp.me/enchilada/" ->
                    """<a href="/enchilada/twrp-3.7.0_11-0-enchilada.img.html">twrp-3.7.0_11-0-enchilada.img</a>"""
                else -> throw IOException("unexpected $url")
            }
        }
        val offers = client.lookupTwrp(listOf("enchilada"))
        assertEquals("https://dl.twrp.me/enchilada/twrp-3.7.0_11-0-enchilada.img", offers.single().downloadUrl)
        assertTrue(asked.contains("https://dl.twrp.me/enchilada/"))
    }

    /** The device page's HTML has no download links; it loads builds-<codename>.json, whose links end in /download. */
    private val liveBuildsJson = """
        {
          "latest": {
            "version": "4.0", "build_type": "OFFICIAL", "date": "2022-12-25",
            "download_link": "https://sourceforge.net/projects/pbrp/files/enchilada/PBRP-enchilada-4.0-20221225-1615-OFFICIAL.zip/download",
            "github_release": null, "changelog": "- Auto-fetched from SourceForge"
          },
          "older_builds": [
            {
              "version": "3.1.0", "build_type": "OFFICIAL", "date": "2021-05-15",
              "download_link": "https://sourceforge.net/projects/pbrp/files/enchilada/PBRP-enchilada-3.1.0-20210515-0953-OFFICIAL.zip/download",
              "github_release": null, "changelog": "- Auto-fetched from SourceForge"
            },
            { "version": "3.0.0", "download_link": "https://evil.example/projects/pbrp/files/enchilada/PBRP-enchilada-3.0.0.zip/download" },
            { "version": "2.9.0", "download_link": "http://sourceforge.net/projects/pbrp/files/enchilada/PBRP-enchilada-2.9.0.zip/download" },
            { "version": "2.8.0", "download_link": "https://sourceforge.net/projects/pbrp/files/enchilada/notes.txt/download" },
            { "version": null, "download_link": null }
          ]
        }
    """.trimIndent()

    @Test
    fun pbrpBuildsJsonGivesOneOfferPerOfficialBuild() {
        val client = RecoveryCatalogClient { _, _ -> error("no fetch") }
        val offers = client.parsePbrpBuilds(liveBuildsJson, "enchilada", "https://pitchblackrecovery.com/enchilada/")
        assertEquals(
            listOf("PBRP-enchilada-4.0-20221225-1615-OFFICIAL.zip", "PBRP-enchilada-3.1.0-20210515-0953-OFFICIAL.zip"),
            offers.map { it.filename },
        )
        assertEquals(listOf("4.0", "3.1.0"), offers.map { it.version })
        assertTrue(offers.all { it.payload == RecoveryPayload.ZIP && it.project == RecoveryProject.PITCHBLACK })
        assertTrue(offers.all { it.downloadUrl.endsWith("/download") && it.safeDownloadUrl() == it.downloadUrl })
        assertEquals("https://pitchblackrecovery.com/enchilada/", offers[0].pageUrl)
    }

    @Test
    fun pbrpLookupReadsTheBuildsJsonInTheDevicesOwnCase() {
        val asked = mutableListOf<String>()
        val client = RecoveryCatalogClient { url, _ ->
            asked += url
            if (url == "https://pitchblackrecovery.com/assets/json/builds-X00P.json") liveBuildsJson else throw IOException("HTTP 404")
        }
        val offers = client.lookupPitchBlack(listOf("X00P"))
        assertEquals(2, offers.size)
        assertEquals(listOf("https://pitchblackrecovery.com/assets/json/builds-X00P.json"), asked)
    }

    @Test
    fun pbrpLookupTriesLowerCaseWhenTheSiteKeepsTheDeviceThere() {
        val asked = mutableListOf<String>()
        val client = RecoveryCatalogClient { url, _ ->
            asked += url
            if (url == "https://pitchblackrecovery.com/assets/json/builds-enchilada.json") liveBuildsJson else throw IOException("HTTP 404")
        }
        assertEquals(2, client.lookupPitchBlack(listOf("Enchilada")).size)
        assertEquals(
            listOf(
                "https://pitchblackrecovery.com/assets/json/builds-Enchilada.json",
                "https://pitchblackrecovery.com/assets/json/builds-enchilada.json",
            ),
            asked,
        )
    }

    /** The app hands over lower-cased names (RecoveryIdentity.lookupNames), but the site keeps this device under X00P. */
    @Test
    fun pbrpLookupTakesTheSitesSpellingFromItsDeviceList() {
        val asked = mutableListOf<String>()
        val client = RecoveryCatalogClient { url, _ ->
            asked += url
            when (url) {
                "https://pitchblackrecovery.com/" ->
                    """<a href="/docs/">Docs</a> <a href="/#devices">Devices</a> <a href="/I01WD/">I01WD</a> <a href="/X00P/">X00P</a> <a href="/enchilada/">enchilada</a>"""
                "https://pitchblackrecovery.com/assets/json/builds-X00P.json" -> liveBuildsJson
                else -> throw IOException("HTTP 404")
            }
        }
        val offers = client.lookupPitchBlack(listOf("x00p"))
        assertEquals(2, offers.size)
        assertTrue(offers.all { it.codename == "x00p" })
        assertEquals(
            listOf(
                "https://pitchblackrecovery.com/assets/json/builds-x00p.json",
                "https://pitchblackrecovery.com/",
                "https://pitchblackrecovery.com/assets/json/builds-X00P.json",
            ),
            asked,
        )
    }

    /** A device the site does not list costs one read of the home page, once for the whole lookup. */
    @Test
    fun pbrpDeviceListIsReadOnceForAllNames() {
        val asked = mutableListOf<String>()
        val client = RecoveryCatalogClient { url, _ ->
            asked += url
            if (url == "https://pitchblackrecovery.com/") """<a href="/X00P/">X00P</a>""" else throw IOException("HTTP 404")
        }
        assertTrue(client.lookupPitchBlack(listOf("nosuchdevice", "othermissing")).isEmpty())
        assertEquals(1, asked.count { it == "https://pitchblackrecovery.com/" })
    }

    /** SourceForge's listing links each build as `.../<file>.zip/download`; the file's folder and info links are not downloads. */
    @Test
    fun sourceForgeListingLinksEndInDownload() {
        val html = """
            <a href="/projects/pbrp/files/olive/">olive</a>
            <a href="https://sourceforge.net/projects/pbrp/files/olive/PBRP-olive-3.1.0-20210820-2120-OFFICIAL.zip/download">zip</a>
            <a href="/projects/pbrp/files/olive/PBRP-olive-3.1.0-20210820-2120-OFFICIAL.zip/stats/timeline">stats</a>
            <a href="/projects/pbrp/files/olive/PBRP-olive-3.1.0-20210820-2120-OFFICIAL.zip/">info</a>
            <a href="https://evil.example/projects/pbrp/files/olive/PBRP-olive-9.zip/download">no</a>
        """.trimIndent()
        val client = RecoveryCatalogClient { _, _ -> error("no fetch") }
        val offers = client.parseSourceForgeFiles(html, "olive", "https://sourceforge.net/projects/pbrp/files/olive/")
        assertEquals(
            listOf("https://sourceforge.net/projects/pbrp/files/olive/PBRP-olive-3.1.0-20210820-2120-OFFICIAL.zip/download"),
            offers.map { it.downloadUrl },
        )
        assertEquals("PBRP-olive-3.1.0-20210820-2120-OFFICIAL.zip", offers.single().filename)
    }

    @Test
    fun pbrpLookupFallsBackToSourceForgeWhenTheBuildsJsonIsMissing() {
        val client = RecoveryCatalogClient { url, _ ->
            when (url) {
                "https://sourceforge.net/projects/pbrp/files/olive/" ->
                    """<a href="https://sourceforge.net/projects/pbrp/files/olive/PBRP-olive-3.1.0-20210820-2120-OFFICIAL.zip/download">zip</a>"""
                else -> throw IOException("HTTP 404")
            }
        }
        val offers = client.lookupPitchBlack(listOf("olive"))
        assertEquals("PBRP-olive-3.1.0-20210820-2120-OFFICIAL.zip", offers.single().filename)
    }

    @Test
    fun linkedFileNameTakesTheFileNotTheWordDownload() {
        fun name(url: String) = RecoveryCatalogClient.linkedFileName(url)
        assertEquals("a.zip", name("https://sourceforge.net/projects/pbrp/files/x/a.zip/download"))
        assertEquals("a.zip", name("https://github.com/PitchBlackRecoveryProject/r/releases/download/4.0/a.zip"))
        assertEquals("a.zip", name("https://downloads.sourceforge.net/project/pbrp/x/a.zip?use_mirror=m"))
        assertNull(name("https://sourceforge.net/projects/pbrp/files/x/a.zip/"))
        assertNull(name("https://sourceforge.net/projects/pbrp/files/x/%2e%2e%2fa.zip/download"))
        assertNull(name("https://sourceforge.net/download"))
        assertNull(name("not a url"))
    }
}
