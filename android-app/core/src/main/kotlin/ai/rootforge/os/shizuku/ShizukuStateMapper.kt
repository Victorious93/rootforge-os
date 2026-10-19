package ai.rootforge.os.shizuku

/**
 * Pure mapping from a [ShizukuProbe] to one of the four UI states.
 * Binder alive + Sui (no Shizuku package) still counts as available.
 */
object ShizukuStateMapper {
    fun map(probe: ShizukuProbe): ShizukuStatus {
        if (!probe.binderAlive) {
            return if (probe.providerPresent) ShizukuStatus.NotRunning else ShizukuStatus.NotInstalled
        }
        if (probe.preV11) return ShizukuStatus.NotRunning
        return if (probe.permissionGranted) ShizukuStatus.Granted else ShizukuStatus.PermissionDenied
    }
}
