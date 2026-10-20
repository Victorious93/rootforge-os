package ai.rootforge.os.bridge

/**
 * Actions `remote.command` / macro steps may name. Envelope ops live in
 * [FleetOp]; this table is the `actionRef` surface. Destructive writes that
 * exist on the GUI but must never auto-run from a remote trigger are listed
 * in [FORBIDDEN_DESTRUCTIVE] and return `confirmation_required`.
 */
object RemoteActionCatalog {

    val ENVELOPE_OPS: List<RemoteCapability> = listOf(
        RemoteCapability(FleetOp.CAPABILITIES, FleetKind.READ, remoteAllowed = true, requiresConfirm = false, description = "Protocol version, node id, ops and action refs."),
        RemoteCapability(FleetOp.REMOTE_COMMAND, FleetKind.ACTION, remoteAllowed = true, requiresConfirm = true, description = "Run one named action. Per-action confirm still applies."),
        RemoteCapability(FleetOp.MACRO_STEP, FleetKind.ACTION, remoteAllowed = true, requiresConfirm = true, description = "Run one step of a controller-owned macro."),
        RemoteCapability(FleetOp.MACRO_RUN, FleetKind.ACTION, remoteAllowed = true, requiresConfirm = true, description = "Run or dry-run a list of steps. Destructive steps never auto-approve."),
    )

    val ACTIONS: List<RemoteCapability> = listOf(
        RemoteCapability(BridgeOp.DEVICES_LIST, FleetKind.READ, true, false, "USB targets attached to this host."),
        RemoteCapability(BridgeOp.DEVICE_INFO, FleetKind.READ, true, false, "Properties of the connected target."),
        RemoteCapability(BridgeOp.ADB_SHELL, FleetKind.ACTION, true, true, "One shell command on the target over ADB."),
        RemoteCapability(BridgeOp.ADB_REBOOT, FleetKind.ACTION, true, true, "Reboot the target (system, bootloader, recovery, sideload, fastboot, edl)."),
        RemoteCapability(BridgeOp.FASTBOOT_REBOOT, FleetKind.ACTION, true, true, "Reboot a target that is in fastboot."),
        RemoteCapability(BridgeOp.MODULE_BUILD, FleetKind.ACTION, true, true, "Build a module zip into the library (no device write)."),
        RemoteCapability(BridgeOp.FASTBOOT_FLASH, FleetKind.DESTRUCTIVE, true, true, "Flash a library image. Typed confirmation on the device."),
        RemoteCapability(BridgeOp.FASTBOOT_SLOT, FleetKind.DESTRUCTIVE, true, true, "Switch the active A/B slot. Typed confirmation."),
        RemoteCapability(BridgeOp.FASTBOOT_UNLOCK, FleetKind.DESTRUCTIVE, true, true, "Unlock the bootloader. Typed confirmation."),
        RemoteCapability(BridgeOp.FASTBOOT_LOCK, FleetKind.DESTRUCTIVE, true, true, "Lock the bootloader. Typed confirmation."),
        RemoteCapability(ACTION_SHIZUKU_DEVICE, FleetKind.ACTION, true, true, "Read-only Shizuku device snapshot on the host phone."),
        RemoteCapability(ACTION_SHIZUKU_PM_GRANT, FleetKind.DESTRUCTIVE, true, true, "pm grant on the host phone. Typed GRANT PERMISSION. Never auto-approved."),
        RemoteCapability(ACTION_SHIZUKU_EXEC, FleetKind.ACTION, false, true, "Arbitrary privileged argv. Not remotely allowed."),
    )

    const val ACTION_SHIZUKU_DEVICE = "shizuku.readDeviceState"
    const val ACTION_SHIZUKU_PM_GRANT = "shizuku.pmGrant"
    const val ACTION_SHIZUKU_EXEC = "shizuku.exec"

    /** Known destructive names that are GUI-only and must not run from a remote trigger. */
    val FORBIDDEN_DESTRUCTIVE: Set<String> = setOf(
        "rootforge.edl.upload",
        "rootforge.edl.write",
        "rootforge.recovery.sideload",
        "rootforge.recovery.flash",
        "rootforge.recovery.install",
        "rootforge.recovery.custom",
        "rootforge.customrecovery.install",
        "rootforge.fastboot.oem",
        "rootforge.fastboot.erase",
        "rootforge.fastboot.boot",
        "rootforge.adb.sideload",
        "rootforge.adb.install",
        "rootforge.adb.push",
        "pm.grant",
    )

    fun envelope(name: String): RemoteCapability? = ENVELOPE_OPS.find { it.name == name }

    fun action(name: String): RemoteCapability? = ACTIONS.find { it.name == name }

    fun isForbiddenDestructive(name: String): Boolean = name in FORBIDDEN_DESTRUCTIVE
}
