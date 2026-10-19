package ai.rootforge.os.core

/**
 * The full terminal RootForge hands off to (the fleet's VictorSuite, a Termux build). RootForge does not
 * know its package name, so the owner sets it; nothing is guessed and no command or serial is passed.
 */
object TerminalApp {
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** Android application id syntax: two or more dot-separated segments, each starting with a letter. */
    fun isValidPackage(name: String): Boolean = name.length <= 255 && PACKAGE.matches(name)
}
