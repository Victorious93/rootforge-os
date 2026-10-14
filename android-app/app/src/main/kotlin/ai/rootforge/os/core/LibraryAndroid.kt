package ai.rootforge.os.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

fun Library.import(context: Context, uri: Uri): LibraryFile {
    val name = Library.sanitize(displayName(context, uri) ?: "import-${System.currentTimeMillis()}")
    context.contentResolver.openInputStream(uri).use { input ->
        if (input == null) throw IOException("Cannot open the selected file")
        return importStream(name, input)
    }
}

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: uri.lastPathSegment
