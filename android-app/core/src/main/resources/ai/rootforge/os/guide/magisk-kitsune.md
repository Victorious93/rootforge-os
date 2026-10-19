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
- RootForge has **no trusted download link** for it and will not point you at a mirror.

## What RootForge does with it [UNSUPPORTED]

Kitsune Mask is listed in **Root / Patching** and in the module installer, marked `[UNSUPPORTED]` with a warning, because you asked for it. The package name (`io.github.huskydg.magisk`) was supplied by the owner of this app, and RootForge cannot verify it.

- It can detect Kitsune on the target, open it, and push a boot image for you to patch in Kitsune's own screen. Patching is done by Kitsune, not by RootForge.
- It looks for the patched image as `magisk_patched*.img` in Download and installs modules with the `magisk` command. Both are **assumptions** because Kitsune is a Magisk fork; neither is verified for this fork. If no file is found, patch again and check the name Kitsune used.
- Flashing the result goes through the same device-match manifest and typed confirmation as every other image. Use **Boot once** before any permanent write.
- Install the Kitsune APK yourself (Root / Patching step 2 can push an APK you import). RootForge does not download it.

## What to use instead

- [Magisk](guide:magisk), the official project, if you want the Magisk module ecosystem.
- [KernelSU](guide:kernelsu) or [APatch](guide:apatch) if you want root that does not rely on Magisk.
- [Magisk vs KernelSU vs APatch](guide:framework-comparison) to choose.

RootForge does not vouch for any image produced by Kitsune.
