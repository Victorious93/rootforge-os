package ai.rootforge.os.theme

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android wiring for [FleetThemeClient]: query the VictorLaunch provider,
 * watch [ContentResolver.notifyChange], and accept the explicit change
 * broadcast only after the sender's package+cert is pinned.
 */
class AndroidFleetTheme(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onQuietLog: (String) -> Unit = {},
) {
    private val resolver: ContentResolver = context.contentResolver
    private val stateFlow = MutableStateFlow(FleetThemeClient.defaultState())
    val state: StateFlow<FleetThemeState> = stateFlow.asStateFlow()

    private val uri: Uri = Uri.parse(FleetThemeContract.URI)
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
        override fun onChange(selfChange: Boolean, uri: Uri?) = refresh()
    }

    @Volatile private var observing = false

    fun start() {
        if (!observing) {
            runCatching { resolver.registerContentObserver(uri, true, observer) }
            observing = true
        }
        refresh()
    }

    fun onBroadcast(senderPackage: String?, senderCerts: Collection<String>) {
        if (!FleetThemeClient.shouldHonorBroadcast(senderPackage, senderCerts)) return
        refresh()
    }

    fun refresh() {
        scope.launch {
            val next = withContext(Dispatchers.IO) { read() }
            if (next != stateFlow.value) {
                stateFlow.value = next
                onQuietLog(next.message)
            }
        }
    }

    private fun read(): FleetThemeState {
        val identity = resolveProvider()
        return FleetThemeClient.consume(identity?.first, identity?.second ?: emptyList()) {
            queryPayload()
        }
    }

    private fun resolveProvider(): Pair<String, List<String>>? {
        val info = try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.resolveContentProvider(
                    FleetThemeContract.AUTHORITY,
                    PackageManager.ComponentInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.resolveContentProvider(FleetThemeContract.AUTHORITY, 0)
            }
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val pkg = info.packageName?.takeIf { it.isNotBlank() } ?: return null
        return pkg to signingCerts(context.packageManager, pkg)
    }

    private fun queryPayload(): FleetThemePayload {
        val cursor = resolver.query(uri, null, null, null, null)
            ?: throw IllegalStateException("fleet theme provider unavailable")
        cursor.use { c ->
            if (!c.moveToFirst()) throw IllegalStateException("empty fleet theme")
            val columns = LinkedHashMap<String, String>()
            for (name in FleetThemeContract.PALETTE_COLUMNS) {
                colString(c, name)?.let { columns[name] = it }
            }
            return FleetThemePayload(
                schemaVersion = colInt(c, FleetThemeContract.COL_SCHEMA_VERSION),
                mode = colString(c, FleetThemeContract.COL_MODE),
                iconStyle = colString(c, FleetThemeContract.COL_ICON_STYLE),
                iconPackPackage = colString(c, FleetThemeContract.COL_ICON_PACK),
                json = colString(c, FleetThemeContract.COL_JSON),
                updatedAtMs = colLong(c, FleetThemeContract.COL_UPDATED_AT),
                columns = columns,
            )
        }
    }
}

class FleetThemeChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent?) {
        if (intent?.action != FleetThemeContract.ACTION_CHANGED) return
        val app = context.applicationContext
        val client = (app as? FleetThemeHost)?.fleetTheme ?: return
        val sender = resolveSender(context, this) ?: return
        client.onBroadcast(sender.first, sender.second)
    }
}

/** Application implements this so the exported receiver can refresh without a second lookup. */
interface FleetThemeHost {
    val fleetTheme: AndroidFleetTheme
}

internal fun resolveSender(context: Context, receiver: BroadcastReceiver): Pair<String, List<String>>? {
    if (Build.VERSION.SDK_INT < 34) return null
    val fromPackage = receiver.sentFromPackage?.takeIf { it.isNotBlank() }
    val fromUid = receiver.sentFromUid
    val pkg = fromPackage
        ?: (if (fromUid >= 0) context.packageManager.getPackagesForUid(fromUid)?.singleOrNull() else null)
    if (pkg.isNullOrBlank()) return null
    return pkg to signingCerts(context.packageManager, pkg)
}

internal fun signingCerts(packageManager: PackageManager, packageName: String): List<String> {
    val info = packageInfo(packageManager, packageName) ?: return emptyList()
    val signers = try {
        if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
    } catch (_: RuntimeException) {
        null
    }
    if (signers.isNullOrEmpty()) return emptyList()
    return signers.map { sig ->
        MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

private fun packageInfo(packageManager: PackageManager, packageName: String): PackageInfo? = try {
    if (Build.VERSION.SDK_INT >= 28) {
        packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
    }
} catch (_: PackageManager.NameNotFoundException) {
    null
} catch (_: RuntimeException) {
    null
}

private fun colString(cursor: android.database.Cursor, name: String): String? {
    val i = cursor.getColumnIndex(name)
    if (i < 0 || cursor.isNull(i)) return null
    return cursor.getString(i)
}

private fun colInt(cursor: android.database.Cursor, name: String): Int? {
    val i = cursor.getColumnIndex(name)
    if (i < 0 || cursor.isNull(i)) return null
    return cursor.getInt(i)
}

private fun colLong(cursor: android.database.Cursor, name: String): Long {
    val i = cursor.getColumnIndex(name)
    if (i < 0 || cursor.isNull(i)) return 0L
    return cursor.getLong(i)
}
