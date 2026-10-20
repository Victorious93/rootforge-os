package ai.rootforge.os.device

/**
 * Labels for the Compose-drawn step diagrams (Android app and :desktop).
 * No bitmaps and no runtime downloads — the UI draws circles and arrows.
 */
data class DiagramStep(
    val label: String,
    val caption: String,
) {
    init {
        require(label.isNotBlank() && caption.isNotBlank()) { label }
        require(!label.contains('\n') && !caption.contains('\n')) { label }
        require(label.length <= 18) { "$label: keep diagram labels short" }
    }
}

object WizardDiagrams {
    fun forGuide(kind: RootGuideKind): List<DiagramStep> = when (kind) {
        RootGuideKind.PIXEL -> listOf(
            DiagramStep("OEM unlock", "Developer options"),
            DiagramStep("Fastboot", "Volume down + Power"),
            DiagramStep("Type UNLOCK", "Wipes the phone"),
            DiagramStep("Recovery", "TWRP / PitchBlack"),
            DiagramStep("Root app", "Magisk / KSU / APatch"),
        )
        RootGuideKind.SAMSUNG -> listOf(
            DiagramStep("Read Knox", "Permanent flag"),
            DiagramStep("Download?", "Not fastboot"),
            DiagramStep("Odin on PC", "Link only here"),
            DiagramStep("Or Unlock", "If actually fastboot"),
        )
        RootGuideKind.XIAOMI -> listOf(
            DiagramStep("Mi account", "Developer options"),
            DiagramStep("Wait", "Days, not skippable"),
            DiagramStep("Type UNLOCK", "After Xiaomi says so"),
            DiagramStep("Recovery", "Then root app"),
        )
        RootGuideKind.MOTOROLA -> listOf(
            DiagramStep("get_unlock_data", "On Fastboot"),
            DiagramStep("Motorola site", "You paste it"),
            DiagramStep("Type UNLOCK", "Wipes the phone"),
            DiagramStep("Recovery", "Then root app"),
        )
        RootGuideKind.ONEPLUS, RootGuideKind.OPPO, RootGuideKind.REALME -> listOf(
            DiagramStep("OEM unlock", "Deep Testing if asked"),
            DiagramStep("Token page", "Browser only"),
            DiagramStep("Type UNLOCK", "Wipes the phone"),
            DiagramStep("Recovery", "Then root app"),
        )
        RootGuideKind.SONY, RootGuideKind.HTC, RootGuideKind.FAIRPHONE -> listOf(
            DiagramStep("Read IDs", "IMEI or token"),
            DiagramStep("Official page", "You paste it"),
            DiagramStep("Type UNLOCK", "Wipes the phone"),
            DiagramStep("Recovery", "Then root app"),
        )
        RootGuideKind.NOTHING, RootGuideKind.NOKIA, RootGuideKind.LENOVO, RootGuideKind.TRANSSION -> listOf(
            DiagramStep("OEM unlock", "Developer options"),
            DiagramStep("Fastboot", "Reboot yourself"),
            DiagramStep("Type UNLOCK", "Wipes the phone"),
            DiagramStep("Recovery", "Then root app"),
        )
        RootGuideKind.ASUS, RootGuideKind.LG, RootGuideKind.HUAWEI, RootGuideKind.VIVO -> listOf(
            DiagramStep("No portal", "Programme closed"),
            DiagramStep("Stop or read", "Do not invent a site"),
            DiagramStep("Official support", "Firmware links only"),
        )
        RootGuideKind.GENERIC -> genericUnlock()
    }

    fun forUnlock(offered: Boolean): List<DiagramStep> = if (offered) {
        listOf(
            DiagramStep("OEM unlocking", "On the phone"),
            DiagramStep("Read data", "fastboot / IMEI"),
            DiagramStep("Official site", "You paste it"),
            DiagramStep("Type UNLOCK", "Wipes the phone"),
        )
    } else {
        listOf(
            DiagramStep("No programme", "Maker stopped"),
            DiagramStep("No invented URL", "Support only"),
            DiagramStep("Do not send IDs", "Never auto-upload"),
        )
    }

    fun forMsm(): List<DiagramStep> = listOf(
        DiagramStep("See 9008", "Identify only"),
        DiagramStep("Read the guide", "MSM is a PC tool"),
        DiagramStep("Official page", "Maker firmware"),
        DiagramStep("No write here", "Nothing bundled"),
    )

    private fun genericUnlock() = listOf(
        DiagramStep("OEM unlock", "Developer options"),
        DiagramStep("Fastboot", "Reboot yourself"),
        DiagramStep("Type UNLOCK", "Wipes the phone"),
        DiagramStep("Recovery", "Then root app"),
    )
}
