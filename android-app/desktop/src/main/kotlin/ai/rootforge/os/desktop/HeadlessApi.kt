package ai.rootforge.os.desktop

import ai.rootforge.os.bridge.BRIDGE_PROTOCOL_MAJOR
import ai.rootforge.os.bridge.BridgeIngress
import ai.rootforge.os.bridge.CallerIdentity
import ai.rootforge.os.bridge.PairingPersistence
import ai.rootforge.os.bridge.PairingStore
import ai.rootforge.os.core.PrivateFile
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tiny HTTP front for [BridgeIngress]. Auth is a bearer token stored next to
 * desktop settings (or ROOTFORGE_API_TOKEN). Destructive ops still go through
 * ApprovalCenter — in headless mode the confirmation phrase must be supplied
 * as `confirmation` in the JSON body and is applied automatically.
 */
class HeadlessApi(
    private val executor: BridgeIngress,
    private val pairing: PairingStore,
    private val token: String,
    private val approvals: ai.rootforge.os.core.ApprovalCenter,
    private val webRoot: String,
) {
    fun start(port: Int): HttpServer {
        val caller = CallerIdentity("headless-api", sha256(token))
        pairing.approve(caller)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.createContext("/") { ex ->
            when {
                ex.requestMethod == "GET" && ex.requestURI.path == "/health" ->
                    json(ex, 200, """{"ok":true,"protocol_major":$BRIDGE_PROTOCOL_MAJOR}""")
                ex.requestMethod == "GET" && (ex.requestURI.path == "/" || ex.requestURI.path == "/ui") ->
                    html(ex, webRoot)
                ex.requestMethod == "POST" && ex.requestURI.path == "/v1/bridge" -> handleBridge(ex, caller)
                else -> json(ex, 404, """{"ok":false,"error":"not found"}""")
            }
        }
        server.executor = null
        server.start()
        return server
    }

    private fun handleBridge(ex: HttpExchange, caller: CallerIdentity) {
        if (!authorized(ex)) {
            json(ex, 401, """{"ok":false,"error":"bearer token required"}""")
            return
        }
        val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val confirmation = extractConfirmation(body)
        val waiter = Thread {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val pending = approvals.pending.value.lastOrNull()
                if (pending == null) {
                    Thread.sleep(20)
                    continue
                }
                if (pending.phrase == null) approvals.answer(pending.id, true)
                else approvals.answer(pending.id, true, confirmation ?: "")
                return@Thread
            }
        }.also { it.isDaemon = true; it.start() }
        val response = runBlocking { executor.handle(body, caller) }
        waiter.join(1_000)
        json(ex, 200, response)
    }

    private fun extractConfirmation(body: String): String? {
        val tree = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        return (tree["confirmation"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private fun authorized(ex: HttpExchange): Boolean = tokenMatches(ex.requestHeaders.getFirst("Authorization"), token)

    private fun json(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun html(ex: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    companion object {
        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /**
         * Whether an `Authorization` header carries [token]. Compared in constant time: loopback is
         * shared by every local account, and `==` stops at the first differing character.
         */
        internal fun tokenMatches(header: String?, token: String): Boolean {
            val got = header.orEmpty().removePrefix("Bearer ").trim()
            return got.isNotEmpty() && token.isNotEmpty() &&
                MessageDigest.isEqual(got.toByteArray(StandardCharsets.UTF_8), token.toByteArray(StandardCharsets.UTF_8))
        }

        fun capabilitiesHint(nodeId: String, port: Int): JsonObject = buildJsonObject {
            put("listen", "127.0.0.1:$port")
            put("node_id", nodeId)
            put("protocol_major", BRIDGE_PROTOCOL_MAJOR)
            put("auth", "Authorization: Bearer <token>")
        }

        fun loadWebUi(): String {
            val fromCp = HeadlessApi::class.java.getResource("/web/index.html")?.readText()
            if (!fromCp.isNullOrBlank()) return fromCp
            val file = File("docker/web/index.html")
            if (file.isFile) return file.readText()
            return DEFAULT_UI
        }

        private const val DEFAULT_UI = """<!doctype html><html><body>
<h1>RootForge OS</h1>
<p>Headless API. POST /v1/bridge with a bridge JSON envelope and a Bearer token.</p>
</body></html>"""
    }
}

class FilePairingPersistence(private val file: File) : PairingPersistence {
    override fun read(): String {
        if (!file.isFile) return "[]"
        PrivateFile.restrictToOwner(file)
        return file.readText()
    }

    override fun write(json: String) {
        PrivateFile.prepare(file)
        file.writeText(json)
        PrivateFile.restrictToOwner(file)
    }
}
