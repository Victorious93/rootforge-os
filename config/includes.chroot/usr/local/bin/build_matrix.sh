#!/usr/bin/env bash
# RootForge OS — NDK/API version matrix build runner
# Victorious Framework
#
# Builds a module or kernel project against every {NDK, API level}
# combination in MATRIX, inside an isolated Docker container per
# combination, and writes a pass/fail table. Catches "works on my NDK"
# breakage before a user hits it on a different toolchain version.
#
# Usage: ./build_matrix.sh --project-dir <path> --build-cmd "<command>" [--matrix-file matrix.tsv]
#
# matrix.tsv format (tab-separated, one combo per line): NDK_VERSION<TAB>API_LEVEL
# If no --matrix-file is given, a small built-in default matrix is used.

set -euo pipefail

PROJECT_DIR=""
BUILD_CMD=""
MATRIX_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project-dir) PROJECT_DIR="$2"; shift ;;
    --build-cmd) BUILD_CMD="$2"; shift ;;
    --matrix-file) MATRIX_FILE="$2"; shift ;;
  esac
  shift
done

[[ -n "$PROJECT_DIR" && -d "$PROJECT_DIR" ]] || { echo "Usage: build_matrix.sh --project-dir <path> --build-cmd \"<cmd>\" [--matrix-file matrix.tsv]" >&2; exit 1; }
[[ -n "$BUILD_CMD" ]] || { echo "--build-cmd is required, e.g. --build-cmd './gradlew assembleRelease' or --build-cmd './build.sh'" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || { echo "docker not found — apt install docker.io, or re-run bootstrap." >&2; exit 1; }

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
LOG_DIR="$ROOTFORGE_HOME/logs"
STAMP="$(date +%Y%m%d_%H%M%S)"
mkdir -p "$LOG_DIR"
REPORT="$LOG_DIR/build-matrix-${STAMP}.md"
# Installed layout ships this script at /usr/local/bin/build_matrix.sh with
# its Dockerfile baked at /opt/rootforge/docker/ — that's the only path a
# real ISO install ever reaches. The second entry is for running straight
# out of a repo checkout (config/includes.chroot/usr/local/bin/build_matrix.sh
# -> ../../../opt/rootforge/docker/), which is the only other place this
# script is actually run from during development.
DOCKERFILE="/opt/rootforge/docker/Dockerfile.ndk-matrix"
[[ -f "$DOCKERFILE" ]] || DOCKERFILE="$(dirname "$0")/../../../opt/rootforge/docker/Dockerfile.ndk-matrix"
[[ -f "$DOCKERFILE" ]] || { echo "Dockerfile.ndk-matrix not found — pass its location or check your RootForge install." >&2; exit 1; }

if [[ -n "$MATRIX_FILE" && -f "$MATRIX_FILE" ]]; then
  mapfile -t COMBOS < "$MATRIX_FILE"
else
  # Default matrix: two recent LTS-ish NDKs against three common API levels —
  # covers the versions Magisk/KernelSU native builds most commonly break on.
  COMBOS=(
    "25.2.9519653	31"
    "25.2.9519653	33"
    "26.1.10909125	31"
    "26.1.10909125	33"
    "26.1.10909125	34"
  )
fi

echo "# RootForge build matrix — $STAMP" > "$REPORT"
echo "" >> "$REPORT"
echo "Project: \`$PROJECT_DIR\`  " >> "$REPORT"
echo "Build command: \`$BUILD_CMD\`" >> "$REPORT"
echo "" >> "$REPORT"
echo "| NDK version | API level | Result | Log |" >> "$REPORT"
echo "|---|---|---|---|" >> "$REPORT"

for combo in "${COMBOS[@]}"; do
  NDK_VER="$(echo "$combo" | cut -f1)"
  API="$(echo "$combo" | cut -f2)"
  TAG="rootforge-matrix:ndk${NDK_VER}-api${API}"
  BUILD_LOG="$LOG_DIR/matrix_${STAMP}_ndk${NDK_VER}_api${API}.log"

  echo ""
  echo "=== NDK $NDK_VER / API $API ==="

  if docker build --build-arg NDK_VERSION="$NDK_VER" --build-arg API_LEVEL="$API" \
      -t "$TAG" -f "$DOCKERFILE" "$(dirname "$DOCKERFILE")" > "$BUILD_LOG" 2>&1; then
    if docker run --rm -v "$(realpath "$PROJECT_DIR"):/project" "$TAG" bash -lc "$BUILD_CMD" >> "$BUILD_LOG" 2>&1; then
      echo "PASS"
      echo "| $NDK_VER | $API | PASS | \`$BUILD_LOG\` |" >> "$REPORT"
    else
      echo "FAIL (build command)"
      echo "| $NDK_VER | $API | FAIL (build) | \`$BUILD_LOG\` |" >> "$REPORT"
    fi
  else
    echo "FAIL (image build — toolchain fetch problem)"
    echo "| $NDK_VER | $API | FAIL (toolchain) | \`$BUILD_LOG\` |" >> "$REPORT"
  fi
done

echo ""
echo "Matrix report: $REPORT"
cat "$REPORT"

# Victorious Framework
