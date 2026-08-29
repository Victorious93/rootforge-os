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

# Each of these takes a value; without the length check a trailing bare
# `--build-cmd` reads an unset $2 and dies with "unbound variable" under
# `set -u`. An unrecognized argument used to be skipped in silence, so a
# typo'd flag left the run using defaults instead of what was asked for.
while [[ $# -gt 0 ]]; do
  case "$1" in
    --project-dir) [[ $# -ge 2 ]] || { echo "--project-dir needs a path" >&2; exit 1; }; PROJECT_DIR="$2"; shift ;;
    --build-cmd)   [[ $# -ge 2 ]] || { echo "--build-cmd needs a command" >&2; exit 1; }; BUILD_CMD="$2"; shift ;;
    --matrix-file) [[ $# -ge 2 ]] || { echo "--matrix-file needs a path" >&2; exit 1; }; MATRIX_FILE="$2"; shift ;;
    -h|--help)
      echo "Usage: build_matrix.sh --project-dir <path> --build-cmd \"<cmd>\" [--matrix-file matrix.tsv]"
      exit 0
      ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
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

if [[ -n "$MATRIX_FILE" ]]; then
  # A --matrix-file that doesn't exist used to fall through to the built-in
  # matrix, so a typo'd path quietly tested the wrong combinations.
  [[ -f "$MATRIX_FILE" ]] || { echo "Matrix file not found: $MATRIX_FILE" >&2; exit 1; }
  # Blank lines and # comments would otherwise become combos with an empty
  # NDK version, which docker build accepts as an empty --build-arg and
  # fails on far downstream.
  mapfile -t COMBOS < <(grep -v '^[[:space:]]*\(#\|$\)' "$MATRIX_FILE")
  [[ ${#COMBOS[@]} -gt 0 ]] || { echo "No usable combinations in $MATRIX_FILE" >&2; exit 1; }
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

MATRIX_FAILURES=0
for combo in "${COMBOS[@]}"; do
  # Accept a tab or whitespace separator; a matrix file written with spaces
  # otherwise yielded the whole line as NDK_VER and again as API.
  read -r NDK_VER API _ <<< "$combo"
  if [[ -z "$NDK_VER" || -z "$API" ]]; then
    echo "Skipping malformed matrix line: '$combo' (expected: NDK_VERSION<TAB>API_LEVEL)" >&2
    MATRIX_FAILURES=$((MATRIX_FAILURES+1))
    continue
  fi
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
      MATRIX_FAILURES=$((MATRIX_FAILURES+1))
    fi
  else
    echo "FAIL (image build — toolchain fetch problem)"
    echo "| $NDK_VER | $API | FAIL (toolchain) | \`$BUILD_LOG\` |" >> "$REPORT"
    MATRIX_FAILURES=$((MATRIX_FAILURES+1))
  fi
done

echo ""
echo "Matrix report: $REPORT"
cat "$REPORT"

# The point of a build matrix is to fail when a combination breaks. This
# always exited 0, so it could not be used as a gate in CI or a pre-push
# hook — the report had to be read by eye.
if [[ $MATRIX_FAILURES -gt 0 ]]; then
  echo ""
  echo "$MATRIX_FAILURES of ${#COMBOS[@]} combination(s) failed." >&2
  exit 1
fi

# Victorious Framework
