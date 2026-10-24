package ai.rootforge.os.catalog

/**
 * Stock-firmware device names that the Custom RR catalog files under a
 * different codename, so [BuildLookup] can find the same hardware.
 *
 * OnePlus stock builds report `ro.product.device` as e.g. `OnePlus6T`, while
 * ROM projects (and the catalog) use the LineageOS-style `fajita`.
 *
 * This table is RootForge's own. It was written from two public sources, not
 * copied from Custom RR's `codename_aliases.dart` (GPL-3.0-only; `:core` is
 * Apache-2.0): Google's Play supported-devices list (stock device name +
 * model numbers) and the catalog's own `models` lists. A row is here only if
 * a model number from Google's list appears under the target codename in the
 * catalog; the four phones whose catalog rows carry no model numbers (5, 5T,
 * 6, 6T) and the original One are matched by marketing name instead.
 * Checked against the catalog generated 2026-10-20.
 *
 * Display-only: the result never feeds
 * [ai.rootforge.os.fastboot.DeviceMatchGate] or authorizes a flash. Aliases
 * only add names to look up; they never replace the phone's own names.
 */
object CodenameAliases {
    private val table: Map<String, Set<String>> = mapOf(
        // Marketing-name matches (catalog rows have no model numbers).
        "a0001" to setOf("bacon"),
        "oneplus5" to setOf("cheeseburger"),
        "oneplus5t" to setOf("dumpling"),
        "oneplus6" to setOf("enchilada"),
        "oneplus6t" to setOf("fajita"),
        "oneplus6tsingle" to setOf("fajita"),
        // Model-number matches.
        "oneplus3t" to setOf("oneplus3"),
        "oneplus7" to setOf("guacamoleb"),
        "oneplus7pro" to setOf("guacamole"),
        "oneplus7protmo" to setOf("guacamole"),
        "oneplus7t" to setOf("hotdogb"),
        "oneplus7ttmo" to setOf("hotdogb"),
        "oneplus7tpro" to setOf("hotdog"),
        "oneplus8" to setOf("instantnoodle"),
        "oneplus8tmo" to setOf("instantnoodle"),
        "oneplus8visible" to setOf("instantnoodle"),
        "oneplus8pro" to setOf("instantnoodlep"),
        "oneplus8t" to setOf("kebab"),
        "oneplus8ttmo" to setOf("kebab"),
        "oneplus9" to setOf("lemonade"),
        "oneplus9tmo" to setOf("lemonade"),
        "oneplus9pro" to setOf("lemonadep"),
        "oneplus9protmo" to setOf("lemonadep"),
        "oneplus9r" to setOf("lemonades"),
        "nord" to setOf("avicii"),
        "oneplusn10" to setOf("billie"),
        "oneplusn10metro" to setOf("billie"),
        "oneplusn10tmo" to setOf("billie"),
        "oneplusn200" to setOf("dre"),
        "op5154l1" to setOf("martini"),
        "op5155l1" to setOf("martini"),
        "op535dl1" to setOf("oscaro"),
        "op5958l1" to setOf("larry"),
        "op594dl1" to setOf("salami"),
        "op595dl1" to setOf("waffle"),
        "op5d35l1" to setOf("aston"),
        "op5cf9l1" to setOf("astonc"),
        "op5cfbl1" to setOf("audi"),
        "op5d06l1" to setOf("corvette"),
        "op5d0dl1" to setOf("dodge"),
        "op5d55l1" to setOf("dodge"),
        "op5d2bl1" to setOf("giuliac"),
        "op5d3bl1" to setOf("giulia"),
        "op5d3fl1" to setOf("benz"),
        "op5e93l1" to setOf("avalon"),
        "op60ffl1" to setOf("infiniti"),
        "op611fl1" to setOf("infiniti"),
        "op6131l1" to setOf("lexus"),
    )

    /** Catalog codenames for one lower-case stock [name]; empty if none. */
    fun forName(name: String): Set<String> = table[name.trim().lowercase()].orEmpty()

    /** [names] plus every catalog codename they alias. */
    fun expand(names: Set<String>): Set<String> = names + names.flatMap { forName(it) }
}
