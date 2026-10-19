#!/usr/bin/env python3
"""Victorious Framework: import the Awesome Android Root docs as RootForge OS Root Guide content.

Reads a checkout of awesome-android-root (MIT) and writes plain-markdown guide pages plus
manifest.json into android-app/core/src/main/resources/ai/rootforge/os/guide/.

    python3 android-app/tools/import_root_guide.py /path/to/awesome-android-root

The output is deterministic for a given source commit. The apps-and-modules catalogue is
deliberately not imported (about 200 KB of link lists; the guide links to the website instead).
Pages authored by RootForge (origin "rootforge") are never written by this script; it only
checks that they exist and lists them in the manifest.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

SITE = "https://awesome-android-root.zhoe.org"
UPSTREAM = "https://github.com/awesome-android-root/awesome-android-root"
OUT = Path(__file__).resolve().parents[1] / "core/src/main/resources/ai/rootforge/os/guide"

# (section title, [(id, source path relative to docs/ or None for a RootForge page, summary)])
SECTIONS = [
    ("Start here", [
        ("rootforge-workflow", None, "Which RootForge OS screen does which step of a root."),
        ("rooting-overview", "rooting-guides/index.md", "The whole path: backup, unlock, patch, flash, hide, maintain."),
        ("framework-comparison", "rooting-guides/root-framework-comparison.md", "Differences, trade-offs and how to choose a root method."),
        ("legal-disclaimer", "legal-disclaimer.md", "Warranty, security and data-loss risks. Read before unlocking."),
    ]),
    ("Unlock and recovery", [
        ("unlock-bootloader", "rooting-guides/how-to-unlock-bootloader.md", "OEM unlocking, fastboot commands, what gets wiped."),
        ("custom-recovery", "rooting-guides/how-to-install-custom-recovery.md", "TWRP and OrangeFox installation."),
        ("pitchblack-recovery", None, "PitchBlack (PBRP): what it is and how to try it with RootForge."),
        ("custom-rom", "rooting-guides/custom-rom-installation.md", "Flashing a custom ROM safely."),
    ]),
    ("Root frameworks", [
        ("magisk", "rooting-guides/magisk-guide.md", "Install and configure Magisk."),
        ("magisk-kitsune", None, "Kitsune Mask / Magisk Delta: status and why RootForge does not drive it."),
        ("kernelsu", "rooting-guides/kernelsu-guide.md", "Install and configure KernelSU."),
        ("apatch", "rooting-guides/apatch-guide.md", "Install and configure APatch."),
        ("lsposed", "rooting-guides/lsposed-guide.md", "LSPosed / Vector framework setup."),
        ("temporary-root", "rooting-guides/temporary-root-solutions.md", "Boot-once and temporary root options."),
        ("root-locked-bootloader", "rooting-guides/root-without-unlocking-bootloader.md", "Temporary root without unlocking the bootloader."),
    ]),
    ("Device guides", [
        ("pixel", "rooting-guides/how-to-root-pixel-phone.md", "Google Pixel."),
        ("samsung", "rooting-guides/how-to-root-samsung-phone.md", "Samsung Galaxy (Odin, Knox)."),
        ("xiaomi", "rooting-guides/how-to-root-xiaomi-phone.md", "Xiaomi, Redmi, POCO."),
        ("oneplus", "rooting-guides/how-to-root-oneplus-phone.md", "OnePlus."),
        ("motorola", "rooting-guides/how-to-root-motorola-phone.md", "Motorola."),
        ("nothing", "rooting-guides/how-to-root-nothing-phone.md", "Nothing and CMF."),
    ]),
    ("Tutorials", [
        ("adblocking", "general-guides/android-adblocking.md", "System-wide ad blocking on a rooted phone."),
        ("debloating", "general-guides/android-apps-debloating.md", "Remove bloatware with App Manager or Canta."),
        ("stop-auto-updates", "general-guides/stop-android-app-auto-updates-play-store.md", "Detach apps from Play Store updates."),
        ("non-root-alternatives", "non-root-alternatives.md", "Root-like features without root (Shizuku, ADB)."),
    ]),
    ("Help", [
        ("faq", "faqs.md", "Questions to answer before and after rooting."),
        ("troubleshooting", "troubleshooting.md", "Bootloops, manager problems, Play Integrity, banking apps."),
        ("resources", "resources.md", "Firmware, tooling, communities, emergency help."),
        ("about", "about.md", "About the upstream project and its licence."),
    ]),
]

FRONTMATTER = re.compile(r"\A---\n.*?\n---\n", re.S)
FENCE = re.compile(r"^(\s*)(```+|~~~+)")
IMAGE = re.compile(r"!\[[^\]]*\]\([^)]*\)")
LINKED_IMAGE = re.compile(r"\[!\[[^\]]*\]\([^)]*\)\]\([^)]*\)")
LINK = re.compile(r"(?<!!)\[([^\]]*)\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
HTML_TAG = re.compile(r"</?(?:div|br|span|p|center|picture|source|img|a)(?:\s[^>]*)?/?>", re.I)


def split_fences(text: str):
    """Yield (is_code, line) so transforms never touch fenced code."""
    fence = None
    for line in text.split("\n"):
        m = FENCE.match(line)
        if fence is None:
            if m:
                fence = m.group(2)[0]
                yield True, line
            else:
                yield False, line
        else:
            yield True, line
            if m and m.group(2)[0] == fence and line.strip().strip(fence) == "":
                fence = None


def slug(heading: str) -> str:
    s = re.sub(r"[^\w\s-]", "", heading.lower(), flags=re.U)
    return re.sub(r"[\s_]+", "-", s.strip()).strip("-")


def title_of(text: str, fallback: str) -> str:
    for is_code, line in split_fences(text):
        if not is_code and line.startswith("# "):
            return re.sub(r"\s*\{#[^}]+\}\s*$", "", line[2:]).strip()
    return fallback


def build_route_map():
    routes = {}
    for _, items in SECTIONS:
        for gid, src, _ in items:
            if src:
                routes[src] = gid
    return routes


def resolve_link(target: str, current_src: str, routes: dict[str, str]) -> str | None:
    """Return 'guide:<id>[#a]', an https URL, or None to drop the link and keep its text."""
    if re.match(r"^(https?:|mailto:)", target):
        return target
    path, _, anchor = target.partition("#")
    if not path:
        return "#" + anchor if anchor else None
    if path.startswith("/"):
        rel = path.lstrip("/")
    else:
        base = current_src.rsplit("/", 1)[0] if "/" in current_src else ""
        parts = [p for p in (base + "/" + path).split("/") if p not in ("", ".")]
        out: list[str] = []
        for p in parts:
            if p == "..":
                if out:
                    out.pop()
            else:
                out.append(p)
        rel = "/".join(out)
    candidates = [rel] if rel.endswith(".md") else [rel + ".md", rel.rstrip("/") + "/index.md", rel + "index.md"]
    for cand in candidates:
        if cand in routes:
            return "guide:" + routes[cand] + ("#" + anchor if anchor else "")
    web = rel[:-3] if rel.endswith(".md") else rel
    if web.endswith("/index"):
        web = web[: -len("/index")]
    return f"{SITE}/{web.strip('/')}" + ("#" + anchor if anchor else "")


def transform_inline(line: str, current_src: str, routes: dict[str, str]) -> str:
    line = LINKED_IMAGE.sub("", line)
    line = IMAGE.sub("", line)
    line = HTML_TAG.sub(lambda m: "\n" if m.group(0).lower().startswith("<br") else "", line)

    def link(m: re.Match) -> str:
        label, target = m.group(1), m.group(2)
        resolved = resolve_link(target, current_src, routes)
        return label if resolved is None else f"[{label}]({resolved})"

    return LINK.sub(link, line)


def convert(text: str, current_src: str, routes: dict[str, str]) -> str:
    text = FRONTMATTER.sub("", text.replace("\r\n", "\n"), count=1)
    out: list[str] = []
    for is_code, line in split_fences(text):
        if is_code:
            out.append(line)
            continue
        stripped = line.strip()
        m = re.match(r"^<details[^>]*>\s*(?:<summary[^>]*>(.*?)</summary>)?\s*(?:<br\s*/?>)?\s*$", stripped, re.I)
        if m:
            title = re.sub(r"<[^>]+>", "", m.group(1) or "").strip()
            out.append(f"::: details {title}".rstrip())
            continue
        m = re.match(r"^<summary[^>]*>(.*?)</summary>\s*$", stripped, re.I)
        if m:
            # The title belongs on the opening line we just emitted.
            idx = len(out) - 1
            while idx >= 0 and out[idx] != "::: details":
                idx -= 1
            if idx >= 0:
                title = re.sub(r"<[^>]+>", "", m.group(1)).strip()
                out[idx] = f"::: details {title}"
            continue
        if re.match(r"^</details>\s*$", stripped, re.I):
            out.append(":::")
            continue
        m = re.match(r"^:::\s*([a-z]+)\s*(.*)$", stripped)
        if m:
            out.append(f"::: {m.group(1)} {m.group(2)}".rstrip())
            continue
        if stripped == ":::":
            out.append(":::")
            continue
        converted = transform_inline(line, current_src, routes)
        # Badge-only or image-only lines become empty and are collapsed below.
        out.extend(converted.split("\n"))
    cleaned: list[str] = []
    for line in out:
        if line.strip() == "" and cleaned and cleaned[-1].strip() == "":
            continue
        cleaned.append(line.rstrip())
    return "\n".join(cleaned).strip() + "\n"


def git(repo: Path, *args: str) -> str:
    try:
        return subprocess.check_output(["git", "-C", str(repo), *args], text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    repo = Path(argv[1]).resolve()
    docs = repo / "docs"
    if not docs.is_dir():
        print(f"error: {docs} is not a directory", file=sys.stderr)
        return 1
    routes = build_route_map()
    OUT.mkdir(parents=True, exist_ok=True)
    manifest_sections = []
    written = set()
    for section, items in SECTIONS:
        entries = []
        for gid, src, summary in items:
            target = OUT / f"{gid}.md"
            if src is None:
                if not target.is_file():
                    print(f"error: RootForge page {target} is missing", file=sys.stderr)
                    return 1
                title = title_of(target.read_text(encoding="utf-8"), gid)
                origin = "rootforge"
            else:
                source = docs / src
                if not source.is_file():
                    print(f"error: {source} not found", file=sys.stderr)
                    return 1
                body = convert(source.read_text(encoding="utf-8"), src, routes)
                target.write_text(body, encoding="utf-8")
                title = title_of(body, gid)
                origin = "awesome-android-root"
                written.add(target.name)
            entries.append({"id": gid, "title": title, "summary": summary, "file": target.name, "origin": origin,
                            "sourcePath": src or ""})
        manifest_sections.append({"title": section, "entries": entries})
    manifest = {
        "source": {
            "name": "Awesome Android Root",
            "repository": UPSTREAM,
            "website": SITE,
            "license": "MIT",
            "importedFrom": git(repo, "remote", "get-url", "origin"),
            "commit": git(repo, "rev-parse", "HEAD"),
        },
        "sections": manifest_sections,
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    # Remove generated pages that are no longer listed (never RootForge pages).
    keep = {e["file"] for s in manifest_sections for e in s["entries"]} | {"manifest.json"}
    for stale in OUT.glob("*.md"):
        if stale.name not in keep:
            stale.unlink()
    print(f"wrote {len(written)} imported pages and manifest.json to {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
