package ai.rootforge.os.core

/**
 * The full terminal RootForge hands off to. The default is Termux's own package, chosen by the owner;
 * if VictorSuite ships under another package the owner changes it in Settings. Launch only: no
 * command, serial or file is passed.
 */
object TerminalApp {
    const val DEFAULT_PACKAGE = "com.termux"

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** Android application id syntax: two or more dot-separated segments, each starting with a letter. */
    fun isValidPackage(name: String): Boolean = name.length <= 255 && PACKAGE.matches(name)
}
