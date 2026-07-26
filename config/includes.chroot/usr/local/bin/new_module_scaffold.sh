#!/usr/bin/env bash
# RootForge OS — module scaffold generator
# Victorious Framework
#
# Generates a Magisk-, KernelSU-, or LSPosed/Xposed-target module skeleton.
# Magisk/KernelSU targets get correct META-INF boilerplate, module.prop, and
# lifecycle scripts pre-stubbed. The xposed target generates a minimal Gradle
# Android app project instead, since LSPosed modules are hook classes inside
# a real APK, not a Magisk-style install zip.
#
# Usage: ./new_module_scaffold.sh <module_id> <display_name> [magisk|kernelsu|xposed]

set -euo pipefail

MODULE_ID="${1:?Usage: new_module_scaffold.sh <module_id> <display_name> [magisk|kernelsu|xposed]}"
DISPLAY_NAME="${2:?Missing display name}"
TARGET="${3:-magisk}"

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
MODULE_DIR="$ROOTFORGE_HOME/modules/$MODULE_ID"

if [[ -d "$MODULE_DIR" ]]; then
  echo "Module directory already exists: $MODULE_DIR" >&2
  exit 1
fi

if [[ "$TARGET" == "xposed" ]]; then
  # LSPosed/Xposed modules are Android app projects (a hook class + manifest
  # metadata), not a Magisk-style zip — scaffold a minimal Gradle project instead.
  mkdir -p "$MODULE_DIR"/app/src/main/{java/com/victorious/"$(echo "$MODULE_ID" | tr -cd 'a-zA-Z0-9')",assets,res/values}

  PKG="com.victorious.$(echo "$MODULE_ID" | tr -cd 'a-zA-Z0-9')"
  PKG_PATH="${PKG//./\/}"
  mkdir -p "$MODULE_DIR/app/src/main/java/$PKG_PATH"

  cat > "$MODULE_DIR/settings.gradle" <<EOF
rootProject.name = "$MODULE_ID"
include ':app'
EOF

  cat > "$MODULE_DIR/build.gradle" <<'EOF'
// RootForge OS — top-level build file
// Victorious Framework
buildscript {
    repositories { google(); mavenCentral() }
    dependencies { classpath 'com.android.tools.build:gradle:8.2.0' }
}
allprojects { repositories { google(); mavenCentral() } }
EOF

  cat > "$MODULE_DIR/app/build.gradle" <<EOF
// RootForge OS — LSPosed/Xposed module: $DISPLAY_NAME
// Victorious Framework
plugins { id 'com.android.application' }

android {
    namespace '$PKG'
    compileSdk 34
    defaultConfig {
        applicationId '$PKG'
        minSdk 27
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }
}

dependencies {
    compileOnly 'de.robv.android.xposed:api:82'
}
EOF

  cat > "$MODULE_DIR/app/src/main/AndroidManifest.xml" <<EOF
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="$DISPLAY_NAME">
        <meta-data android:name="xposedmodule" android:value="true" />
        <meta-data android:name="xposedminversion" android:value="82" />
        <meta-data android:name="xposeddescription" android:value="$DISPLAY_NAME — built with RootForge OS (Victorious Framework)" />
        <meta-data android:name="xposedscope" android:resource="@array/xposed_scope" />
    </application>
</manifest>
EOF

  cat > "$MODULE_DIR/app/src/main/res/values/arrays.xml" <<'EOF'
<resources>
    <!-- RootForge OS — list the target app package names this module hooks -->
    <string-array name="xposed_scope">
        <item>android</item>
    </string-array>
</resources>
EOF

  cat > "$MODULE_DIR/app/src/main/assets/xposed_init" <<EOF
$PKG.HookEntry
EOF

  cat > "$MODULE_DIR/app/src/main/java/$PKG_PATH/HookEntry.kt" <<EOF
// RootForge OS — LSPosed hook entry point
// Victorious Framework
package $PKG

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != "android") return
        // Hook logic goes here — see LSPosed's XposedBridge / XC_MethodHook docs.
    }
}
EOF

  echo "Scaffolded LSPosed/Xposed module '$MODULE_ID' (package $PKG) at $MODULE_DIR"
  echo "Edit app/src/main/res/values/arrays.xml to set your target app scope, then"
  echo "build the APK with Gradle and sideload with 'adb install'. Enable it inside"
  echo "the LSPosed manager app on-device — LSPosed modules are disabled by default"
  echo "until toggled per-app in the manager UI."
  echo ""
  echo "# Victorious Framework"
  exit 0
fi

mkdir -p "$MODULE_DIR"/{META-INF/com/google/android,system,webroot}

cat > "$MODULE_DIR/module.prop" <<EOF
id=$MODULE_ID
name=$DISPLAY_NAME
version=v1.0
versionCode=1
author=Victorious
description=$DISPLAY_NAME — built with RootForge OS (Victorious Framework)
EOF

cat > "$MODULE_DIR/META-INF/com/google/android/updater-script" <<'EOF'
#MAGISK
EOF

cat > "$MODULE_DIR/META-INF/com/google/android/update-binary" <<'EOF'
#!/sbin/sh
# RootForge OS — generated module installer shim
# Victorious Framework
umask 022
OUTFD=$2
ZIPFILE=$3
mount /data 2>/dev/null
[ -f /data/adb/magisk/util_functions.sh ] && MAGISK_VER_CODE=$(grep_prop MAGISK_VER_CODE /data/adb/magisk/util_functions.sh)
ui_print() { echo "ui_print $1" >&$OUTFD; echo -en "ui_print\n" >&$OUTFD; }
require_new_magisk() {
  ui_print "*******************************"
  ui_print " Please install Magisk v20.4+ "
  ui_print "*******************************"
  exit 1
}
[ -z "$MAGISK_VER_CODE" ] && require_new_magisk
EOF

cat > "$MODULE_DIR/customize.sh" <<EOF
# RootForge OS — customize.sh (install-time hook)
# Victorious Framework
ui_print "- Installing $DISPLAY_NAME"
# set_perm_recursive \$MODPATH/system 0 0 0755 0644
EOF

cat > "$MODULE_DIR/post-fs-data.sh" <<'EOF'
#!/system/bin/sh
# RootForge OS — post-fs-data.sh (early boot, before /data fully mounted)
# Victorious Framework
MODDIR=${0%/*}
EOF

cat > "$MODULE_DIR/service.sh" <<'EOF'
#!/system/bin/sh
# RootForge OS — service.sh (late-start daemon/hook logic)
# Victorious Framework
MODDIR=${0%/*}
EOF

cat > "$MODULE_DIR/system.prop" <<EOF
# RootForge OS — system.prop overlay
# Victorious Framework
EOF

chmod 755 "$MODULE_DIR"/post-fs-data.sh "$MODULE_DIR"/service.sh "$MODULE_DIR"/customize.sh
chmod 755 "$MODULE_DIR/META-INF/com/google/android/update-binary"

if [[ "$TARGET" == "kernelsu" ]]; then
  cat >> "$MODULE_DIR/module.prop" <<'EOF'
# NOTE: KernelSU has no native Zygisk implementation.
# If this module needs Zygisk-style process hooking under KernelSU,
# wire in the zygisksu overlay separately — see README section 3.
EOF
fi

cat > "$MODULE_DIR/webroot/index.html" <<EOF
<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>$DISPLAY_NAME</title></head>
<body style="font-family:monospace;background:#0d0d0d;color:#00ff9c;padding:2rem;">
<h1>$DISPLAY_NAME</h1>
<p>Magisk WebUI X stub — Victorious Framework / RootForge OS</p>
</body></html>
EOF

echo "Scaffolded $TARGET module '$MODULE_ID' at $MODULE_DIR"
echo "Next: edit post-fs-data.sh / service.sh / system.prop, then run build_magisk_module.sh $MODULE_ID"

# Victorious Framework
