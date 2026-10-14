# RootForge OS — Android host app

Material 3 toolkit APK. This phone is the **host**; a second phone on a USB-C
cable is the **target**. Not Termux.

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Install, cable setup, USB stack, limits: [`docs/ANDROID_APP.md`](../docs/ANDROID_APP.md).
droidcommand-ai bridge: [`docs/DROIDCOMMAND_INTEGRATION.md`](../docs/DROIDCOMMAND_INTEGRATION.md).
Screenshots: [`screenshots/`](screenshots/).
