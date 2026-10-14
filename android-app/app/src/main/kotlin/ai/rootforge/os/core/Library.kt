package ai.rootforge.os.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

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

    fun import(context: Context, uri: Uri): LibraryFile {
        val name = sanitize(displayName(context, uri) ?: "import-${System.currentTimeMillis()}")
        val target = fileFor(name)
        val tmp = File(dir, "$name.part")
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) throw IOException("Cannot open the selected file")
            tmp.outputStream().use { input.copyTo(it) }
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Could not store $name")
        }
        refresh()
        return LibraryFile(name, target)
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment

    companion object {
        fun sanitize(name: String): String =
            name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').take(96)
    }
}
