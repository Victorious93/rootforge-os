package ai.rootforge.os.catalog

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A catalog the user downloaded or imported, plus where it came from. */
data class LoadedCatalog(
    val catalog: BuildCatalog,
    val freshness: FreshnessFeed?,
    /** e.g. "Downloaded from Custom RR" or "Imported catalog.json". */
    val source: String,
)

/**
 * Fetches the two Custom RR files over https. Only ever called from an
 * explicit user action; nothing here runs on a schedule or at startup.
 */
object BuildCatalogFetcher {
    const val CATALOG_URL = "https://raw.githubusercontent.com/monsiu/Custom-RR/main/assets/catalog.json"
    const val FRESHNESS_URL = "https://raw.githubusercontent.com/monsiu/Custom-RR/main/assets/freshness.json"

    /** The published catalog is about 1.4 MB; anything far bigger is not it. */
    const val MAX_BYTES = 8L * 1024 * 1024

    fun fetch(url: String, maxBytes: Long = MAX_BYTES, open: (URL) -> InputStream = ::openHttps): String {
        require(url.startsWith("https://")) { "Only https URLs are fetched: $url" }
        return open(URL(url)).use { readCapped(it, maxBytes) }
    }

    internal fun readCapped(input: InputStream, maxBytes: Long): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IOException("Response is larger than $maxBytes bytes")
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun openHttps(url: URL): InputStream {
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        // HttpURLConnection never follows a redirect that changes protocol, so https stays https.
        c.instanceFollowRedirects = true
        val code = c.responseCode
        if (code != 200) {
            c.disconnect()
            throw IOException("HTTP $code from ${url.host}")
        }
        if (c.url.protocol != "https") {
            c.disconnect()
            throw IOException("Redirected away from https")
        }
        return c.inputStream
    }
}

/**
 * Keeps the last good catalog in [dir] (app-private storage). Every write is
 * parsed first and stored atomically, so a bad download or a wrong file never
 * replaces a good catalog. A stored file that no longer parses loads as none.
 */
class BuildCatalogStore(
    private val dir: File,
    private val fetch: (String) -> String = { BuildCatalogFetcher.fetch(it) },
) {
    private val state = MutableStateFlow<LoadedCatalog?>(null)
    val loaded: StateFlow<LoadedCatalog?> = state.asStateFlow()

    private val catalogFile get() = File(dir, "catalog.json")
    private val freshnessFile get() = File(dir, "freshness.json")
    private val sourceFile get() = File(dir, "source.txt")

    init {
        dir.mkdirs()
        state.value = readStored()
    }

    /** Blocking; call off the main thread. Returns a line for the log. */
    fun download(): Result<String> = runCatching {
        val catalogText = fetch(BuildCatalogFetcher.CATALOG_URL)
        requireCatalog(catalogText)
        val freshnessText = runCatching { fetch(BuildCatalogFetcher.FRESHNESS_URL).also { requireFreshness(it) } }
        writeAtomic(catalogFile, catalogText)
        freshnessText.onSuccess { writeAtomic(freshnessFile, it) }
        writeAtomic(sourceFile, "Downloaded from Custom RR")
        state.value = readStored()
        val count = state.value?.catalog?.let { it.roms.size + it.recoveries.size + it.roots.size } ?: 0
        freshnessText.fold(
            onSuccess = { "Downloaded the Custom RR catalog ($count entries) and freshness feed." },
            onFailure = { "Downloaded the Custom RR catalog ($count entries); freshness feed failed: ${it.message}" },
        )
    }

    /** Stores a user-picked `catalog.json` or `freshness.json`; which one is decided by its keys. */
    fun import(file: File): Result<String> = runCatching {
        if (file.length() > BuildCatalogFetcher.MAX_BYTES) throw IOException("${file.name} is larger than ${BuildCatalogFetcher.MAX_BYTES} bytes")
        val text = file.readText()
        when (kindOf(text)) {
            Kind.CATALOG -> {
                requireCatalog(text)
                writeAtomic(catalogFile, text)
                writeAtomic(sourceFile, "Imported ${file.name}")
                state.value = readStored()
                "Imported build catalog from ${file.name}."
            }
            Kind.FRESHNESS -> {
                requireFreshness(text)
                writeAtomic(freshnessFile, text)
                state.value = readStored()
                if (state.value == null) "Imported freshness feed from ${file.name}; import or download a catalog to use it."
                else "Imported freshness feed from ${file.name}."
            }
        }
    }

    fun clear() {
        listOf(catalogFile, freshnessFile, sourceFile).forEach { it.delete() }
        state.value = null
    }

    private fun readStored(): LoadedCatalog? {
        val catalog = catalogFile.takeIf { it.isFile }
            ?.let { runCatching { BuildCatalogParser.parseCatalog(it.readText()) }.getOrNull() }
            ?: return null
        val freshness = freshnessFile.takeIf { it.isFile }
            ?.let { runCatching { BuildCatalogParser.parseFreshness(it.readText()) }.getOrNull() }
        val source = sourceFile.takeIf { it.isFile }?.readText()?.trim().orEmpty().ifEmpty { "Stored catalog" }
        return LoadedCatalog(catalog, freshness, source)
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(dir, "${target.name}.part")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Could not store ${target.name}")
        }
    }

    private enum class Kind { CATALOG, FRESHNESS }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private fun kindOf(text: String): Kind {
            val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: throw IllegalArgumentException("Not a JSON object; expected a Custom RR catalog.json or freshness.json.")
            return when {
                listOf("roms", "recoveries", "roots").any { it in root } -> Kind.CATALOG
                "entries" in root -> Kind.FRESHNESS
                else -> throw IllegalArgumentException("Not a Custom RR catalog.json or freshness.json.")
            }
        }

        private fun requireCatalog(text: String) {
            val c = BuildCatalogParser.parseCatalog(text)
            require(c.roms.isNotEmpty() || c.recoveries.isNotEmpty() || c.roots.isNotEmpty()) { "The catalog has no entries." }
        }

        private fun requireFreshness(text: String) {
            BuildCatalogParser.parseFreshness(text)
        }
    }
}
