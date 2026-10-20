package ai.rootforge.os.links

import ai.rootforge.os.device.BoardHelpers
import ai.rootforge.os.device.UnlockPortals

enum class LinkSection(val label: String) {
    ROOT_MANAGERS("Root managers"),
    MODULE_SOURCES("Module repositories and sources"),
    SHIZUKU_APPS("Shizuku apps"),
    BOOTLOADER_UNLOCK_PORTALS("Bootloader unlock portals"),
    ESP32_PI_HELPERS("ESP32 & Pi helpers"),
}

data class NamedUrl(val label: String, val url: String)

/**
 * One browser destination in the in-app Links directory. RootForge never
 * downloads or bundles these; callers open https in the system browser.
 * [url] is null when the brand has no live official portal (closed or
 * never published a token page).
 */
data class DirectoryLink(
    val id: String,
    val name: String,
    val url: String?,
    val section: LinkSection,
    val description: String,
    val extras: List<NamedUrl> = emptyList(),
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && description.isNotBlank()) { id }
        if (url != null) {
            require(url.startsWith("https://")) { "$id: directory urls must be https" }
            require(!url.contains(' ')) { "$id: directory url has whitespace" }
        }
        for (extra in extras) {
            require(extra.url.startsWith("https://")) { "$id extra must be https" }
        }
    }
}

/**
 * Curated https-only directory of official project pages. Checked 2026-10-20
 * against GitHub (repo or organisation exists, not archived unless noted) and
 * live Play / F-Droid listing pages. Sites that were not reachable are omitted.
 */
object DirectoryLinks {
    private val PROJECT_LINKS: List<DirectoryLink> = listOf(
        DirectoryLink(
            id = "magisk",
            name = "Magisk",
            url = "https://github.com/topjohnwu/Magisk",
            section = LinkSection.ROOT_MANAGERS,
            description = "Official Magisk source and releases (topjohnwu).",
        ),
        DirectoryLink(
            id = "kernelsu",
            name = "KernelSU",
            url = "https://github.com/tiann/KernelSU",
            section = LinkSection.ROOT_MANAGERS,
            description = "Official KernelSU source and releases (tiann).",
        ),
        DirectoryLink(
            id = "kernelsu-next",
            name = "KernelSU Next",
            url = "https://github.com/KernelSU-Next/KernelSU-Next",
            section = LinkSection.ROOT_MANAGERS,
            description = "KernelSU Next, a separate KernelSU-family project with its own manager.",
        ),
        DirectoryLink(
            id = "apatch",
            name = "APatch",
            url = "https://github.com/bmax121/APatch",
            section = LinkSection.ROOT_MANAGERS,
            description = "Official APatch source and releases (bmax121).",
        ),
        DirectoryLink(
            id = "magisk-modules-alt-repo",
            name = "Magisk-Modules-Alt-Repo",
            url = "https://github.com/Magisk-Modules-Alt-Repo",
            section = LinkSection.MODULE_SOURCES,
            description = "GitHub organisation with one repository per Magisk-format module.",
        ),
        DirectoryLink(
            id = "magisk-modules-repo",
            name = "Magisk-Modules-Repo",
            url = "https://github.com/Magisk-Modules-Repo",
            section = LinkSection.MODULE_SOURCES,
            description = "The original Magisk modules GitHub organisation. Check last-update dates; its submission repo is archived.",
        ),
        DirectoryLink(
            id = "mmrl",
            name = "MMRL",
            url = "https://github.com/MMRLApp/MMRL",
            section = LinkSection.MODULE_SOURCES,
            description = "App for browsing and installing modules from repositories (Magisk, KernelSU, APatch). GPL-3.0. Formerly DerGoogler/MMRL.",
        ),
        DirectoryLink(
            id = "xposed-modules-repo",
            name = "Xposed-Modules-Repo",
            url = "https://github.com/Xposed-Modules-Repo",
            section = LinkSection.MODULE_SOURCES,
            description = "GitHub organisation of LSPosed / Xposed modules (APK releases, not Magisk zips).",
        ),
        DirectoryLink(
            id = "lsposed",
            name = "LSPosed",
            url = "https://github.com/LSPosed/LSPosed",
            section = LinkSection.MODULE_SOURCES,
            description = "Original LSPosed framework repository (LSPosed organisation).",
        ),
        DirectoryLink(
            id = "vector",
            name = "Vector (LSPosed successor)",
            url = "https://github.com/JingMatrix/Vector",
            section = LinkSection.MODULE_SOURCES,
            description = "JingMatrix's maintained LSPosed successor. github.com/JingMatrix/LSPosed redirects here.",
        ),
        DirectoryLink(
            id = "zygisk-next",
            name = "Zygisk Next",
            url = "https://github.com/Dr-TSNG/ZygiskNext",
            section = LinkSection.MODULE_SOURCES,
            description = "Standalone Zygisk for Magisk, KernelSU and APatch (Dr-TSNG). Releases after v4-0.9.1 are not GPL.",
        ),
        DirectoryLink(
            id = "rezygisk",
            name = "ReZygisk",
            url = "https://github.com/PerformanC/ReZygisk",
            section = LinkSection.MODULE_SOURCES,
            description = "FOSS Zygisk Next fork (PerformanC). WIP; not an official successor of Zygisk Next.",
        ),
        DirectoryLink(
            id = "shizuku",
            name = "Shizuku",
            url = "https://github.com/RikkaApps/Shizuku",
            section = LinkSection.SHIZUKU_APPS,
            description = "Official Shizuku (RikkaApps): ADB/root APIs for normal apps. Not on F-Droid.",
            extras = listOf(
                NamedUrl("Play Store", "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"),
            ),
        ),
        DirectoryLink(
            id = "sui",
            name = "Sui",
            url = "https://github.com/RikkaApps/Sui",
            section = LinkSection.SHIZUKU_APPS,
            description = "RikkaApps Magisk module that provides the Shizuku API on a rooted phone.",
        ),
        DirectoryLink(
            id = "app-ops",
            name = "App Ops",
            url = "https://play.google.com/store/apps/details?id=rikka.appops",
            section = LinkSection.SHIZUKU_APPS,
            description = "Rikka's permission manager (Play Store). Source is not public; GitHub is the issue tracker.",
            extras = listOf(
                NamedUrl("Issue tracker", "https://github.com/RikkaApps/App-Ops-issue-tracker"),
            ),
        ),
        DirectoryLink(
            id = "ice-box",
            name = "Ice Box",
            url = "https://play.google.com/store/apps/details?id=com.catchingnow.icebox",
            section = LinkSection.SHIZUKU_APPS,
            description = "CatchingNow's app freezer. Official listing is Google Play (no public GitHub).",
        ),
        DirectoryLink(
            id = "hail",
            name = "Hail",
            url = "https://github.com/aistra0528/Hail",
            section = LinkSection.SHIZUKU_APPS,
            description = "FOSS app freezer. Works with Shizuku, Sui, root or Device Owner.",
            extras = listOf(
                NamedUrl("F-Droid", "https://f-droid.org/packages/com.aistra.hail/"),
            ),
        ),
        DirectoryLink(
            id = "systemui-tuner",
            name = "SystemUI Tuner",
            url = "https://github.com/zacharee/Tweaker",
            section = LinkSection.SHIZUKU_APPS,
            description = "Zacharee's SystemUI Tuner (repo name Tweaker). Hidden settings; Shizuku or ADB grant.",
            extras = listOf(
                NamedUrl("Play Store", "https://play.google.com/store/apps/details?id=com.zacharee1.systemuituner"),
            ),
        ),
        DirectoryLink(
            id = "canta",
            name = "Canta",
            url = "https://github.com/samolego/Canta",
            section = LinkSection.SHIZUKU_APPS,
            description = "Debloat / uninstall apps via Shizuku, no root (samolego).",
            extras = listOf(
                NamedUrl("Play Store", "https://play.google.com/store/apps/details?id=io.github.samolego.canta"),
                NamedUrl("F-Droid", "https://f-droid.org/packages/io.github.samolego.canta/"),
            ),
        ),
        DirectoryLink(
            id = "inure",
            name = "Inure",
            url = "https://github.com/Hamza417/Inure",
            section = LinkSection.SHIZUKU_APPS,
            description = "App manager with root and Shizuku support (Hamza417).",
            extras = listOf(
                NamedUrl("F-Droid", "https://f-droid.org/packages/app.simple.inure/"),
            ),
        ),
        DirectoryLink(
            id = "ashell",
            name = "aShell",
            url = "https://github.com/sunilpaulmathew/aShell",
            section = LinkSection.SHIZUKU_APPS,
            description = "Local ADB shell for Shizuku (sunilpaulmathew). GitHub is a mirror of the GitLab project.",
        ),
        DirectoryLink(
            id = "ashell-you",
            name = "aShell You",
            url = "https://github.com/DP-Hridayan/aShellYou",
            section = LinkSection.SHIZUKU_APPS,
            description = "Material You ADB / root / Shizuku shell (DP-Hridayan). Separate from aShell.",
            extras = listOf(
                NamedUrl("F-Droid", "https://f-droid.org/packages/in.hridayan.ashell/"),
            ),
        ),
        DirectoryLink(
            id = "dhizuku",
            name = "Dhizuku",
            url = "https://github.com/iamr0s/Dhizuku",
            section = LinkSection.SHIZUKU_APPS,
            description = "Shares Device Owner APIs with other apps, Shizuku-style (iamr0s).",
            extras = listOf(
                NamedUrl("F-Droid", "https://f-droid.org/packages/com.rosan.dhizuku/"),
            ),
        ),
    )

    private val PORTAL_LINKS: List<DirectoryLink> = UnlockPortals.all.map { portal ->
        DirectoryLink(
            id = "unlock-${portal.id}",
            name = portal.name,
            url = portal.url,
            section = LinkSection.BOOTLOADER_UNLOCK_PORTALS,
            description = portal.note,
        )
    }

    private val HELPER_LINKS: List<DirectoryLink> = BoardHelpers.all.map { helper ->
        DirectoryLink(
            id = "helper-${helper.id}",
            name = helper.name,
            url = helper.page.url,
            section = LinkSection.ESP32_PI_HELPERS,
            description = helper.summary + " " + helper.inScope,
            extras = helper.extras.map { extra -> NamedUrl(extra.label, extra.url) },
        )
    }

    val all: List<DirectoryLink> = PROJECT_LINKS + PORTAL_LINKS + HELPER_LINKS

    /** Every URL this directory will offer to the browser, including extras. Null portal urls are omitted. */
    fun urls(): List<String> = all.flatMap { listOfNotNull(it.url) + it.extras.map { extra -> extra.url } }
}
