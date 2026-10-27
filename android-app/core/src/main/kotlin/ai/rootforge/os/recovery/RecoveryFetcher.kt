package ai.rootforge.os.recovery

import ai.rootforge.os.core.FileDigest
import ai.rootforge.os.core.readPrefix
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * https-only fetch for official recovery indexes and images. Called only
 * from an explicit user action. [referer] must also be https when set
 * (dl.twrp.me serves images to browsers that send the directory page).
 */
object RecoveryFetcher {
    const val TEXT_MAX_BYTES = 8L * 1024 * 1024
    const val IMAGE_MAX_BYTES = 192L * 1024 * 1024
    const val USER_AGENT = "RootForge-OS/0.1 (recovery lookup; +https://github.com/Victorious93/rootforge-os)"

    fun fetchText(
        url: String,
        referer: String? = null,
        maxBytes: Long = TEXT_MAX_BYTES,
        open: (URL, String?) -> InputStream = ::openHttps,
    ): String = open(requireHttps(url), refererHttps(referer)).use { readCapped(it, maxBytes).toString(Charsets.UTF_8) }

    fun fetchBytes(
        url: String,
        referer: String? = null,
        maxBytes: Long = IMAGE_MAX_BYTES,
        open: (URL, String?) -> InputStream = ::openHttps,
    ): ByteArray = open(requireHttps(url), refererHttps(referer)).use { readCapped(it, maxBytes) }

    fun fetchToFile(
        url: String,
        dest: File,
        referer: String? = null,
        maxBytes: Long = IMAGE_MAX_BYTES,
        progress: (Long, Long) -> Unit = { _, _ -> },
        open: (URL, String?) -> InputStream = ::openHttps,
    ): Long {
        requireHttps(url)
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            open(URL(url), refererHttps(referer)).use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IOException("Response is larger than $maxBytes bytes")
                        out.write(buf, 0, n)
                        progress(total, maxBytes)
                    }
                    if (total == 0L) throw IOException("Empty response from ${URL(url).host}")
                }
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                throw IOException("Could not store ${dest.name}")
            }
            return dest.length()
        } catch (e: Exception) {
            // [dest] may already be a library file. A failed download must not remove it;
            // only the partial temp is discarded. Callers that replace a library file
            // do that from a staging name after the body has been checked.
            tmp.delete()
            throw e
        }
    }

    /**
     * Downloads [url] and moves it onto [dest] only after the body is a recovery
     * image or zip and any published checksum matches. An existing [dest] stays
     * where it is when the transfer fails, the host returns HTML, or a checksum
     * does not match — retrying a download must not throw away the copy already
     * in the library.
     */
    fun downloadChecked(
        url: String,
        dest: File,
        referer: String? = null,
        md5: String? = null,
        sha256: String? = null,
        label: String = dest.name,
        open: (URL, String?) -> InputStream = ::openHttps,
    ): Long {
        val parent = dest.parentFile ?: throw IOException("No directory for ${dest.name}")
        parent.mkdirs()
        // Ends in .part so a crash mid-download is hidden from the library listing.
        val staging = File(parent, "${dest.name}.incoming.${System.nanoTime()}.part")
        try {
            fetchToFile(url, staging, referer, open = open)
            val payload = RecoveryPlanner.payloadOf(dest.name, staging.readPrefix(8))
            if (payload == RecoveryPayload.OTHER) {
                throw IOException("$label is not a recovery.img or zip (the host may have returned an HTML page).")
            }
            md5?.let { expected ->
                val check = FileDigest.verifyMd5(expected, FileDigest.md5Hex(staging))
                if (!check.ok) throw IOException(check.message)
            }
            sha256?.let { expected ->
                val check = FileDigest.verify(expected, FileDigest.sha256Hex(staging))
                if (!check.ok) throw IOException(check.message)
            }
            moveIntoPlace(staging, dest)
            return dest.length()
        } catch (e: Exception) {
            staging.delete()
            throw e
        }
    }

    /** Replaces [dest] with [staging]. If the replace fails, [dest] is put back. */
    private fun moveIntoPlace(staging: File, dest: File) {
        if (!dest.exists()) {
            if (!staging.renameTo(dest)) throw IOException("Could not store ${dest.name}")
            return
        }
        val backup = File(dest.parentFile, "${dest.name}.replacing.${System.nanoTime()}.part")
        if (!dest.renameTo(backup)) throw IOException("Could not store ${dest.name}")
        if (!staging.renameTo(dest)) {
            backup.renameTo(dest)
            throw IOException("Could not store ${dest.name}")
        }
        backup.delete()
    }

    internal fun readCapped(input: InputStream, maxBytes: Long): ByteArray {
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
        return out.toByteArray()
    }

    private fun requireHttps(url: String): URL {
        require(url.startsWith("https://")) { "Only https URLs are fetched: $url" }
        return URL(url)
    }

    private fun refererHttps(referer: String?): String? {
        if (referer == null) return null
        require(referer.startsWith("https://")) { "Referer must be https: $referer" }
        return referer
    }

    private fun openHttps(url: URL, referer: String?): InputStream {
        var current = url
        var hops = 0
        while (hops < 8) {
            val c = current.openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", USER_AGENT)
            if (referer != null) c.setRequestProperty("Referer", referer)
            c.setRequestProperty("Accept", "*/*")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("HTTP $code with no Location from ${current.host}")
                c.disconnect()
                val next = URL(current, loc)
                if (next.protocol != "https") throw IOException("Redirected away from https")
                current = next
                hops++
                continue
            }
            if (code != 200) {
                c.disconnect()
                throw IOException("HTTP $code from ${current.host}")
            }
            if (c.url.protocol != "https") {
                c.disconnect()
                throw IOException("Redirected away from https")
            }
            return c.inputStream
        }
        throw IOException("Too many redirects")
    }
}
