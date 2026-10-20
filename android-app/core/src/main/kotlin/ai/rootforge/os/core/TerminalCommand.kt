package ai.rootforge.os.core

import ai.rootforge.os.fastboot.RebootTarget

/**
 * The line parser behind the secondary terminal panel. It deliberately does
 * not forward arbitrary text to fastboot: anything that writes, erases or
 * changes lock state is routed to the same confirmed code path as the GUI
 * buttons, or refused, so typing a command never bypasses a confirmation.
 */
sealed interface TerminalCommand {
    data class AdbShell(val command: String) : TerminalCommand
    data class AdbReboot(val target: String) : TerminalCommand
    data class FastbootGetvar(val name: String) : TerminalCommand
    data class FastbootReboot(val target: RebootTarget) : TerminalCommand
    data class FastbootErase(val partition: String) : TerminalCommand
    data class FastbootSlot(val slot: String) : TerminalCommand
    data object FastbootUnlock : TerminalCommand
    data object FastbootLock : TerminalCommand
    data object Devices : TerminalCommand
    data object Help : TerminalCommand
    data object Clear : TerminalCommand
    data class Refused(val reason: String) : TerminalCommand

    companion object {
        const val HELP = """Commands:
  adb shell <command>        run a command on the target
  adb reboot [bootloader|recovery|sideload|fastboot|edl]
  fastboot getvar <name|all>
  fastboot reboot [bootloader|recovery|fastboot]
  fastboot erase <partition>      (asks for confirmation)
  fastboot set_active <a|b>       (asks for confirmation)
  fastboot flashing unlock|lock   (asks for confirmation)
  devices, clear, help
Flashing images, sideload, push/pull and EDL are on Flash Tools or their own screens."""

        fun parse(line: String): TerminalCommand? {
            val text = line.trim()
            if (text.isEmpty()) return null
            val words = text.split(Regex("\\s+"))
            return when (words[0].lowercase()) {
                "help", "?" -> Help
                "clear", "cls" -> Clear
                "devices" -> Devices
                "adb" -> parseAdb(words.drop(1), text)
                "shell" -> parseAdb(words, text)
                "fastboot" -> parseFastboot(words.drop(1))
                else -> Refused("Unknown command '${words[0]}'. Type help.")
            }
        }

        private fun parseAdb(args: List<String>, original: String): TerminalCommand {
            return when (args.firstOrNull()?.lowercase()) {
                "shell" -> {
                    val cmd = original.substringAfter("shell", "").trim()
                    if (cmd.isEmpty()) Refused("adb shell needs a command; an interactive shell is not offered.") else AdbShell(cmd)
                }
                "reboot" -> {
                    val t = args.getOrNull(1)?.lowercase() ?: ""
                    if (t in setOf("", "bootloader", "recovery", "sideload", "fastboot", "edl")) AdbReboot(t)
                    else Refused("Unknown reboot target '$t'.")
                }
                "devices" -> Devices
                else -> Refused("Unsupported adb command. Type help.")
            }
        }

        private fun parseFastboot(args: List<String>): TerminalCommand {
            return when (args.firstOrNull()?.lowercase()) {
                "getvar" -> args.getOrNull(1)?.takeIf { Regex("[A-Za-z0-9_:.-]{1,48}").matches(it) }?.let { FastbootGetvar(it) }
                    ?: Refused("fastboot getvar needs a variable name.")
                "reboot" -> when (args.getOrNull(1)?.lowercase()) {
                    null, "system" -> FastbootReboot(RebootTarget.SYSTEM)
                    "bootloader" -> FastbootReboot(RebootTarget.BOOTLOADER)
                    "recovery" -> FastbootReboot(RebootTarget.RECOVERY)
                    "fastboot", "fastbootd" -> FastbootReboot(RebootTarget.FASTBOOTD)
                    else -> Refused("Unknown reboot target.")
                }
                "reboot-bootloader" -> FastbootReboot(RebootTarget.BOOTLOADER)
                "erase" -> args.getOrNull(1)?.let { FastbootErase(it) } ?: Refused("fastboot erase needs a partition.")
                "set_active" -> args.getOrNull(1)?.takeIf { it == "a" || it == "b" }?.let { FastbootSlot(it) }
                    ?: Refused("fastboot set_active needs a or b.")
                "flashing" -> when (args.getOrNull(1)?.lowercase()) {
                    "unlock" -> FastbootUnlock
                    "lock" -> FastbootLock
                    else -> Refused("Only 'flashing unlock' and 'flashing lock' are supported here.")
                }
                "flash", "boot", "update", "flashall" -> Refused("Use Flash Tools or the Fastboot / Recovery screen: it picks the image, checks the sidecar claim, and shows the plan before writing.")
                "oem" -> Refused("Raw OEM commands are not forwarded; they can be destructive and are vendor-specific.")
                else -> Refused("Unsupported fastboot command. Type help.")
            }
        }
    }
}
