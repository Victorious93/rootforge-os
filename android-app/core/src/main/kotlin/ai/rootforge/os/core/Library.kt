package ai.rootforge.os.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LibraryFile(val name: String, val file: File) {
    val size: Long get() = file.length()
}

/**
 * Images, APKs and zips the user imported. Imported once, persisted in
 * app-private storage, and referred to by name, so a paired controller can
 * say "flash twrp.img" without ever being able to name an arbitrary path.
 */
class Library(private val dir: File) {
    private val state = MutableStateFlow<List<LibraryFile>>(emptyList())
    val files: StateFlow<List<LibraryFile>> = state.asStateFlow()

    init {
        dir.mkdirs()
        refresh()
    }

    fun list(): List<LibraryFile> =
        dir.listFiles { f -> f.isFile && !f.name.endsWith(".part") }.orEmpty().sortedBy { it.name.lowercase() }.map { LibraryFile(it.name, it) }

    /** Re-reads the directory; call after writing a file into it. */
    fun refresh() {
        state.value = list()
    }

    fun get(name: String): LibraryFile? {
        if (sanitize(name) != name) return null
        val f = File(dir, name)
        return if (f.isFile && f.parentFile?.canonicalPath == dir.canonicalPath) LibraryFile(name, f) else null
    }

    fun delete(name: String): Boolean = (get(name)?.file?.delete() ?: false).also { refresh() }

    fun fileFor(name: String): File {
        val clean = sanitize(name)
        require(clean.isNotEmpty()) { "empty file name" }
        return File(dir, clean)
    }

    fun importStream(suggestedName: String, input: InputStream): LibraryFile {
        val name = sanitize(suggestedName.ifBlank { "import-${System.currentTimeMillis()}" })
        val target = fileFor(name)
        val tmp = File(dir, "$name.part")
        try {
            tmp.outputStream().use { input.copyTo(it) }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("Could not store $name")
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        refresh()
        return LibraryFile(name, target)
    }

    fun importFile(source: File): LibraryFile =
        source.inputStream().use { importStream(source.name, it) }

    companion object {
        fun sanitize(name: String): String =
            name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').take(96)
    }
}
