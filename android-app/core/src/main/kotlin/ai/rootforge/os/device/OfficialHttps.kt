package ai.rootforge.os.device

import java.net.URI

/**
 * One official https page. RootForge only opens these in the system browser.
 * Nothing here is downloaded or bundled.
 */
data class OfficialHttps(
    val label: String,
    val url: String,
    val allowedHosts: List<String>,
    val note: String,
) {
    init {
        require(label.isNotBlank() && note.isNotBlank()) { label }
        require(url.startsWith("https://")) { "$label: urls must be https" }
        require(!url.contains(' ')) { "$label: url has whitespace" }
        require(!note.contains('\n')) { "$label: keep notes one line" }
        val host = URI(url).host.orEmpty().lowercase()
        require(allowedHosts.isNotEmpty()) { "$label: live page needs allowedHosts" }
        require(allowedHosts.any { host == it || host.endsWith(".$it") }) {
            "$label: $host is not an allowed host $allowedHosts"
        }
    }
}
