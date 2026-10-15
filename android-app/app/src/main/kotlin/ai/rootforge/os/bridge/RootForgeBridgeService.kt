package ai.rootforge.os.bridge

import ai.rootforge.os.RootForgeApp
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.security.MessageDigest

/**
 * The Android transport for the RootForge bridge: a bound Messenger service
 * carrying the same one-line JSON request/response as the SSH transport.
 *
 * It is exported so another app can bind, but binding grants nothing. The
 * caller's identity is taken from the kernel-attested UID of the Message
 * (never from the JSON), and the request goes through [BridgeExecutor]'s
 * pairing check, which only the owner can satisfy from RootForge OS itself.
 */
class RootForgeBridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        messenger = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what != BRIDGE_MSG_REQUEST) return super.handleMessage(msg)
                val replyTo = msg.replyTo ?: return
                val line = msg.data?.getString(BRIDGE_KEY_REQUEST)
                val uid = msg.sendingUid
                scope.launch { reply(replyTo, respond(line, uid)) }
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun respond(line: String?, uid: Int): String {
        val app = application as RootForgeApp
        val nodeId = app.settings.nodeId
        fun err(category: String, message: String) = bridgeJson.encodeToString(
            BridgeResponse(BRIDGE_PROTOCOL_MAJOR, null, nodeId, false, null, BridgeErrorBody(category, message)),
        )
        if (line.isNullOrBlank()) return err(ErrorCategory.MALFORMED, "empty request")
        val caller = resolveCaller(uid) ?: return err(ErrorCategory.NOT_PAIRED, "could not establish the calling app's identity")
        return app.bridge.handle(line, caller)
    }

    private fun reply(to: Messenger, response: String) {
        val m = Message.obtain(null, BRIDGE_MSG_RESPONSE)
        m.data = Bundle().apply { putString(BRIDGE_KEY_RESPONSE, response) }
        runCatching { to.send(m) }
    }

    private fun resolveCaller(uid: Int): CallerIdentity? {
        val packages = packageManager.getPackagesForUid(uid) ?: return null
        // A shared UID would let one package speak for another; refuse the ambiguity.
        if (packages.size != 1) return null
        val pkg = packages[0]
        val hashes = signingCertHashes(pkg) ?: return null
        return CallerIdentity(pkg, hashes)
    }

    @Suppress("DEPRECATION")
    private fun signingCertHashes(pkg: String): String? {
        val signers = try {
            if (Build.VERSION.SDK_INT >= 28) {
                packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            } else {
                packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        if (signers.isNullOrEmpty()) return null
        return signers.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.sorted().joinToString(",")
    }

    companion object {
        const val ACTION_BIND = BRIDGE_ACTION
        const val MSG_EXCHANGE = BRIDGE_MSG_REQUEST
        const val MSG_RESPONSE = BRIDGE_MSG_RESPONSE
        const val KEY_REQUEST = BRIDGE_KEY_REQUEST
        const val KEY_RESPONSE = BRIDGE_KEY_RESPONSE
    }
}
