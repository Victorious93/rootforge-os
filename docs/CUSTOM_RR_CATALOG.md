# Custom RR catalog — builds for the connected device

RootForge can read the ROM / recovery / root-solution catalog that
[Custom RR](https://github.com/monsiu/Custom-RR) publishes, and answer "which
community builds list this phone?" for the connected target. Custom RR stays a
separate app; RootForge only parses its published data.

**Status:** the Dashboard has a **Builds for this device** card on the Android
host-phone app and on the desktop client. The catalog arrives one of two ways:

| Option | What happens | Network |
|---|---|---|
| **Download** / **Update** | Two https GETs to raw.githubusercontent.com (catalog, then freshness feed), only when tapped | Yes (`INTERNET` permission on Android) |
| **Import file** | Pick a `catalog.json` or `freshness.json` you already have; the app tells them apart by their keys | None |

**Remove** deletes the stored copy. Nothing is fetched at startup or on a timer.

## What it is for (and not for)

- **For:** showing, next to a connected target, the ROMs, recoveries and root
  solutions whose catalog entry lists that phone's product or codename, with
  the project's download page, its forum, any warning the catalog carries, and a
  freshness label (`active`, `stale`, `abandoned`). A matching **TWRP** or
  **PitchBlack** recovery row also has **Install with Custom Recovery**, which
  opens the Custom Recovery screen already aimed at that project
  ([CUSTOM_RECOVERY_INSTALLER.md](CUSTOM_RECOVERY_INSTALLER.md)).
- **Not for:** deciding whether a flash is allowed. Nothing in `catalog/` is read
  by `DeviceMatchGate`, `FlashSafety` or any typed confirmation. A listing is not
  permission to flash and does not stand in for an image sidecar manifest. No
  setting changes that. The installer still defaults to dry-run, shows SHA-256,
  verifies a published checksum when one exists, backs up first, and needs typed
  `FLASH RECOVERY`.
- **Not a zip/image source.** The catalog has no image files and no digests, only
  each project's download page. ROMs and root solutions stay browser links. A
  TWRP / PitchBlack install looks up official files from the **probed** phone
  (not from the catalog row's device list) and still has to pass the installer's
  https host allow-list. OrangeFox, SHRP and RedWolf stay link-only. A file the
  user downloads for Flash Tools still needs its own `<image>.manifest.json`
  ([FLASH_TOOLS_PLAN.md](FLASH_TOOLS_PLAN.md)).

## Code

All shared logic is in `:core` (Apache-2.0), package `ai.rootforge.os.catalog`:

| Piece | What it does |
|---|---|
| `BuildCatalogParser` | Parses `catalog.json` (`roms`, `recoveries`, `roots`, `_generatedAt`) and `freshness.json` (`entries` by id). Unknown keys are ignored; an entry without `id`/`name`, or a non-JSON body, throws `IllegalArgumentException`. |
| `BuildLookup.forTarget(target, catalog, freshness?)` | `BuildMatch` rows for a `TargetIdentity`: ROMs, then recoveries, then root solutions; official before unofficial within each kind; otherwise catalog order. |
| `BuildCatalogFetcher` | `https://` only, 15 s connect / 30 s read timeouts, a non-200 status is an error, 8 MiB cap (the published catalog is about 1.4 MB). |
| `BuildCatalogStore(dir)` | Holds the last good catalog in app-private storage (`filesDir/build-catalog` on Android, `~/.rootforge/build-catalog` on desktop). Every write is parsed first and written via a temp file + rename, so a bad download, an HTML error page, `{}` or the wrong file never replaces a good catalog. A missing freshness feed still stores the catalog. A stored file that no longer parses loads as "no catalog". |
| `CatalogEntry.safeDownloadUrl()` / `safeForumUrl()` | The link only if it starts with `https://`. The card uses these, and both apps' `openUrl` also refuse anything else. |
| `CatalogRecoveryBridge.prefill(match)` | Maps a RECOVERY `BuildMatch` onto `CatalogRecoveryPrefill` (TWRP or PitchBlack, https page on the installer allow-list). ROMs, root solutions, OrangeFox, SHRP, RedWolf, `http://` and off-host pages return null. Official lookup uses `lookupNames(probed)` — the phone, not the catalog row. |

Clients: `ui/screens/BuildCatalogCard.kt` (shared Compose UI), `AppActionsImpl`
(Android) and `DesktopActions` (desktop). On Android, Import goes through the
normal file picker into the library and the library copy is deleted afterwards;
the catalog is kept separately so it never shows up in flash file lists.
**Install with Custom Recovery** is shown only when `CatalogRecoveryBridge`
returns a prefill; it switches to the Custom Recovery drawer screen with that
project selected. The 2026-09-12 Custom RR catalog lists five recoveries
(`twrp`, `orangefox`, `redwolf`, `pitchblack`, `shrp`); only TWRP (`twrp.me`)
and PitchBlack (`pitchblack.tech`) map. `pitchblack.tech` was added to the
installer allow-list for that reason (https only).

**Matching rules.** The target's product and codename (lower-cased, `unknown`
dropped, via `TargetIdentity.names()`) are compared exactly against each catalog
device codename. Combined pages such as `rmx2001/rmx2151`, `X00T, X00TD` and
`instantnoodle & instantnoodlep` are split on `/`, `,`, `&` and spaces. A prefix
is not a match.

**Model-number fallback.** When no codename of an entry matches, the target's
`ro.product.model` (read over ADB, kept on `TargetInfo.modelNumber`, not shown as
a row) is compared exactly and case-insensitively against the catalog row's
`models` list. This uses the catalog's own data, not an alias table: 4,578 of the
5,936 device rows in Custom RR's 2026-09-12 catalog carry model numbers. Example:
a Redmi 8 reports codename `olive` and model `M1908C3IG`; no entry lists `olive`,
but the unified `mi439` rows list that model, so 16 ROMs match. The card says
"matched model M1908C3IG as Mi439" on such rows. A codename match always wins over
a model match for the same entry. Fastboot reports no model, so the fallback only
applies over ADB.

An unknown target (no product, codename or model) shows "Connect a target":
unknown is not a wildcard.

**Alias table.** OnePlus stock builds report `ro.product.device` as e.g.
`OnePlus6T`, while the catalog says `fajita`. `:core` `CodenameAliases` maps 48
stock names to catalog codenames (OnePlus 3T through 9R, Nord / N10 / N200 and the
newer `OP…L1` device names). It only **adds** names to look up; the phone's own
names still match, so rows filed under both forms (`oneplus8` and `instantnoodle`)
both show. It is display-only and never feeds `DeviceMatchGate`.

How it was built (RootForge's own, not Custom RR's `codename_aliases.dart`): each
stock name and its model numbers come from Google's public Play supported-devices
list (`storage.googleapis.com/play_public/supported_devices.csv`); a row was kept only
when one of those model numbers appears under the target codename in the catalog
(`assets/catalog.json`, `_generatedAt` read 2026-10-20). The few catalog rows
without model numbers (OnePlus 5, 5T, 6, 6T, One) are matched by marketing name
instead, which is a weaker check [Likely]. A script confirmed all 48 targets exist
as codenames in that catalog; it was a throwaway and is not committed. Table
entries are not re-verified automatically: if the catalog renames a codename the
alias just stops matching.

**Known gap.** Rows without model numbers still cannot use the model fallback, and
Transsion/realme vendor prefixes and regional suffixes are not stripped. Brands
other than OnePlus have no alias table.

## Data source

The same URLs Custom RR's app reads (its `lib/data/catalog_repository.dart` and
`lib/data/freshness_repository.dart`):

- `https://raw.githubusercontent.com/monsiu/Custom-RR/main/assets/catalog.json`
- `https://raw.githubusercontent.com/monsiu/Custom-RR/main/assets/freshness.json`

These point at the upstream repository, not the `Victorious93/Custom-RR` fork.
The format is third-party, unversioned and unsigned. A field rename upstream can
make the parser drop data (unknown keys are ignored) or reject the download (a
required field disappears); a rejected download keeps the previous catalog.
Trust implications are in [SECURITY_MODEL.md](SECURITY_MODEL.md).

On 2026-10-19 a live download from this agent returned 37 entries (catalog
`_generatedAt` 2026-09-12) and 35 freshness rows. The freshness feed is
refreshed nightly upstream; the catalog itself less often. Many matches are
discontinued projects (`abandoned` / `stale`); the card shows that label rather
than hiding them.

## Licensing

Custom RR is **GPL-3.0-only**, copyright its authors (`monsiu`). `:core` is
Apache-2.0. So:

- No Custom RR source code is copied into RootForge. The parser was written
  against the JSON shape only.
- No Custom RR catalog data is bundled in the APK, the desktop package or the
  repository. Tests and screenshots use small placeholder entries written here.
- Fetching the published JSON at the user's request and showing it does not copy
  it into this repository. [Likely] — this is not legal advice; bundling a
  snapshot would need a separate look at the data's licence.

## Verification (2026-10-19)

- `./gradlew --rerun-tasks :core:test :desktop:test :desktop:compileKotlin
  :app:verifyPaparazziDebug :app:assembleDebug` → `:core` 119 tests / 0 failures
  (`BuildCatalogTest` 10, `BuildCatalogStoreTest` 10), `:desktop` 2 / 0,
  `:app` 28 / 0 including screenshot verification. The Dashboard and Flash Tools
  goldens were re-recorded and a `buildCatalog` golden added.
- `aapt2 dump permissions` on the debug APK lists `INTERNET` and
  `POST_NOTIFICATIONS`.
- Throwaway tests (not committed): one parsed Custom RR's bundled
  `catalog.json`/`freshness.json`; one ran `BuildCatalogStore.download()` against
  the live URLs from this agent's JVM and looked up `shiba` (16 ROM matches).
- **Not run:** the APK on a phone (so Download over a phone's network, the
  Android file picker path, and the card with a real target are untested), and
  the desktop window.

## Phone test checklist (owner)

Not run by the agent: it needs a phone. Use a debug APK built from this branch and
a real target phone with USB debugging on. Write down what you see; "it looked
fine" is not a result.

1. **Install.** `adb install -r app-debug.apk` (or sideload the CI artifact). If the
   v0.2.0 APK is installed, uninstall it once first (different debug key).
2. **Before any catalog.** Open Dashboard → *Builds for this device*. Expect: "No
   catalog yet…" and the buttons *Download* and *Import file*; no *Remove*.
3. **Download.** Turn on mobile data or Wi-Fi, tap *Download*. Expect: a line like
   "<source> · catalog generated <date>", the button now says *Update*, and *Remove*
   appears. Repeat with network off: expect a readable error, and the old catalog
   (if any) still shown.
4. **Import file.** Tap *Remove*, then put `catalog.json` (and optionally
   `freshness.json`) from `github.com/monsiu/Custom-RR/tree/main/assets` on the
   phone, tap *Import file*, pick it. Expect the same card as step 3. Then pick a
   non-catalog file (any `.txt`): expect a rejection and the earlier catalog kept.
5. **With a real target.** Plug in the target phone (this phone as host, USB OTG),
   allow USB debugging, wait for the Dashboard to show its codename. Expect build
   rows that list that exact codename; the freshness pill and "last build" appear
   when `freshness.json` was loaded. Record the codename and model you saw.
6. **Fallbacks.** Try (a) a OnePlus on stock OxygenOS / ColorOS: expect rows via
   the alias table (e.g. `OnePlus6T` → fajita builds); (b) a Redmi 8: expect rows
   labelled "matched model M1908C3IG as …"; (c) a phone no entry lists: expect
   "No catalog entry lists …", not a crash or an empty card.
7. **Buttons.** *Download page* / *Forum* should open the system browser only. On a
   TWRP or PitchBlack RECOVERY row, *Install with Custom Recovery* should open the
   Custom Recovery screen pre-filled, still in **dry-run**. Do **not** turn off
   dry-run or type `FLASH RECOVERY` unless you mean to flash that phone.
8. **Report back:** device, Android version, what happened at each step, and
   screenshots of any step that did not match "Expect".

## Possible follow-ups

1. Alias tables for other brands, built the same way (only rows with a verifiable
   source).
2. Mark rows found through an alias on the card (today they look like codename
   matches).
3. Show the card's catalog age more prominently, or warn when it is old.
4. OrangeFox / SHRP / RedWolf stay link-only; they are not part of the Custom
   Recovery writer (TWRP and PitchBlack only).
