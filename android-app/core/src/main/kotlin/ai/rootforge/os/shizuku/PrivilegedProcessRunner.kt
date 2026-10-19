package ai.rootforge.os.shizuku

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * argv [ProcessBuilder] used by the Shizuku UserService (and JVM tests).
 * Never logs stdout/stderr. Caps each stream so a hostile command cannot
 * fill memory. Callers must already have been approval-gated.
 */
object PrivilegedProcessRunner {
    const val MAX_OUTPUT_BYTES = 64 * 1024

    fun run(argv: List<String>, timeoutMs: Long): PrivilegedResult {
        if (argv.isEmpty()) {
            return PrivilegedResult.Failure("argv must contain at least one element (the executable)")
        }
        if (argv.any { it.contains('\u0000') }) {
            return PrivilegedResult.Failure("argv must not contain NUL bytes")
        }
        val timeout = timeoutMs.coerceIn(1_000L, 120_000L)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val process = try {
            ProcessBuilder(argv).start()
        } catch (t: Throwable) {
            return PrivilegedResult.Failure("Failed to start '${argv.first()}'", t)
        }
        val outThread = drain(process.inputStream, stdout)
        val errThread = drain(process.errorStream, stderr)
        return try {
            val finished = process.waitFor(timeout, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
                join(outThread, errThread)
                PrivilegedResult.Success(
                    exitCode = process.exitValueOr(143),
                    stdout = text(stdout),
                    stderr = text(stderr),
                    timedOut = true,
                )
            } else {
                join(outThread, errThread)
                PrivilegedResult.Success(
                    exitCode = process.exitValue(),
                    stdout = text(stdout),
                    stderr = text(stderr),
                    timedOut = false,
                )
            }
        } catch (t: Throwable) {
            process.destroyForcibly()
            PrivilegedResult.Failure("Privileged process failed", t)
        }
    }

    fun readDeviceState(): PrivilegedResult {
        val chunks = DEVICE_STATE_COMMANDS.map { argv ->
            val label = argv.joinToString(" ")
            when (val result = run(argv, 10_000)) {
                is PrivilegedResult.Failure -> "$label\nERROR ${result.reason}\n"
                is PrivilegedResult.Success -> {
                    val body = result.stdout.ifBlank { result.stderr }.trim()
                    "$label\n$body\n"
                }
            }
        }
        return PrivilegedResult.Success(
            exitCode = 0,
            stdout = chunks.joinToString("\n").trim(),
            stderr = "",
            timedOut = false,
        )
    }

    fun pmGrant(packageName: String, permission: String): PrivilegedResult {
        if (!PrivilegedNames.isSafeAndroidName(packageName)) {
            return PrivilegedResult.Failure("Invalid package name")
        }
        if (!PrivilegedNames.isSafeAndroidName(permission)) {
            return PrivilegedResult.Failure("Invalid permission name")
        }
        return run(listOf("pm", "grant", packageName, permission), 15_000)
    }

    private val DEVICE_STATE_COMMANDS = listOf(
        listOf("id"),
        listOf("getprop", "ro.build.version.release"),
        listOf("getprop", "ro.product.model"),
        listOf("getprop", "ro.product.device"),
    )

    private fun drain(stream: InputStream, sink: ByteArrayOutputStream): Thread =
        Thread({
            val buf = ByteArray(4096)
            while (true) {
                val n = try {
                    stream.read(buf)
                } catch (_: Throwable) {
                    break
                }
                if (n <= 0) break
                synchronized(sink) {
                    val room = MAX_OUTPUT_BYTES - sink.size()
                    if (room > 0) sink.write(buf, 0, minOf(n, room))
                }
            }
        }, "shizuku-drain").apply {
            isDaemon = true
            start()
        }

    private fun join(vararg threads: Thread) {
        threads.forEach { it.join(2_000) }
    }

    private fun text(bytes: ByteArrayOutputStream): String =
        synchronized(bytes) { String(bytes.toByteArray(), Charsets.UTF_8) }

    private fun Process.exitValueOr(fallback: Int): Int = try {
        exitValue()
    } catch (_: IllegalThreadStateException) {
        fallback
    }
}
