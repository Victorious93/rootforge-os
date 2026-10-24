package ai.rootforge.os.recovery

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class RecoveryProject { TWRP, PITCHBLACK }

data class RecoveryOffer(
    val project: RecoveryProject,
    val title: String,
    val codename: String,
    val filename: String,
    val payload: RecoveryPayload,
    val downloadUrl: String,
    val pageUrl: String,
    val version: String? = null,
    val referer: String? = null,
    val md5: String? = null,
    val sha256: String? = null,
    val sizeHint: String? = null,
    val hasPgpSignature: Boolean = false,
) {
    fun safeDownloadUrl(): String? = downloadUrl.takeIf { RecoveryHosts.allowed(it, project) }
}

object RecoveryHosts {
    private val TWRP = setOf("dl.twrp.me", "eu.dl.twrp.me", "twrp.me")
    // pitchblack.tech is the host Custom RR's published catalog uses (2026-09-12).
    private val PBRP = setOf(
        "pitchblackrecovery.com", "www.pitchblackrecovery.com",
        "pitchblack.tech", "www.pitchblack.tech",
        "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com",
        "sourceforge.net", "downloads.sourceforge.net",
    )

    fun allowed(url: String, project: RecoveryProject): Boolean {
        if (!url.startsWith("https://")) return false
        val host = runCatching { URI(url).host.lowercase() }.getOrNull() ?: return false
        return when (project) {
            RecoveryProject.TWRP -> host in TWRP
            RecoveryProject.PITCHBLACK -> host in PBRP
        }
    }
}

object RecoveryChecksum {
    private val MD5_HEX = Regex("""\b([0-9a-fA-F]{32})\b""")
    private val SHA_HEX = Regex("""\b([0-9a-fA-F]{64})\b""")
    private val BSD = Regex("""MD5\s*\(([^)]+)\)\s*=\s*([0-9a-fA-F]{32})""")

    /** TWRP `.img.md5` (GNU `md5sum` or BSD `MD5 (file) = hex`). */
    fun parseMd5File(text: String): String? {
        BSD.find(text)?.let { return it.groupValues[2].lowercase() }
        return MD5_HEX.find(text)?.groupValues?.get(1)?.lowercase()
    }

    fun parseSha256(text: String): String? = SHA_HEX.find(text)?.groupValues?.get(1)?.lowercase()
}

@Serializable
data class TwrpSearchEntry(
    val title: String = "",
    val desc: String = "",
    val url: String = "",
)

/**
 * `https://pitchblackrecovery.com/assets/json/builds-<codename>.json`, the file the device page itself
 * loads: the page's HTML has no download links. Fields are nullable because the site writes `null`.
 */
@Serializable
data class PbrpBuild(
    val version: String? = null,
    @SerialName("download_link") val downloadLink: String? = null,
)

@Serializable
data class PbrpBuilds(
    val latest: PbrpBuild? = null,
    @SerialName("older_builds") val olderBuilds: List<PbrpBuild> = emptyList(),
)

@Serializable
data class GithubRepoSearch(
    val items: List<GithubRepo> = emptyList(),
)

@Serializable
data class GithubRepo(
    @SerialName("full_name") val fullName: String = "",
    val name: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
)

@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val body: String = "",
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
data class GithubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0,
)

/**
 * Looks up official TWRP (twrp.me search.json + dl.twrp.me) and PitchBlack
 * (the pitchblackrecovery.com builds JSON, then SourceForge and GitHub releases)
 * for a codename. Nothing is fetched until [lookup] is called.
 *
 * Both sites name a device in its own case (dl.twrp.me/AI2201/ and
 * pitchblackrecovery.com/X00P/ are pages; the lower-case spellings are 404s) and the
 * app passes lower-cased codenames, so TWRP's spelling comes from its search titles
 * and PitchBlack's from its device list when the name as given misses.
 */
class RecoveryCatalogClient(
    private val fetchText: (url: String, referer: String?) -> String = { url, referer ->
        RecoveryFetcher.fetchText(url, referer)
    },
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun lookup(codenames: List<String>, project: RecoveryProject? = null): List<RecoveryOffer> {
        val names = codenames.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        if (names.isEmpty()) return emptyList()
        val out = mutableListOf<RecoveryOffer>()
        val errors = mutableListOf<String>()
        when (project) {
            null -> {
                runCatching { out += lookupTwrp(names) }.onFailure { errors += "TWRP: ${it.message}" }
                runCatching { out += lookupPitchBlack(names) }.onFailure { errors += "PitchBlack: ${it.message}" }
            }
            RecoveryProject.TWRP ->
                runCatching { out += lookupTwrp(names) }.onFailure { errors += "TWRP: ${it.message}" }
            RecoveryProject.PITCHBLACK ->
                runCatching { out += lookupPitchBlack(names) }.onFailure { errors += "PitchBlack: ${it.message}" }
        }
        if (out.isEmpty() && errors.isNotEmpty()) throw IOException(errors.joinToString(" "))
        return out.distinctBy { it.downloadUrl }
    }

    fun lookupTwrp(codenames: List<String>): List<RecoveryOffer> {
        val search = runCatching { fetchText(TWRP_SEARCH, null) }.getOrNull()
        val matched = search?.let { matchTwrpSearch(it, codenames) }.orEmpty()
        val tryNames = (matched.map { it.codename } + codenames).distinctBy { it.lowercase() }
        val out = mutableListOf<RecoveryOffer>()
        for (name in tryNames) {
            val page = "https://dl.twrp.me/$name/"
            val html = runCatching { fetchText(page, null) }.getOrNull() ?: continue
            out += parseTwrpDirectory(html, name, matched.firstOrNull { it.codename.equals(name, true) }?.title)
        }
        return out
    }

    fun lookupPitchBlack(codenames: List<String>): List<RecoveryOffer> {
        val out = mutableListOf<RecoveryOffer>()
        for (name in codenames) {
            val tried = pbrpSpellings(name).toMutableList()
            // The site's own build list first: it is what the device page shows, and the page's HTML has no links.
            var listed = pbrpBuilds(name, tried)
            if (listed == null) {
                // The app hands over lower-cased names, but the site keeps X00P under X00P: take the spelling from its device list.
                val spelled = pbrpDeviceDirs.firstOrNull { it.equals(name.trim(), ignoreCase = true) && it !in tried }
                if (spelled != null) {
                    tried += spelled
                    listed = pbrpBuilds(name, listOf(spelled))
                }
            }
            if (listed != null) {
                out += listed
                continue
            }
            for (spelling in tried) {
                val page = "$PBRP_SITE/$spelling/"
                val html = runCatching { fetchText(page, null) }.getOrNull()
                if (html != null) out += parsePbrpDevicePage(html, name, page)
                val sf = "https://sourceforge.net/projects/pbrp/files/$spelling/"
                val sfHtml = runCatching { fetchText(sf, null) }.getOrNull()
                if (sfHtml != null) out += parseSourceForgeFiles(sfHtml, name, sf)
            }
            out += lookupPbrpGithub(name)
        }
        return out.distinctBy { it.downloadUrl }
    }

    /** Offers from the first of [spellings] whose builds list the site serves and which names an official build. */
    private fun pbrpBuilds(name: String, spellings: List<String>): List<RecoveryOffer>? =
        spellings.firstNotNullOfOrNull { spelling ->
            val body = runCatching { fetchText("$PBRP_SITE/assets/json/builds-$spelling.json", null) }.getOrNull()
                ?: return@firstNotNullOfOrNull null
            runCatching { parsePbrpBuilds(body, name, "$PBRP_SITE/$spelling/") }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

    /** Device folders linked from pitchblackrecovery.com's home page (`/X00P/`), fetched once and only when needed. */
    private val pbrpDeviceDirs: List<String> by lazy {
        val html = runCatching { fetchText("$PBRP_SITE/", null) }.getOrNull().orEmpty()
        hrefs(html).mapNotNull { PBRP_DEVICE_DIR.matchEntire(it)?.groupValues?.get(1) }.distinct()
    }

    fun matchTwrpSearch(searchJson: String, codenames: List<String>): List<TwrpMatch> {
        val entries = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(TwrpSearchEntry.serializer()), searchJson)
        val want = codenames.map { it.lowercase() }.toSet()
        return entries.mapNotNull { e ->
            // The title's own spelling goes into dl.twrp.me/<codename>/, which is case-sensitive.
            val hit = titleCodenames(e.title).firstOrNull { it.lowercase() in want } ?: return@mapNotNull null
            TwrpMatch(title = e.title, codename = hit, pageUrl = twrpPage(e.url))
        }
    }

    fun parseTwrpDirectory(html: String, codename: String, title: String?): List<RecoveryOffer> {
        val links = hrefs(html).mapNotNull { twrpLink(it, codename) }
        val names = links.map { it.file }
        val images = links.filter { it.file.endsWith(".img", true) || it.file.endsWith(".zip", true) }
            .filterNot { it.file.endsWith(".md5", true) || it.file.endsWith(".asc", true) }
        val md5s = names.filter { it.endsWith(".md5", true) }.toSet()
        val ascs = names.filter { it.endsWith(".asc", true) }.toSet()
        val page = "https://dl.twrp.me/$codename/"
        return images.map { link ->
            val name = link.file
            val payload = if (name.endsWith(".zip", true)) RecoveryPayload.ZIP else RecoveryPayload.IMAGE
            val md5Name = "$name.md5"
            val md5Hex = if (md5Name in md5s) {
                runCatching { RecoveryChecksum.parseMd5File(fetchText("$page$md5Name", page)) }.getOrNull()
            } else null
            // TWRP asks people to arrive through the file's own page, and the image host serves the file only to a
            // request that names a dl.twrp.me page as its Referer (without one it returns that page's HTML).
            val filePage = link.page ?: page
            RecoveryOffer(
                project = RecoveryProject.TWRP,
                title = title?.ifBlank { "TWRP for $codename" } ?: "TWRP for $codename",
                codename = codename,
                filename = name,
                payload = payload,
                downloadUrl = "$page$name",
                pageUrl = filePage,
                version = twrpVersion(name),
                referer = filePage,
                md5 = md5Hex,
                hasPgpSignature = "$name.md5.asc" in ascs || "$name.asc" in ascs,
            )
        }
    }

    /**
     * Offers from `builds-<codename>.json`. Only https links on the PitchBlack allow-list are kept, and the file
     * name is taken from the link (SourceForge's end in `/download`).
     */
    fun parsePbrpBuilds(body: String, codename: String, pageUrl: String): List<RecoveryOffer> {
        val builds = json.decodeFromString(PbrpBuilds.serializer(), body)
        return (listOfNotNull(builds.latest) + builds.olderBuilds).mapNotNull { build ->
            val link = build.downloadLink?.trim().orEmpty()
            if (!RecoveryHosts.allowed(link, RecoveryProject.PITCHBLACK)) return@mapNotNull null
            val file = linkedFileName(link) ?: return@mapNotNull null
            if (!file.endsWith(".img", true) && !file.endsWith(".zip", true)) return@mapNotNull null
            RecoveryOffer(
                project = RecoveryProject.PITCHBLACK,
                title = "PitchBlack Recovery for $codename",
                codename = codename,
                filename = file,
                payload = if (file.endsWith(".zip", true)) RecoveryPayload.ZIP else RecoveryPayload.IMAGE,
                downloadUrl = link,
                pageUrl = pageUrl,
                version = build.version?.takeIf { it.isNotBlank() } ?: pbrpVersion(file),
            )
        }
    }

    fun parsePbrpDevicePage(html: String, codename: String, pageUrl: String): List<RecoveryOffer> {
        val out = mutableListOf<RecoveryOffer>()
        for (href in hrefs(html)) {
            val abs = absolutize(pageUrl, href) ?: continue
            val file = abs.substringAfterLast('/').substringBefore('?')
            if (!SAFE_FILE.matches(file)) continue
            if (!file.endsWith(".img", true) && !file.endsWith(".zip", true)) continue
            if (!RecoveryHosts.allowed(abs, RecoveryProject.PITCHBLACK)) continue
            out += RecoveryOffer(
                project = RecoveryProject.PITCHBLACK,
                title = "PitchBlack Recovery for $codename",
                codename = codename,
                filename = file,
                payload = if (file.endsWith(".zip", true)) RecoveryPayload.ZIP else RecoveryPayload.IMAGE,
                downloadUrl = abs,
                pageUrl = pageUrl,
                version = pbrpVersion(file),
            )
        }
        val githubRepo = hrefs(html).mapNotNull { absolutize(pageUrl, it) }
            .firstOrNull { it.startsWith("https://github.com/PitchBlackRecoveryProject/") }
        if (githubRepo != null) {
            val repo = githubRepo.removePrefix("https://github.com/").trim('/').substringBefore("/releases")
            out += releasesForRepo(repo, codename, pageUrl)
        }
        return out
    }

    fun parseSourceForgeFiles(html: String, codename: String, pageUrl: String): List<RecoveryOffer> {
        return hrefs(html).mapNotNull { href ->
            val abs = absolutize(pageUrl, href) ?: return@mapNotNull null
            val file = linkedFileName(abs) ?: return@mapNotNull null
            if (!file.endsWith(".img", true) && !file.endsWith(".zip", true)) return@mapNotNull null
            if (!RecoveryHosts.allowed(abs, RecoveryProject.PITCHBLACK)) return@mapNotNull null
            RecoveryOffer(
                project = RecoveryProject.PITCHBLACK,
                title = "PitchBlack Recovery for $codename",
                codename = codename,
                filename = file,
                payload = if (file.endsWith(".zip", true)) RecoveryPayload.ZIP else RecoveryPayload.IMAGE,
                downloadUrl = abs,
                pageUrl = pageUrl,
                version = pbrpVersion(file),
            )
        }
    }

    fun parseGithubReleases(body: String, repo: String, codename: String, pageUrl: String): List<RecoveryOffer> {
        val release = json.decodeFromString(GithubRelease.serializer(), body)
        val shaFromBody = RecoveryChecksum.parseSha256(release.body)
        return release.assets.mapNotNull { asset ->
            val file = asset.name
            if (!SAFE_FILE.matches(file)) return@mapNotNull null
            if (!file.endsWith(".img", true) && !file.endsWith(".zip", true)) return@mapNotNull null
            if (!RecoveryHosts.allowed(asset.browserDownloadUrl, RecoveryProject.PITCHBLACK)) return@mapNotNull null
            RecoveryOffer(
                project = RecoveryProject.PITCHBLACK,
                title = "PitchBlack Recovery for $codename",
                codename = codename,
                filename = file,
                payload = if (file.endsWith(".zip", true)) RecoveryPayload.ZIP else RecoveryPayload.IMAGE,
                downloadUrl = asset.browserDownloadUrl,
                pageUrl = release.htmlUrl.ifBlank { pageUrl },
                version = release.tagName.ifBlank { pbrpVersion(file) },
                sha256 = shaFromBody,
                sizeHint = if (asset.size > 0) asset.size.toString() else null,
            )
        }
    }

    private fun lookupPbrpGithub(codename: String): List<RecoveryOffer> {
        val q = URLEncoder.encode("org:PitchBlackRecoveryProject $codename in:name", "UTF-8")
        val body = runCatching { fetchText("https://api.github.com/search/repositories?q=$q", null) }.getOrNull()
            ?: return emptyList()
        val search = json.decodeFromString(GithubRepoSearch.serializer(), body)
        val repo = search.items.firstOrNull { repo ->
            repo.fullName.startsWith("PitchBlackRecoveryProject/") &&
                repo.name.lowercase().contains(codename.lowercase())
        } ?: return emptyList()
        return releasesForRepo(repo.fullName, codename, repo.htmlUrl)
    }

    private fun releasesForRepo(repo: String, codename: String, pageUrl: String): List<RecoveryOffer> {
        if (!repo.matches(Regex("""^PitchBlackRecoveryProject/[A-Za-z0-9._-]+$"""))) return emptyList()
        val body = runCatching { fetchText("https://api.github.com/repos/$repo/releases/latest", null) }.getOrNull()
            ?: return emptyList()
        return parseGithubReleases(body, repo, codename, pageUrl)
    }

    data class TwrpMatch(val title: String, val codename: String, val pageUrl: String)

    /** A file on a dl.twrp.me device directory; [page] is the file's own download page when the listing links to it. */
    private class TwrpLink(val file: String, val page: String?)

    /**
     * dl.twrp.me lists each file as a root-relative link to the file's download page, `/<codename>/<file>.html`; the
     * file itself sits next to it without the `.html`. A plain relative link to a file is read as well.
     */
    private fun twrpLink(href: String, codename: String): TwrpLink? {
        if (".." in href || "://" in href) return null
        if (href.startsWith("/")) {
            val parts = href.removePrefix("/").split('/')
            if (parts.size != 2 || parts[0] != codename || !parts[1].endsWith(".html")) return null
            val file = parts[1].removeSuffix(".html").takeIf { SAFE_FILE.matches(it) } ?: return null
            return TwrpLink(file, "https://dl.twrp.me/$codename/${parts[1]}")
        }
        return href.substringAfterLast('/').takeIf { SAFE_FILE.matches(it) }?.let { TwrpLink(it, null) }
    }

    companion object {
        const val TWRP_SEARCH = "https://twrp.me/search.json"
        private const val PBRP_SITE = "https://pitchblackrecovery.com"
        private val PBRP_DEVICE_DIR = Regex("""/([A-Za-z0-9._+-]+)/""")
        private val HREF = Regex("""(?i)href\s*=\s*["']([^"']+)["']""")
        private val SAFE_FILE = Regex("""^[A-Za-z0-9._+-]+$""")
        private val TITLE_CODE = Regex("""\(([^)]+)\)\s*$""")
        private const val MAX_TITLE_CHARS = 300
        private val TWRP_VER = Regex("""twrp-([0-9][A-Za-z0-9._-]*)-""", RegexOption.IGNORE_CASE)

        /** The codenames in a title's trailing parentheses, spelled as the title spells them. */
        fun titleCodenames(title: String): List<String> {
            // The title is remote text and TITLE_CODE is quadratic in its "(" count (a long run of them
            // took seconds); real titles are a device name and a codename, nowhere near this long.
            if (title.length > MAX_TITLE_CHARS) return emptyList()
            val inner = TITLE_CODE.find(title)?.groupValues?.get(1) ?: return emptyList()
            return inner.split('/', ',', '&').map { it.trim() }.filter { it.isNotEmpty() }
        }

        fun extractCodenames(title: String): List<String> = titleCodenames(title).map { it.lowercase() }

        /** As given first, then lower case: both sites keep a device under one exact spelling. */
        internal fun pbrpSpellings(name: String): List<String> = name.trim().let { listOf(it, it.lowercase()) }.distinct()

        /**
         * The file a download link names: `.../<file>`, or `.../<file>/download` (SourceForge). A trailing slash is a
         * folder or a file's info page, never a download, and the name must be a plain file name.
         */
        internal fun linkedFileName(url: String): String? {
            val path = runCatching { URI(url).rawPath }.getOrNull() ?: return null
            if (path.isNullOrEmpty() || path.endsWith("/")) return null
            val segments = path.split('/').filter { it.isNotEmpty() }
            val file = (if (segments.lastOrNull() == "download") segments.getOrNull(segments.size - 2) else segments.lastOrNull())
                ?: return null
            return file.takeIf { SAFE_FILE.matches(it) }
        }

        fun hrefs(html: String): List<String> = HREF.findAll(html).map { it.groupValues[1] }.toList()

        fun twrpPage(url: String): String =
            if (url.startsWith("https://")) url else "https://twrp.me" + (if (url.startsWith("/")) url else "/$url")

        fun twrpVersion(filename: String): String? = TWRP_VER.find(filename)?.groupValues?.get(1)

        fun pbrpVersion(filename: String): String? {
            val m = Regex("""PBRP-.*?-(\d+(?:\.\d+)*)""", RegexOption.IGNORE_CASE).find(filename)
            return m?.groupValues?.get(1)
        }

        fun absolutize(base: String, href: String): String? {
            val t = href.trim()
            if (t.startsWith("#") || t.startsWith("javascript:", true)) return null
            return runCatching { URI(base).resolve(t).toString() }.getOrNull()
        }
    }
}
