package ai.rootforge.os.device

import ai.rootforge.os.usb.TargetMode

/**
 * Plain-language MSM Download Tool / EDL restore guide. Detect + explain +
 * link official https pages only.
 *
 * RootForge never ships the MSM Download Tool, QFIL, Rescue and Smart
 * Assistant, Mi Flash, a Firehose programmer, or any firmware image from
 * this table. It never writes in EDL from this guide.
 *
 * REFUSED (ethics): EDL as an FRP / screen-lock wipe, Qualcomm "auth"
 * / signature bypass, leaked MSM packages, unofficial firmware dump
 * sites. Those are out of scope even if a community page names them.
 */
data class MsmGuideStep(
    val id: String,
    val title: String,
    val detail: String,
) {
    init {
        require(id.isNotBlank() && title.isNotBlank()) { id }
        require(detail.length > 40) { "$id: explain the step" }
        require(!title.contains('\n') && !detail.contains('\n')) { "$id: keep MSM text one line" }
    }
}

data class MsmGuide(
    val title: String,
    val summary: String,
    val detected: String,
    val steps: List<MsmGuideStep>,
    val sources: List<OfficialHttps>,
    val usesMsm: Boolean,
    val writes: Boolean = false,
    val shipsTool: Boolean = false,
    val shipsFirmware: Boolean = false,
) {
    init {
        require(title.isNotBlank() && summary.isNotBlank() && detected.isNotBlank()) { title }
        require(steps.size >= 4) { "$title: need the detect/explain/link/stop steps" }
        require(!writes && !shipsTool && !shipsFirmware) { "$title: MSM guide never writes or ships files" }
        require(!summary.contains('\n') && !detected.contains('\n')) { "$title: keep MSM copy one line" }
    }
}

object MsmGuides {
    val REFUSED: List<String> = listOf(
        "Factory Reset Protection (FRP) / Google account lock wipe via EDL",
        "Screen-lock bypass by flashing through 9008",
        "Qualcomm Firehose auth / signature bypass, \"unlocked\" programmers, or leaked MSM packages",
        "Shipping or bundling MSM Download Tool, QFIL, RSA, Mi Flash, or any firmware image",
        "Linking unofficial dump sites (NeedROM, SamFw, random AndroidFileHost mirrors, 4PDA firmware packs)",
    )

    val QUALCOMM_SUPPORT = OfficialHttps(
        "Qualcomm Support",
        "https://www.qualcomm.com/support",
        listOf("qualcomm.com"),
        "Qualcomm's public support page (HTTP 200 on 2026-10-20). Qualcomm does not publish MSM Download Tool for consumers.",
    )

    val AOSP_LOCKING = OfficialHttps(
        "AOSP bootloader locking",
        "https://source.android.com/docs/core/architecture/bootloader/locking_unlocking",
        listOf("android.com"),
        "Android Open Source Project locking/unlocking notes (HTTP 200 on 2026-10-20). Not a firmware download.",
    )

    fun forIdentity(identity: DeviceIdentity?): MsmGuide {
        val brand = BrandCatalog.forIdentity(identity)
        val sources = sourcesFor(brand)
        val detected = when {
            identity == null -> "No phone identified yet. Qualcomm EDL shows up as USB 05c6:9008."
            identity.mode != TargetMode.EDL ->
                "This guide is for Qualcomm 9008. Current USB mode is ${identity.mode.label}."
            brand == null || brand.id == "google" ->
                "Qualcomm 9008 (EDL). The chip did not name a phone maker, so the links below are official OEM support pages — pick the brand of the phone in your hand."
            else ->
                "${brand.displayName} in Qualcomm EDL (05c6:9008). ${brand.note}"
        }
        val usesMsm = brand?.usesMsm == true
        val toolName = when {
            usesMsm -> "MSM Download Tool"
            brand?.id == "motorola" -> "Motorola Rescue and Smart Assistant"
            brand?.id == "xiaomi" -> "Xiaomi's own EDL / Mi Flash path"
            brand?.id == "samsung" -> "Samsung Download Mode / Odin (not EDL)"
            else -> "the maker's official unbrick tool"
        }
        return MsmGuide(
            title = "MSM / EDL guide",
            summary = "EDL is Qualcomm's 9008 emergency port. MSM Download Tool is an OEM Windows program that restores factory firmware over that port. RootForge only identifies the chip and opens official https pages.",
            detected = detected,
            usesMsm = usesMsm,
            steps = listOf(
                MsmGuideStep(
                    "detect",
                    "Recognise 9008",
                    "A phone in EDL enumerates as Qualcomm USB 05c6:9008. RootForge reads Sahara identity only. It does not upload a programmer and does not write sectors from this guide.",
                ),
                MsmGuideStep(
                    "msm",
                    "What MSM Download Tool is",
                    "MSM Download Tool is a Windows program some makers (especially OnePlus, Oppo and realme) used to restore a Qualcomm phone that will not boot. It is not open source, not redistributable here, and it is not the same as fastboot. RootForge does not run it.",
                ),
                MsmGuideStep(
                    "restore",
                    "What an EDL restore does",
                    "An ongoing EDL restore is the OEM tool talking to 9008 until the factory package is written and the phone can boot again. That write happens on the maker's PC program, never inside this app. Dry-run / typed confirm on other screens do not apply here because this guide does not write.",
                ),
                MsmGuideStep(
                    "official",
                    "Use only the maker's official page",
                    "Open $toolName or firmware support from the official https link for this brand. Copy nothing from unofficial dump sites. RootForge never pastes serial, IMEI or Sahara ids into a site.",
                ),
                MsmGuideStep(
                    "stop",
                    "This app never writes in EDL from the guide",
                    "No MSM binary, no firmware zip, no Firehose programmer is bundled. If the experimental EDL screen still offers Sahara/Firehose, that is a separate typed-EDL path and still ships no OEM package. Leave EDL (battery pull / wait / maker tool reset) before a root plan.",
                ),
            ),
            sources = sources,
        )
    }

    fun urls(): List<String> = (
        BrandCatalog.msmBrands().flatMap { listOfNotNull(it.firmware?.url, it.tool?.url) } +
            BrandCatalog.all.mapNotNull { it.firmware?.url } +
            listOf(QUALCOMM_SUPPORT.url, AOSP_LOCKING.url)
        ).distinct()

    private fun sourcesFor(brand: BrandRecord?): List<OfficialHttps> {
        if (brand != null) {
            return listOfNotNull(brand.firmware, brand.tool).distinctBy { it.url } +
                listOf(QUALCOMM_SUPPORT)
        }
        val msm = BrandCatalog.msmBrands().mapNotNull { it.firmware }
        val motorola = BrandCatalog.MOTOROLA.firmware
        val xiaomi = BrandCatalog.XIAOMI.firmware
        return (msm + listOfNotNull(motorola, xiaomi) + QUALCOMM_SUPPORT + AOSP_LOCKING).distinctBy { it.url }
    }
}
