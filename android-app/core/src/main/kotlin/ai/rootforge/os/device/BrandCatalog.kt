package ai.rootforge.os.device

/**
 * Device-identity table: brand aliases, which root wizard to show, which
 * unlock portal applies, and official https firmware / unbrick pages.
 *
 * Matching is whole-word tokens from [DeviceIdentity.brandTokens] so "lg"
 * does not match "google". Official pages were fetched 2026-10-20 (https,
 * vendor domain). Unofficial dump sites are refused.
 */
data class BrandRecord(
    val id: String,
    val displayName: String,
    val aliases: List<String>,
    val modelTokens: List<String> = emptyList(),
    val guideKind: RootGuideKind,
    val portalId: String,
    val firmware: OfficialHttps?,
    val tool: OfficialHttps?,
    val usesMsm: Boolean,
    val unlockOffered: Boolean,
    val note: String,
) {
    init {
        require(id.isNotBlank() && displayName.isNotBlank() && note.isNotBlank()) { id }
        require(aliases.isNotEmpty()) { "$id: need aliases" }
        require(portalId.isNotBlank()) { "$id: portalId" }
        require(!note.contains('\n')) { "$id: keep notes one line" }
        val aliasSet = (aliases + modelTokens).map { it.lowercase() }.toSet()
        require(aliasSet.size == aliases.size + modelTokens.size) { "$id: duplicate alias" }
    }

    fun tokens(): Set<String> = (aliases + modelTokens).map { it.lowercase() }.toSet()
}

object BrandCatalog {
    val GOOGLE = BrandRecord(
        id = "google",
        displayName = "Google Pixel",
        aliases = listOf("google"),
        modelTokens = listOf("pixel"),
        guideKind = RootGuideKind.PIXEL,
        portalId = "google",
        firmware = OfficialHttps(
            "Google factory images",
            "https://developers.google.com/android/images",
            listOf("google.com"),
            "Official Pixel factory images (developers.google.com, HTTP 200 on 2026-10-20). RootForge does not download or flash them from this table.",
        ),
        tool = null,
        usesMsm = false,
        unlockOffered = true,
        note = "Pixel unlocks with OEM unlocking then fastboot. No maker token. Tensor is not Qualcomm 9008.",
    )

    val SAMSUNG = BrandRecord(
        id = "samsung",
        displayName = "Samsung",
        aliases = listOf("samsung"),
        modelTokens = listOf("galaxy"),
        guideKind = RootGuideKind.SAMSUNG,
        portalId = "samsung",
        firmware = OfficialHttps(
            "Samsung Support",
            "https://www.samsung.com/us/support/",
            listOf("samsung.com"),
            "Official Samsung Support (HTTP 200 on 2026-10-20). RootForge does not speak Odin and does not fetch firmware packages.",
        ),
        tool = null,
        usesMsm = false,
        unlockOffered = false,
        note = "Samsung does not publish an unlock-token site. Many models use Download Mode / Odin, not Qualcomm MSM.",
    )

    val XIAOMI = BrandRecord(
        id = "xiaomi",
        displayName = "Xiaomi / Redmi / POCO",
        aliases = listOf("xiaomi", "redmi", "poco", "blackshark"),
        guideKind = RootGuideKind.XIAOMI,
        portalId = "xiaomi",
        firmware = OfficialHttps(
            "Xiaomi Support",
            "https://www.mi.com/global/support/",
            listOf("mi.com"),
            "Official Xiaomi Support (mi.com, HTTP 200 on 2026-10-20). Mi Flash, when Xiaomi still offers it, lives on Xiaomi's site — RootForge does not ship it.",
        ),
        tool = null,
        usesMsm = false,
        unlockOffered = true,
        note = "Mi Unlock wait is on Xiaomi's side. Qualcomm Xiaomi phones may use EDL with Xiaomi's own PC tool, not MSM Download Tool.",
    )

    val ONEPLUS = BrandRecord(
        id = "oneplus",
        displayName = "OnePlus",
        aliases = listOf("oneplus"),
        guideKind = RootGuideKind.ONEPLUS,
        portalId = "oneplus",
        firmware = OfficialHttps(
            "OnePlus Support",
            "https://www.oneplus.com/support",
            listOf("oneplus.com"),
            "Official OnePlus Support (redirects to service.oneplus.com, HTTP 200 on 2026-10-20). MSM Download Tool, when OnePlus still issues it, comes from OnePlus — never from this app.",
        ),
        tool = OfficialHttps(
            "OnePlus Support (MSM is not bundled)",
            "https://www.oneplus.com/support",
            listOf("oneplus.com"),
            "Ask OnePlus Support for the MSM Download Tool / EDL restore package for this exact model. RootForge never ships that Windows program or any firmware.",
        ),
        usesMsm = true,
        unlockOffered = true,
        note = "Some ColorOS OnePlus builds use Deep Testing instead of the unlock-token page.",
    )

    val OPPO = BrandRecord(
        id = "oppo",
        displayName = "Oppo",
        aliases = listOf("oppo"),
        guideKind = RootGuideKind.OPPO,
        portalId = "oppo",
        firmware = OfficialHttps(
            "Oppo Support",
            "https://support.oppo.com/en/",
            listOf("oppo.com"),
            "Official Oppo Support (HTTP 200 on 2026-10-20). ColorOS Deep Testing is in-phone; MSM Download Tool is an Oppo PC program this app does not ship.",
        ),
        tool = OfficialHttps(
            "Oppo Support (MSM is not bundled)",
            "https://support.oppo.com/en/",
            listOf("oppo.com"),
            "Oppo's MSM Download Tool, when offered, is obtained from Oppo Support for that model. RootForge never ships it.",
        ),
        usesMsm = true,
        unlockOffered = true,
        note = "Oppo does not publish a public unlock-token webpage. Deep Testing is an in-phone ColorOS flow.",
    )

    val REALME = BrandRecord(
        id = "realme",
        displayName = "realme",
        aliases = listOf("realme"),
        guideKind = RootGuideKind.REALME,
        portalId = "realme",
        firmware = OfficialHttps(
            "realme Support",
            "https://www.realme.com/global/support",
            listOf("realme.com"),
            "Official realme Support (HTTP 200 on 2026-10-20). Deep Testing is in-phone; MSM is an OEM PC program this app does not ship.",
        ),
        tool = OfficialHttps(
            "realme Support (MSM is not bundled)",
            "https://www.realme.com/global/support",
            listOf("realme.com"),
            "realme's MSM Download Tool, when offered, is obtained from realme Support. RootForge never ships it.",
        ),
        usesMsm = true,
        unlockOffered = true,
        note = "realme does not publish a public unlock-token webpage. Deep Testing is an in-phone Realme UI flow.",
    )

    val MOTOROLA = BrandRecord(
        id = "motorola",
        displayName = "Motorola",
        aliases = listOf("motorola", "moto"),
        guideKind = RootGuideKind.MOTOROLA,
        portalId = "motorola",
        firmware = OfficialHttps(
            "Motorola Support",
            "https://en-us.support.motorola.com/",
            listOf("motorola.com"),
            "Official Motorola Support (HTTP 200 on 2026-10-20). Rescue and Smart Assistant is Motorola's unbrick program — RootForge does not ship it.",
        ),
        tool = OfficialHttps(
            "Motorola Support (RSA is not bundled)",
            "https://en-us.support.motorola.com/",
            listOf("motorola.com"),
            "Motorola Rescue and Smart Assistant is the official PC unbrick path. RootForge never ships RSA, MSM, or firmware.",
        ),
        usesMsm = false,
        unlockOffered = true,
        note = "Motorola issues a per-phone unlock key from its official portal. Qualcomm Motorola phones unbrick with RSA, not MSM Download Tool.",
    )

    val SONY = BrandRecord(
        id = "sony",
        displayName = "Sony Xperia",
        aliases = listOf("sony", "xperia"),
        guideKind = RootGuideKind.SONY,
        portalId = "sony",
        firmware = OfficialHttps(
            "Sony Open Devices",
            "https://developer.sony.com/open-source/aosp-on-xperia-open-devices",
            listOf("sony.com"),
            "Sony Open Devices (developer.sony.com, HTTP 202 WAF with empty body on 2026-10-20). RootForge never sends IMEI.",
        ),
        tool = null,
        usesMsm = false,
        unlockOffered = true,
        note = "Sony Open Devices issues an unlock code from IMEI 1. RootForge never submits IMEI.",
    )

    val HTC = BrandRecord(
        id = "htc",
        displayName = "HTC",
        aliases = listOf("htc"),
        guideKind = RootGuideKind.HTC,
        portalId = "htc",
        firmware = OfficialHttps(
            "HTC Support",
            "https://www.htc.com/support",
            listOf("htc.com"),
            "Official HTC Support (HTTP 200 on 2026-10-20). HTCdev unlock is a separate portal.",
        ),
        tool = null,
        usesMsm = false,
        unlockOffered = true,
        note = "HTCdev still serves unlock for some pre-June-2018 models. Newer HTC phones are not on that programme.",
    )

    val FAIRPHONE = BrandRecord(
        id = "fairphone",
        displayName = "Fairphone",
        aliases = listOf("fairphone"),
        guideKind = RootGuideKind.FAIRPHONE,
        portalId = "fairphone",
        firmware = OfficialHttps(
            "Fairphone",
            "https://www.fairphone.com/",
            listOf("fairphone.com"),
            "Official Fairphone site (HTTP 200 on 2026-10-20). The dedicated support host returned 403 (WAF) here, so the homepage is the live official link.",
        ),
        tool = null,
        usesMsm = false,
        unlockOffered = true,
        note = "Current Fairphones often unlock from Developer options; older builds used a code generator.",
    )

    val NOTHING = BrandRecord(
        id = "nothing",
        displayName = "Nothing / CMF",
        aliases = listOf("nothing", "cmf"),
        guideKind = RootGuideKind.NOTHING,
        portalId = "nothing",
        firmware = OfficialHttps(
            "Nothing Support",
            "https://nothing.tech/pages/support",
            listOf("nothing.tech"),
            "Official Nothing Support (redirects to us.nothing.tech, HTTP 200 on 2026-10-20). RootForge does not download firmware.",
        ),
        tool = null,
        usesMsm = false,
        unlockOffered = true,
        note = "Nothing does not publish an unlock-token webpage. Unlocking is usually OEM unlocking then fastboot.",
    )

    val ASUS = BrandRecord(
        id = "asus",
        displayName = "ASUS",
        aliases = listOf("asus", "zenfone"),
        guideKind = RootGuideKind.ASUS,
        portalId = "asus",
        firmware = OfficialHttps(
            "ASUS Support",
            "https://www.asus.com/support/",
            listOf("asus.com"),
            "Official ASUS Support (HTTP 200 on 2026-10-20). Unlock servers were shut down; this is firmware/support, not an unlock portal.",
        ),
        tool = null,
        usesMsm = false,
        unlockOffered = false,
        note = "ASUS no longer offers a bootloader unlock tool (phones after January 2024 are not covered).",
    )

    val LG = BrandRecord(
        id = "lg",
        displayName = "LG",
        aliases = listOf("lge", "lg"),
        guideKind = RootGuideKind.LG,
        portalId = "lg",
        firmware = OfficialHttps(
            "LG Support",
            "https://www.lg.com/us/support",
            listOf("lg.com"),
            "Official LG Support (HTTP 200 on 2026-10-20). The unlock-code site is gone.",
        ),
        tool = null,
        usesMsm = false,
        unlockOffered = false,
        note = "LG shut its official unlock-code site. LG no longer offers bootloader unlocking.",
    )

    val HUAWEI = BrandRecord(
        id = "huawei",
        displayName = "Huawei / Honor",
        aliases = listOf("huawei", "honor"),
        guideKind = RootGuideKind.HUAWEI,
        portalId = "huawei",
        firmware = OfficialHttps(
            "Huawei Support",
            "https://consumer.huawei.com/en/support/",
            listOf("huawei.com"),
            "Official Huawei Support (HTTP 200 on 2026-10-20). Unlock codes stopped in 2018.",
        ),
        tool = null,
        usesMsm = false,
        unlockOffered = false,
        note = "Huawei stopped issuing bootloader unlock codes in 2018. There is no live official portal.",
    )

    val VIVO = BrandRecord(
        id = "vivo",
        displayName = "vivo / iQOO",
        aliases = listOf("vivo", "iqoo"),
        guideKind = RootGuideKind.VIVO,
        portalId = "vivo",
        firmware = OfficialHttps(
            "vivo Support",
            "https://www.vivo.com/en/support",
            listOf("vivo.com"),
            "Official vivo Support (HTTP 200 on 2026-10-20). vivo does not publish a consumer unlock programme.",
        ),
        tool = null,
        usesMsm = false,
        unlockOffered = false,
        note = "vivo / iQOO does not publish an official bootloader-unlock programme for retail phones.",
    )

    val NOKIA = BrandRecord(
        id = "nokia",
        displayName = "HMD / Nokia",
        aliases = listOf("nokia", "hmd"),
        guideKind = RootGuideKind.NOKIA,
        portalId = "nokia",
        firmware = OfficialHttps(
            "HMD Support",
            "https://www.hmd.com/en_int/support",
            listOf("hmd.com"),
            "Official HMD Support (HTTP 200 on 2026-10-20). There is no HMD unlock-token webpage.",
        ),
        tool = null,
        usesMsm = false,
        unlockOffered = true,
        note = "HMD / Nokia Android phones often unlock with OEM unlocking then fastboot. There is no maker token page.",
    )

    val LENOVO = BrandRecord(
        id = "lenovo",
        displayName = "Lenovo",
        aliases = listOf("lenovo"),
        guideKind = RootGuideKind.LENOVO,
        portalId = "lenovo",
        firmware = OfficialHttps(
            "Lenovo",
            "https://www.lenovo.com/",
            listOf("lenovo.com"),
            "Official Lenovo site (HTTP 200 on 2026-10-20). support.lenovo.com returned 403 here, so the vendor homepage is the live official link.",
        ),
        tool = null,
        usesMsm = false,
        unlockOffered = true,
        note = "Lenovo phones/tablets have no public unlock-token webpage. Motorola (also Lenovo) has its own portal.",
    )

    val MEIZU = BrandRecord(
        id = "meizu",
        displayName = "Meizu",
        aliases = listOf("meizu"),
        guideKind = RootGuideKind.GENERIC,
        portalId = "meizu",
        firmware = OfficialHttps(
            "Meizu",
            "https://www.meizu.com/",
            listOf("meizu.com"),
            "Official Meizu site (HTTP 200 on 2026-10-20). Flyme unlock is not a live public token page.",
        ),
        tool = null,
        usesMsm = false,
        unlockOffered = false,
        note = "Meizu does not publish a live official unlock-token webpage.",
    )

    val ZTE = BrandRecord(
        id = "zte",
        displayName = "ZTE / nubia / REDMAGIC",
        aliases = listOf("zte", "nubia", "redmagic"),
        guideKind = RootGuideKind.GENERIC,
        portalId = "zte",
        firmware = OfficialHttps(
            "ZTE Devices",
            "https://www.ztedevices.com/",
            listOf("ztedevices.com"),
            "Official ZTE Devices site (HTTP 200 on 2026-10-20). A zteusa.com/support URL redirected off-domain and was not used.",
        ),
        tool = null,
        usesMsm = false,
        unlockOffered = false,
        note = "ZTE / nubia / REDMAGIC does not publish a consumer unlock-token webpage.",
    )

    val TECNO = BrandRecord(
        id = "tecno",
        displayName = "TECNO",
        aliases = listOf("tecno"),
        guideKind = RootGuideKind.TRANSSION,
        portalId = "tecno",
        firmware = OfficialHttps(
            "TECNO",
            "https://www.tecno-mobile.com/",
            listOf("tecno-mobile.com"),
            "Official TECNO site (HTTP 200 on 2026-10-20). No public unlock-token page.",
        ),
        tool = null,
        usesMsm = false,
        unlockOffered = true,
        note = "TECNO often unlocks with OEM unlocking then fastboot. There is no maker token page.",
    )

    val INFINIX = BrandRecord(
        id = "infinix",
        displayName = "Infinix",
        aliases = listOf("infinix"),
        guideKind = RootGuideKind.TRANSSION,
        portalId = "infinix",
        firmware = OfficialHttps(
            "Infinix",
            "https://www.infinixmobility.com/",
            listOf("infinixmobility.com"),
            "Official Infinix site (HTTP 200 on 2026-10-20). No public unlock-token page.",
        ),
        tool = null,
        usesMsm = false,
        unlockOffered = true,
        note = "Infinix often unlocks with OEM unlocking then fastboot. There is no maker token page.",
    )

    val ITEL = BrandRecord(
        id = "itel",
        displayName = "itel",
        aliases = listOf("itel"),
        guideKind = RootGuideKind.TRANSSION,
        portalId = "itel",
        firmware = OfficialHttps(
            "itel",
            "https://www.itel-mobile.com/",
            listOf("itel-mobile.com", "itel-life.com"),
            "Official itel site (HTTP 200 on 2026-10-20, may redirect to itel-life.com). No public unlock-token page.",
        ),
        tool = null,
        usesMsm = false,
        unlockOffered = true,
        note = "itel often unlocks with OEM unlocking then fastboot. There is no maker token page.",
    )

    val TCL = BrandRecord(
        id = "tcl",
        displayName = "TCL / Alcatel",
        aliases = listOf("tcl", "alcatel"),
        guideKind = RootGuideKind.GENERIC,
        portalId = "tcl",
        firmware = OfficialHttps(
            "TCL Support",
            "https://www.tcl.com/us/en/support",
            listOf("tcl.com"),
            "Official TCL Support (HTTP 200 on 2026-10-20). No public unlock-token page.",
        ),
        tool = null,
        usesMsm = false,
        unlockOffered = true,
        note = "TCL / Alcatel has no public unlock-token webpage. Some models allow OEM unlocking then fastboot.",
    )

    val SHIFT = BrandRecord(
        id = "shift",
        displayName = "SHIFT",
        aliases = listOf("shift", "shiftphone"),
        guideKind = RootGuideKind.GENERIC,
        portalId = "shift",
        firmware = OfficialHttps(
            "SHIFT",
            "https://www.shiftphones.com/",
            listOf("shiftphones.com", "shift.eco"),
            "Official SHIFT site (HTTP 200 on 2026-10-20, may redirect to shift.eco).",
        ),
        tool = null,
        usesMsm = false,
        unlockOffered = true,
        note = "SHIFT has no separate unlock-token webpage; follow SHIFT's own docs. RootForge never sends serials.",
    )

    val all: List<BrandRecord> = listOf(
        GOOGLE, SAMSUNG, XIAOMI, ONEPLUS, OPPO, REALME, MOTOROLA, SONY, HTC, FAIRPHONE,
        NOTHING, ASUS, LG, HUAWEI, VIVO, NOKIA, LENOVO, MEIZU, ZTE, TECNO, INFINIX, ITEL, TCL, SHIFT,
    )

    fun byId(id: String): BrandRecord? = all.firstOrNull { it.id == id }

    fun forIdentity(identity: DeviceIdentity?): BrandRecord? {
        if (identity == null) return null
        val tokens = identity.brandTokens()
        if (tokens.isEmpty()) return null
        return all.firstOrNull { record -> record.tokens().any { it in tokens } }
    }

    fun officialPages(): List<OfficialHttps> =
        all.flatMap { listOfNotNull(it.firmware, it.tool) }.distinctBy { it.url }

    fun msmBrands(): List<BrandRecord> = all.filter { it.usesMsm }
}
