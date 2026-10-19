package ai.rootforge.os.shizuku

/**
 * Always-present [PrivilegedShell]. [isAvailable] is truthfully false;
 * every op fails explicitly rather than fabricating a privileged result.
 * Used by desktop and by the app when Shizuku is missing, not running, or
 * permission was denied — the rest of the app keeps working.
 */
class NullPrivilegedShell : PrivilegedShell {
    override fun isAvailable(): Boolean = false

    override fun exec(argv: List<String>, timeoutMs: Long) =
        unavailable(argv.firstOrNull() ?: "(empty)")

    override fun readDeviceState() =
        PrivilegedResult.Failure("${ShizukuCopy.UNAVAILABLE_FAILURE} (NullPrivilegedShell)")

    override fun pmGrant(packageName: String, permission: String) =
        unavailable("pm grant")

    private fun unavailable(what: String) = PrivilegedResult.Failure(
        "Cannot run '$what' via Shizuku: ${ShizukuCopy.UNAVAILABLE_FAILURE} (NullPrivilegedShell)",
    )
}
