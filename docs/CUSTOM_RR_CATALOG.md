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

**Known gap.** Rows without model numbers cannot use the fallback. OnePlus stock
OxygenOS names (`oneplus6t` for `fajita`, reported model `ONEPLUS A6013`) still
get "No catalog entry lists …", and Transsion/realme vendor prefixes and regional
suffixes are not stripped. Custom RR's own app handles these with a hand-written
alias table; RootForge has not copied it (see [Licensing](#licensing)) and has no
table of its own.

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

## Possible follow-ups

1. RootForge's own alias table for rows without model numbers (see Known gap).
2. Show the card's catalog age more prominently, or warn when it is old.
3. OrangeFox / SHRP / RedWolf stay link-only; they are not part of the Custom
   Recovery writer (TWRP and PitchBlack only).
