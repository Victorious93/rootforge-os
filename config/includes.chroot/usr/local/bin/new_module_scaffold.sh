#!/usr/bin/env bash
# RootForge OS — module scaffold generator
# Victorious Framework
#
# Generates a Magisk-, KernelSU-, APatch-, standalone-Zygisk-, or
# LSPosed/Xposed-target module skeleton. Magisk/KernelSU/APatch targets get
# correct META-INF boilerplate, module.prop, and lifecycle scripts
# pre-stubbed — APatch's APM module format is deliberately designed to be
# Magisk-module-compatible, so it reuses the same scaffold. The zygisk
# target is the same scaffold plus a zygisk/ native-lib subdirectory wired
# to the header 0095-zygisk-headers.hook.chroot already vendors. The xposed
# target generates a minimal Gradle Android app project instead, since
# LSPosed modules are hook classes inside a real APK, not a Magisk-style
# install zip.
#
# Usage: ./new_module_scaffold.sh <module_id> <display_name> [magisk|kernelsu|apatch|zygisk|xposed]

set -euo pipefail

MODULE_ID="${1:?Usage: new_module_scaffold.sh <module_id> <display_name> [magisk|kernelsu|apatch|zygisk|xposed]}"
DISPLAY_NAME="${2:?Missing display name}"
TARGET="${3:-magisk}"

case "$TARGET" in
  magisk|kernelsu|apatch|zygisk|xposed) ;;
  *) echo "Unknown target '$TARGET' — expected magisk, kernelsu, apatch, zygisk, or xposed" >&2; exit 1 ;;
esac

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

if [[ "$TARGET" == "apatch" ]]; then
  cat >> "$MODULE_DIR/module.prop" <<'EOF'
# NOTE: this scaffold targets APatch's APM module format, which mirrors
# Magisk's module.prop/lifecycle-script convention by design. Install
# through the APatch app's module manager. As with KernelSU, APatch has
# no native Zygisk implementation of its own — see the zygisk scaffold
# target if this module needs Zygisk-style process hooking.
EOF
fi

if [[ "$TARGET" == "zygisk" ]]; then
  ZYGISK_HEADER="/usr/local/share/rootforge/zygisk-api/zygisk.hpp"
  ZYGISK_CMAKE_STUB="/usr/local/share/rootforge/zygisk-api/CMakeLists.zygisk.txt"
  mkdir -p "$MODULE_DIR/zygisk/jni"

  cat > "$MODULE_DIR/zygisk/jni/module.cpp" <<EOF
// RootForge OS — Zygisk module: $DISPLAY_NAME
// Victorious Framework
//
// Standard Zygisk module shape (see topjohnwu/zygisk-module-sample, the
// same reference 0095-zygisk-headers.hook.chroot vendors zygisk.hpp from).
// Build a zygisk/<abi>.so per target ABI (arm64-v8a, armeabi-v7a, x86,
// x86_64) and place each at zygisk/<abi>.so before zipping — lint_module.sh
// checks for at least one of them.
#include "zygisk.hpp"

class ${MODULE_ID//[^a-zA-Z0-9]/_}_module : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        // Hook logic before the app process specializes goes here.
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *args) override {
        // Hook logic after the app process specializes goes here.
    }

private:
    zygisk::Api *api;
    JNIEnv *env;
};

REGISTER_ZYGISK_MODULE(${MODULE_ID//[^a-zA-Z0-9]/_}_module)
EOF

  cat > "$MODULE_DIR/zygisk/jni/CMakeLists.txt" <<EOF
# RootForge OS — Zygisk module CMake build
# Victorious Framework
cmake_minimum_required(VERSION 3.22)
project($MODULE_ID)

add_library($MODULE_ID SHARED module.cpp)

if(EXISTS "$ZYGISK_CMAKE_STUB")
  set(MODULE_NAME $MODULE_ID)
  include("$ZYGISK_CMAKE_STUB")
else()
  message(WARNING "$ZYGISK_CMAKE_STUB not found — run this on a RootForge OS build where 0095-zygisk-headers.hook.chroot has run, or vendor zygisk.hpp yourself.")
endif()
EOF

  if [[ -f "$ZYGISK_HEADER" ]]; then
    cp "$ZYGISK_HEADER" "$MODULE_DIR/zygisk/jni/zygisk.hpp"
  else
    echo "NOTE: $ZYGISK_HEADER not found on this host — copy zygisk.hpp into"
    echo "      $MODULE_DIR/zygisk/jni/ yourself before building (RootForge OS's"
    echo "      own image vendors it via 0095-zygisk-headers.hook.chroot)."
  fi

  cat >> "$MODULE_DIR/module.prop" <<'EOF'
# NOTE: this is a standalone Zygisk module. Build zygisk/jni/module.cpp
# with the NDK per ABI and place the resulting .so at zygisk/<abi>.so
# (e.g. zygisk/arm64-v8a.so) before zipping. Works under Magisk natively;
# under KernelSU/APatch it additionally needs a Zygisk-API-compatible
# loader such as Zygisk Next installed on the device.
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
