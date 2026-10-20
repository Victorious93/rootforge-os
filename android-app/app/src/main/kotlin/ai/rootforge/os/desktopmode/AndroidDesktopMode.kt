package ai.rootforge.os.desktopmode

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * PackageManager-backed lookup. Every call is wrapped so a missing package or a
 * broken resolver cannot crash the UI.
 */
class AndroidCompanionLookup(private val packageManager: PackageManager) {
    fun installed(): List<CompanionPackage> = VictorSuite.CANDIDATE_PACKAGES.mapNotNull { pkg ->
        runCatching { inspect(pkg) }.getOrNull()
    }

    private fun inspect(pkg: String): CompanionPackage? {
        val info = packageInfo(pkg) ?: return null
        val cert = signingCertHashes(info) ?: ""
        return CompanionPackage(
            packageName = pkg,
            certSha256 = cert,
            exportsRunCommand = resolvesService(pkg, VictorSuite.RUN_COMMAND_ACTION, VictorSuite.RUN_COMMAND_SERVICE),
            exportsDesktopAction = resolvesService(pkg, VictorSuite.ACTION_DESKTOP_MODE, VictorSuite.SERVICE_DESKTOP_MODE),
        )
    }

    private fun packageInfo(pkg: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun resolvesService(pkg: String, action: String, className: String): Boolean {
        val intent = Intent(action).setClassName(pkg, className)
        return try {
            packageManager.resolveService(intent, 0) != null ||
                packageManager.queryIntentServices(Intent(action), 0).any { it.serviceInfo?.packageName == pkg }
        } catch (_: RuntimeException) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun signingCertHashes(info: PackageInfo): String? {
        val signers = try {
            if (Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.apkContentsSigners
            } else {
                info.signatures
            }
        } catch (_: RuntimeException) {
            null
        }
        if (signers.isNullOrEmpty()) return null
        return signers.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.sorted().joinToString(",")
    }
}

fun IntentSpec.toAndroidIntent(): Intent {
    val intent = Intent(action).setPackage(packageName)
    val component = className
    if (component != null) intent.setClassName(packageName, component)
    stringExtras.forEach { (k, v) -> intent.putExtra(k, v) }
    stringArrayExtras.forEach { (k, v) -> intent.putExtra(k, v.toTypedArray()) }
    booleanExtras.forEach { (k, v) -> intent.putExtra(k, v) }
    return intent
}
