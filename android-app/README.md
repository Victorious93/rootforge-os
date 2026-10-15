# RootForge OS — toolkit (Android APK + desktop)

Material 3 GUI. On a **phone**, this module's `:app` is the host and a second
phone on a USB-C cable is the target (USB Host API, not Termux). On **Linux
or Windows**, `:desktop` is the same screens talking to host `adb`/`fastboot`.
Shared rules live in `:core`.

```bash
./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest
# APK: app/build/outputs/apk/debug/app-debug.apk

./gradlew :desktop:run                          # GUI
./gradlew :desktop:packageDeb                   # Linux .deb
./gradlew :desktop:packageExe                   # Windows .exe (on Windows)
```

Phone install and USB stack: [`docs/ANDROID_APP.md`](../docs/ANDROID_APP.md).
Linux/Windows/Docker: [`docs/DESKTOP.md`](../docs/DESKTOP.md).
droidcommand-ai bridge: [`docs/DROIDCOMMAND_INTEGRATION.md`](../docs/DROIDCOMMAND_INTEGRATION.md).
Screenshots: [`screenshots/`](screenshots/).

## License

`:core` is Apache License 2.0 ([core/LICENSE](core/LICENSE)). `:app`,
`:desktop`, and the rest of this tree follow the repository
GPL-3.0-or-later ([../LICENSE](../LICENSE)). Third-party dependencies
keep their own licenses. See [../NOTICE](../NOTICE).
