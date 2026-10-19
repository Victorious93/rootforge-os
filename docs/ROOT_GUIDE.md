# Root Guide — Victorious Framework

Offline rooting documentation bundled in the RootForge OS app (Android host-phone APK and
Linux/Windows desktop client). Drawer entry: **Root Guide**.

This file is the maintenance guide for that feature. Keep it in step with the code.

## Status (2026-10-19)

| Item | State |
|---|---|
| 26 pages imported from Awesome Android Root + 1 RootForge page | in tree |
| Markdown parser, search, link allow-list (`:core`) | in tree, 19 unit tests |
| Compose screen (`GuideScreen`, shared by `:app` and `:desktop`) | in tree; `:desktop:compileKotlin` builds |
| Importer `android-app/tools/import_root_guide.py` | in tree, 14 Python tests |
| `:app` build, Paparazzi golden, run on a phone | **not done** (no Android SDK in the authoring session) |

## What is in it

`manifest.json` lists six sections: Start here, Unlock and recovery, Root frameworks, Device guides,
Tutorials, Help. Each entry has an `id`, `title`, `summary`, `file`, and `origin`:

- `origin: "awesome-android-root"` — generated from the upstream docs by the importer. Never hand-edit
  these files; the next import overwrites them.
- `origin: "rootforge"` — written here. Today only `rootforge-workflow.md` ("Rooting with RootForge OS"),
  which maps guide steps onto app screens. It must only describe behaviour the app has.

The upstream app/module catalogue (`docs/apps-and-modules/`, ~200 KB of link lists) is **not** imported.
Links to it open the website. Importing it would be a data-model decision (searchable entries with
Magisk / KernelSU / APatch / LSPosed tags), not a formatting one; see Future work.

## Architecture

```
awesome-android-root/docs/*.md
        │  tools/import_root_guide.py   (front matter, badges, images, <details>, VitePress ::: → plain markdown;
        │                                internal links → guide:<id>#anchor or website URL)
        ▼
android-app/core/src/main/resources/ai/rootforge/os/guide/   manifest.json, *.md, LICENSE-awesome-android-root.txt
        │  GuideLibrary (classpath read, parse on first use, cache)   ← :core, pure Kotlin, Apache-2.0
        │  GuideMarkdown → Block / Span model, GuideLink allow-list
        ▼
android-app/app/src/main/kotlin/ai/rootforge/os/ui/screens/GuideScreen.kt   ← Compose, GPL; compiled into :desktop too
```

Decisions:

- **Bundled, offline.** The app has no INTERNET permission and the guide must be readable with a
  bricked target and no network. Pages are classpath resources in `:core`, so Android and desktop share one copy.
- **Own small parser instead of a markdown library.** The pages use a small subset; a dependency would
  have to be added to Android and desktop, and Android has no stock markdown renderer. The parser is
  tested against the real pages (`GuideLibraryTest`).
- **Links are allow-listed.** `GuideLink.parse` accepts `guide:`, `#anchor` and `https://` only, matching
  `AppActions.openUrl`. `javascript:`, `file:`, `intent:`, `http:` and `mailto:` are rendered as text.
- **Heading anchors follow VitePress slugify** (including `-1`, `-2` for duplicates) so anchors authored
  upstream resolve. `GuideLibraryTest` fails if any internal link or anchor dangles.
- **Guide is read-only.** It never touches the target and adds nothing to `AppActions`.

## Maintenance

Refresh from upstream:

```bash
git -C /path/to/awesome-android-root pull
python3 android-app/tools/import_root_guide.py /path/to/awesome-android-root
cd android-app && ./gradlew :core:test        # link/anchor/HTML checks run on the new pages
python3 -m unittest discover -s tests -p 'test_guide_import.py'   # from the repo root
git diff --stat android-app/core/src/main/resources
```

`manifest.json` records `commit` and `importedFrom` (the checkout used, which may be a fork). Read the
diff: upstream pages change advice about specific devices and tools, and nobody has checked those
instructions on hardware here.

Add or reorder a page: edit `SECTIONS` in the importer, re-run it. A page whose source is `None` must
already exist in the resources directory. Stale generated pages are deleted automatically.

If a test reports a dangling anchor after an import, that is an upstream defect (their site would have
the same dead link). Fix it upstream, or accept the loss of scroll target and say why in the commit.

## Limits (honest)

- The upstream text is community-written and is **reference, not verification**. RootForge did not test
  the commands for any listed device. Some pages describe steps (Samsung Odin, Xiaomi unlock tool,
  vbmeta, Play Integrity modules) the app does not perform; `rootforge-workflow.md` says so.
- Pages still contain `adb`/`fastboot` commands written for a computer. The app does not run them from
  the guide; there are no "run this" buttons by design. Code blocks are selectable for copying.
- Images, badges and raw HTML are dropped at import. Tables render as horizontally scrollable grids
  when they do not fit.
- No paparazzi golden yet: recording one needs the Android SDK. `Previews.kt` has `PreviewRootGuide`.
- Android loads the pages through `Class.getResourceAsStream` on a Java library jar. That is standard
  and [Likely] to work in the APK, but it has not been run on a device or emulator.

## Remaining work

1. Build `:app` (`assembleDebug`, `testDebugUnitTest`) with an Android SDK; record a Root Guide
   Paparazzi golden in `ScreenshotTest` and `android-app/screenshots/`.
2. Open the guide in the APK on a phone; confirm page load, anchor jump, search, back stack and
   rotation (state is `rememberSaveable`).
3. Decide whether Flash Tools / Root screens should deep-link to a guide page (e.g. "Which partition?"
   → the device guide). Needs a small `AppUiState` or navigation hook; not built.

## Future improvements

- Import the apps-and-modules catalogue as structured, filterable data (root apps, Magisk, KernelSU,
  APatch, LSPosed tags) instead of prose.
- Device-aware ordering: when a target is connected, surface its brand's guide first
  (`TargetInfo` already carries manufacturer/model).
- A CI job that regenerates from a pinned upstream commit and fails on drift.
- Fixed-width column hints and a sticky first column for wide tables.

## Licence

Imported pages: MIT, © Awesome Android Root (`NOTICE`, `LICENSE-awesome-android-root.txt` beside the
pages). `rootforge-workflow.md`, the parser and the screen: this repository's licences (`:core`
Apache-2.0, `:app`/`:desktop` GPL-3.0-or-later).
