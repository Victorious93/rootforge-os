package ai.rootforge.os.recovery

/**
 * Builds the exact shell snippets used to inspect, back up and write a
 * dedicated recovery partition. Callers execute them through `su`; this
 * object never runs a process.
 *
 * Writes are limited to `recovery` / `recovery_a` / `recovery_b` under
 * [RecoveryLayoutDetector.ALLOWED_DIRS]. `boot` and `vendor_boot` cannot
 * be produced.
 */
object RecoveryCommands {
    const val IMAGE_REMOTE = "/data/local/tmp/rf-recovery.img"
    const val BACKUP_REMOTE = "/data/local/tmp/rf-recovery-backup.img"
    const val MAX_PARTITION_BYTES = 256L * 1024 * 1024
    val WRITE_PARTITIONS = setOf("recovery", "recovery_a", "recovery_b")

    fun shSingleQuote(value: String): String {
        require(value.none { it == '\n' || it == '\r' || it == '\u0000' }) { "refusing a path with a line break" }
        return "'" + value.replace("'", "'\\''") + "'"
    }

    fun suShell(inner: String): String = "su -c " + shSingleQuote(inner)

    fun suArgv(inner: String): List<String> = listOf("su", "-c", inner)

    fun requireWritePath(layout: RecoveryLayout): String {
        val path = layout.writePath ?: throw IllegalArgumentException("No dedicated recovery partition to write.")
        val dir = layout.byNameDir ?: throw IllegalArgumentException("Recovery by-name directory is unknown.")
        val part = layout.writePartition ?: throw IllegalArgumentException("Recovery partition name is unknown.")
        require(dir in RecoveryLayoutDetector.ALLOWED_DIRS) { "Refusing to write outside by-name: $dir" }
        require(part in WRITE_PARTITIONS) { "Refusing to write partition '$part'." }
        require(path == "$dir/$part") { "Write path '$path' does not match $dir/$part." }
        return path
    }

    fun listPartitions(): String = RecoveryLayoutDetector.LIST_SCRIPT

    fun rootId(): String = "id"

    fun partitionSize(path: String): String = "blockdev --getsize64 ${shSingleQuote(requirePath(path))}"

    fun statPartition(path: String): String = "ls -l ${shSingleQuote(requirePath(path))}"

    fun backup(layout: RecoveryLayout, destRemote: String = BACKUP_REMOTE): String {
        val src = requireWritePath(layout)
        return "dd if=${shSingleQuote(src)} of=${shSingleQuote(requireTmp(destRemote))} bs=4096 status=none"
    }

    fun write(layout: RecoveryLayout, imageRemote: String = IMAGE_REMOTE): String {
        val dest = requireWritePath(layout)
        return "dd if=${shSingleQuote(requireTmp(imageRemote))} of=${shSingleQuote(dest)} bs=4096 conv=fsync status=none"
    }

    fun chmodReadable(path: String): String = "chmod 644 ${shSingleQuote(requireTmp(path))}"

    fun removeRemote(path: String): String = "rm -f ${shSingleQuote(requireTmp(path))}"

    fun copyToTmp(localPath: String, destRemote: String = IMAGE_REMOTE): String =
        "cp ${shSingleQuote(requireReadableFile(localPath))} ${shSingleQuote(requireTmp(destRemote))}"

    fun rebootRecovery(): String = "reboot recovery"

    fun parseBlockdevSize(output: String): Long? =
        output.trim().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.toLongOrNull()

    private fun requirePath(path: String): String {
        require(path.startsWith("/dev/block/")) { "Not a block path: $path" }
        val dir = path.substringBeforeLast('/')
        val name = path.substringAfterLast('/')
        require(dir in RecoveryLayoutDetector.ALLOWED_DIRS) { "Refusing path outside by-name: $path" }
        require(name in WRITE_PARTITIONS) { "Refusing partition '$name'." }
        return path
    }

    private fun requireTmp(path: String): String {
        require(path == IMAGE_REMOTE || path == BACKUP_REMOTE) { "Refusing remote path $path" }
        return path
    }

    private fun requireReadableFile(path: String): String {
        require(path.startsWith("/")) { "Local path must be absolute" }
        require(path.none { it == '\n' || it == '\r' || it == '\u0000' }) { "refusing a path with a line break" }
        require(".." !in path.split('/')) { "Refusing a path with .." }
        return path
    }
}
