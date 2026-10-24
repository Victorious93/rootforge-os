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
 * (pitchblackrecovery.com + GitHub releases) for a codename. Nothing is
 * fetched until [lookup] is called.
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
            val page = "https://pitchblackrecovery.com/${name.lowercase()}"
            val html = runCatching { fetchText(page, null) }.getOrNull()
            if (html != null) out += parsePbrpDevicePage(html, name, page)
            val sf = "https://sourceforge.net/projects/pbrp/files/${name.lowercase()}/"
            val sfHtml = runCatching { fetchText(sf, null) }.getOrNull()
            if (sfHtml != null) out += parseSourceForgeFiles(sfHtml, name, sf)
            out += lookupPbrpGithub(name)
        }
        return out.distinctBy { it.downloadUrl }
    }

    fun matchTwrpSearch(searchJson: String, codenames: List<String>): List<TwrpMatch> {
        val entries = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(TwrpSearchEntry.serializer()), searchJson)
        val want = codenames.map { it.lowercase() }.toSet()
        return entries.mapNotNull { e ->
            val codes = extractCodenames(e.title)
            val hit = codes.firstOrNull { it in want } ?: return@mapNotNull null
            TwrpMatch(title = e.title, codename = hit, pageUrl = twrpPage(e.url))
        }
    }

    fun parseTwrpDirectory(html: String, codename: String, title: String?): List<RecoveryOffer> {
        val files = hrefs(html).mapNotNull { href ->
            if (".." in href || href.startsWith("/") || href.contains("://")) return@mapNotNull null
            href.substringAfterLast('/').takeIf { SAFE_FILE.matches(it) }
        }
        val images = files.filter { it.endsWith(".img", true) || it.endsWith(".zip", true) }
            .filterNot { it.endsWith(".md5", true) || it.endsWith(".asc", true) }
        val md5s = files.filter { it.endsWith(".md5", true) }.toSet()
        val ascs = files.filter { it.endsWith(".asc", true) }.toSet()
        val page = "https://dl.twrp.me/$codename/"
        return images.map { name ->
            val payload = if (name.endsWith(".zip", true)) RecoveryPayload.ZIP else RecoveryPayload.IMAGE
            val md5Name = "$name.md5"
            val md5Hex = if (md5Name in md5s) {
                runCatching { RecoveryChecksum.parseMd5File(fetchText("$page$md5Name", page)) }.getOrNull()
            } else null
            RecoveryOffer(
                project = RecoveryProject.TWRP,
                title = title?.ifBlank { "TWRP for $codename" } ?: "TWRP for $codename",
                codename = codename,
                filename = name,
                payload = payload,
                downloadUrl = "$page$name",
                pageUrl = page,
                version = twrpVersion(name),
                referer = page,
                md5 = md5Hex,
                hasPgpSignature = "$name.md5.asc" in ascs || "$name.asc" in ascs,
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
            val file = href.substringAfterLast('/').substringBefore('?')
            if (!SAFE_FILE.matches(file)) return@mapNotNull null
            if (!file.endsWith(".img", true) && !file.endsWith(".zip", true)) return@mapNotNull null
            val abs = absolutize(pageUrl, href) ?: return@mapNotNull null
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

    companion object {
        const val TWRP_SEARCH = "https://twrp.me/search.json"
        private val HREF = Regex("""(?i)href\s*=\s*["']([^"']+)["']""")
        private val SAFE_FILE = Regex("""^[A-Za-z0-9._+-]+$""")
        private val TITLE_CODE = Regex("""\(([^)]+)\)\s*$""")
        private const val MAX_TITLE_CHARS = 300
        private val TWRP_VER = Regex("""twrp-([0-9][A-Za-z0-9._-]*)-""", RegexOption.IGNORE_CASE)

        fun extractCodenames(title: String): List<String> {
            // The title is remote text and TITLE_CODE is quadratic in its "(" count (a long run of them
            // took seconds); real titles are a device name and a codename, nowhere near this long.
            if (title.length > MAX_TITLE_CHARS) return emptyList()
            val inner = TITLE_CODE.find(title)?.groupValues?.get(1) ?: return emptyList()
            return inner.split('/', ',', '&').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
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
