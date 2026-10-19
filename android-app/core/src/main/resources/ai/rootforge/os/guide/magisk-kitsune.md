# Magisk Kitsune (Kitsune Mask)

Kitsune Mask, also called Magisk Delta, is a community fork of Magisk by HuskyDG. It was popular for features the official Magisk lacks, such as running Magisk without Zygisk and a built-in hide option. **RootForge OS does not support it in Root / Patching, and this page explains why.**

::: warning Status: unmaintained [Likely]
When this page was written (2026-10-19) the checks below came back as follows. They were made from a sandbox without direct access to the project's own pages, so confirm them yourself before relying on them.
:::

## What was found

- The project's `HuskyDG/magisk-files` repository on GitHub returned "404 Not Found".
- A third-party mirror on the KDE Store says the files are hosted there temporarily because that repository is gone.
- The newest build found was `R6687BB53-kitsune` (version 27001), a pre-release based on official Magisk 27.0, listed about two years old. No later build was found.
- No official discontinuation notice was found. Absence of a notice is not evidence the project is alive.

## What that means for you

- A boot image patched by an unmaintained fork can fall behind Android security and Play Integrity changes, and nobody is accountable for fixes.
- Third-party mirrors are not the author. A modified APK from a mirror can contain anything. Check any download against a digest the author published, and be prepared for there not being one.
- RootForge OS only opens the manager app already installed on the target. It does not know Kitsune's package name or where to download it safely, so **Root / Patching offers Magisk, KernelSU and APatch only**. That is deliberate, not an omission.

## What to use instead

- [Magisk](guide:magisk), the official project, if you want the Magisk module ecosystem.
- [KernelSU](guide:kernelsu) or [APatch](guide:apatch) if you want root that does not rely on Magisk.
- [Magisk vs KernelSU vs APatch](guide:framework-comparison) to choose.

If you still want Kitsune, patch with its own manager on the target, then bring the patched image back with Flash Tools and flash it like any other (the device-match manifest and typed confirmation still apply). RootForge does not vouch for the image.
