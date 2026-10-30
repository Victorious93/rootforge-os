package ai.rootforge.os.device

import ai.rootforge.os.catalog.LoadedCatalog
import ai.rootforge.os.usb.AttachedTarget
import ai.rootforge.os.usb.TargetMode

data class DeviceCardModel(
    val target: AttachedTarget,
    val identity: DeviceIdentity?,
    val busy: String?,
    val plan: RootPlan,
) {
    val key: String get() = target.rosterKey()
}

object DeviceRoster {
    /** Published when Android USB access has not been granted yet. */
    const val NOTE_USB_PERMISSION = "USB permission needed."

    /** Published when host `adb` reports unauthorized / offline / no permissions. */
    const val NOTE_HOST_UNAUTHORIZED = "unauthorized or offline"

    fun cards(
        targets: List<AttachedTarget>,
        identities: Map<String, DeviceIdentity>,
        busy: Map<String, String>,
        catalog: LoadedCatalog?,
    ): List<DeviceCardModel> = targets.map { target ->
        val key = target.rosterKey()
        val identity = identities[key]
        DeviceCardModel(
            target = target,
            identity = identity,
            busy = busy[SerialOpLock.key(key)] ?: busy[key],
            plan = RootPlanner.plan(identity, catalog),
        )
    }

    fun prune(identities: Map<String, DeviceIdentity>, targets: List<AttachedTarget>): Map<String, DeviceIdentity> {
        val keys = targets.map { it.rosterKey() }.toSet()
        return identities.filterKeys { it in keys }
    }

    fun needsIdentify(target: AttachedTarget, identities: Map<String, DeviceIdentity>): Boolean {
        if (target.mode == TargetMode.UNKNOWN) return false
        val current = identities[target.rosterKey()] ?: return true
        if (current.mode != target.mode) return true
        // A permission / unauthorised stub must be replaced once the phone is usable.
        // A failed identify (any other note) is not retried every poll.
        return target.hasPermission && isAccessStub(current)
    }

    fun isAccessStub(identity: DeviceIdentity): Boolean =
        identity.note == NOTE_USB_PERMISSION || identity.note == NOTE_HOST_UNAUTHORIZED
}
