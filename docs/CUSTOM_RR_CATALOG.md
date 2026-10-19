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
  freshness label (`active`, `stale`, `abandoned`).
- **Not for:** deciding whether a flash is allowed. Nothing in `catalog/` is read
  by `DeviceMatchGate`, `FlashSafety` or any typed confirmation. A listing is not
  permission to flash and does not stand in for an image sidecar manifest. No
  setting changes that.
- **Not a download source.** The catalog has no image files and no digests, only
  each project's download page. Those open in the browser. A file the user then
  downloads still needs its own `<image>.manifest.json` to pass the Flash Tools
  check ([FLASH_TOOLS_PLAN.md](FLASH_TOOLS_PLAN.md)).

## Code

All shared logic is in `:core` (Apache-2.0), package `ai.rootforge.os.catalog`:

| Piece | What it does |
|---|---|
| `BuildCatalogParser` | Parses `catalog.json` (`roms`, `recoveries`, `roots`, `_generatedAt`) and `freshness.json` (`entries` by id). Unknown keys are ignored; an entry without `id`/`name`, or a non-JSON body, throws `IllegalArgumentException`. |
| `BuildLookup.forTarget(target, catalog, freshness?)` | `BuildMatch` rows for a `TargetIdentity`: ROMs, then recoveries, then root solutions; official before unofficial within each kind; otherwise catalog order. |
| `BuildCatalogFetcher` | `https://` only, 15 s connect / 30 s read timeouts, a non-200 status is an error, 8 MiB cap (the published catalog is about 1.4 MB). |
| `BuildCatalogStore(dir)` | Holds the last good catalog in app-private storage (`filesDir/build-catalog` on Android, `~/.rootforge/build-catalog` on desktop). Every write is parsed first and written via a temp file + rename, so a bad download, an HTML error page, `{}` or the wrong file never replaces a good catalog. A missing freshness feed still stores the catalog. A stored file that no longer parses loads as "no catalog". |
| `CatalogEntry.safeDownloadUrl()` / `safeForumUrl()` | The link only if it starts with `https://`. The card uses these, and both apps' `openUrl` also refuse anything else. |

Clients: `ui/screens/BuildCatalogCard.kt` (shared Compose UI), `AppActionsImpl`
(Android) and `DesktopActions` (desktop). On Android, Import goes through the
normal file picker into the library and the library copy is deleted afterwards;
the catalog is kept separately so it never shows up in flash file lists.

**Matching rules.** The target's product and codename (lower-cased, `unknown`
dropped, via `TargetIdentity.names()`) are compared exactly against each catalog
device codename. Combined pages such as `rmx2001/rmx2151` are split on `/`. A
prefix is not a match. An unknown target shows "Connect a target" instead of a
list: unknown is not a wildcard. The marketing `models` list is not matched.

**Known gap.** Custom RR's own app also tries codename aliases (OnePlus stock
names such as `oneplus6t` → `fajita`, Xiaomi unified boards such as `olive` →
`mi439`, Transsion/realme suffixes). RootForge does not; such a phone gets "No
catalog entry lists …". Adding aliases means writing RootForge's own table, not
copying Custom RR's code (see [Licensing](#licensing)).

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

1. RootForge's own codename-alias table (see Known gap).
2. Show the card's catalog age more prominently, or warn when it is old.
