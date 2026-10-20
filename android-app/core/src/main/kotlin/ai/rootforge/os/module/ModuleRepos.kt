package ai.rootforge.os.module

enum class RepoSupport(val label: String) { MAGISK("Magisk"), KERNELSU("KernelSU"), APATCH("APatch"), LSPOSED("LSPosed") }

enum class RepoKind(val label: String) { REPOSITORY("Module repositories"), CLIENT("Apps that browse repositories") }

/**
 * One place to find root modules. RootForge only links to these: it does not fetch, index, vet or
 * install anything from them. [checked] says what was verified and when; blank means the entry comes
 * from a list (Awesome Android Root) and was not reachable from where it was written.
 */
data class ModuleRepo(
    val id: String,
    val name: String,
    val url: String,
    val description: String,
    val kind: RepoKind,
    val supports: Set<RepoSupport>,
    val checked: String = "",
    val note: String = "",
)

object ModuleRepos {
    private const val CHECKED = "2026-10-19"

    val all: List<ModuleRepo> = listOf(
        ModuleRepo(
            id = "magisk-modules-alt-repo",
            name = "Magisk-Modules-Alt-Repo",
            url = "https://github.com/Magisk-Modules-Alt-Repo",
            description = "GitHub organisation with one repository per module. Describes itself as an alternative to Magisk-Modules-Repo with fewer restrictions.",
            kind = RepoKind.REPOSITORY,
            supports = setOf(RepoSupport.MAGISK, RepoSupport.KERNELSU, RepoSupport.APATCH),
            checked = "$CHECKED: organisation page exists, 143 repositories listed.",
            note = "Modules are written for Magisk. KernelSU and APatch accept the same zip format, but a module can still need Magisk-only features.",
        ),
        ModuleRepo(
            id = "magisk-modules-repo",
            name = "Magisk-Modules-Repo",
            url = "https://github.com/Magisk-Modules-Repo",
            description = "The original GitHub organisation hosting Magisk modules.",
            kind = RepoKind.REPOSITORY,
            supports = setOf(RepoSupport.MAGISK),
            checked = "$CHECKED: organisation page exists. Its submission repository is marked archived.",
            note = "Older than the Alt-Repo. Check each module's last update before using it.",
        ),
        ModuleRepo(
            id = "kernelsu-modules",
            name = "KernelSU Module Repository",
            url = "https://modules.kernelsu.org/",
            description = "Website for KernelSU modules, called the official module repository in Awesome Android Root's KernelSU guide.",
            kind = RepoKind.REPOSITORY,
            supports = setOf(RepoSupport.KERNELSU),
            note = "Listed by Awesome Android Root; the site could not be reached when this entry was written.",
        ),
        ModuleRepo(
            id = "lsposed-modules",
            name = "LSPosed Modules Repository",
            url = "https://modules.lsposed.org/",
            description = "Website for LSPosed (Xposed) modules. These are APKs enabled in LSPosed, not zips installed through a root manager.",
            kind = RepoKind.REPOSITORY,
            supports = setOf(RepoSupport.LSPOSED),
            checked = "$CHECKED: linked as its site from the Xposed-Modules-Repo GitHub organisation; the site itself could not be reached.",
        ),
        ModuleRepo(
            id = "xposed-modules-repo",
            name = "Xposed-Modules-Repo",
            url = "https://github.com/Xposed-Modules-Repo",
            description = "GitHub organisation behind the LSPosed module site, one repository per module (APK releases).",
            kind = RepoKind.REPOSITORY,
            supports = setOf(RepoSupport.LSPOSED),
            checked = "$CHECKED: organisation page exists, about 1,300 repositories.",
        ),
        ModuleRepo(
            id = "mmrl",
            name = "MMRL",
            url = "https://github.com/MMRLApp/MMRL",
            description = "An app for managing, sharing and exploring modules across repositories. GPL-3.0. Formerly DerGoogler/MMRL.",
            kind = RepoKind.CLIENT,
            supports = setOf(RepoSupport.MAGISK, RepoSupport.KERNELSU, RepoSupport.APATCH),
            checked = "$CHECKED: repository exists at MMRLApp/MMRL (DerGoogler/MMRL redirects here); its page states compatibility with Magisk, KernelSU and APatch.",
            note = "Install it on the target phone yourself. RootForge does not drive it.",
        ),
    )
}
