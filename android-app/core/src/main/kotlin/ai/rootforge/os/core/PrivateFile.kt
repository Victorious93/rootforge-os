package ai.rootforge.os.core

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Create or tighten a file to owner-read/write. Desktop loopback is shared
 * by every local account; pairing lists and command logs must not inherit
 * umask `0644`.
 */
object PrivateFile {
    private const val OWNER_RW = "rw-------"

    fun restrictToOwner(target: File) {
        if (!target.exists()) return
        if (supportsPosix(target)) {
            Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString(OWNER_RW))
            return
        }
        target.setReadable(false, false)
        target.setWritable(false, false)
        target.setExecutable(false, false)
        target.setReadable(true, true)
        target.setWritable(true, true)
    }

    fun prepare(target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) {
            restrictToOwner(target)
            return
        }
        if (supportsPosix(target)) {
            try {
                Files.createFile(
                    target.toPath(),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_RW)),
                )
            } catch (_: FileAlreadyExistsException) {
                restrictToOwner(target)
            }
        } else if (target.createNewFile() || target.isFile) {
            restrictToOwner(target)
        }
    }

    fun supportsPosix(target: File): Boolean =
        target.toPath().fileSystem.supportedFileAttributeViews().contains("posix")
}
