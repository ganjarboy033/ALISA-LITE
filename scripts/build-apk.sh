#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/dist"
if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle tidak ditemukan. Pasang Gradle 8.9 atau jalankan workflow GitHub Actions." >&2
  exit 1
fi
gradle -p "$ROOT/android" assembleRelease
APK="$ROOT/android/app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK" ]; then
  echo "APK release tidak ditemukan: $APK" >&2
  exit 1
fi
cp "$APK" "$ROOT/dist/alisa-lite.apk"
echo "Build selesai: $ROOT/dist/alisa-lite.apk"
