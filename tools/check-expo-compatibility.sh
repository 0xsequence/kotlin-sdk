#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture_dir="$repo_root/compatibility-tests/expo"
min_fixture_dir="$repo_root/compatibility-tests/expo-min"
# The SDK 56 template; expo 56.0.15+ bundles the SDK 57 template instead.
min_prebuild_template="expo-template-bare-minimum@56.0.37"
release_version="$(sed -n 's/^POM_VERSION_NAME=//p' "$repo_root/gradle.properties")"
sdk_version="${release_version}-expo-compat"
locked_expo_version="$(node -p "require('$fixture_dir/package-lock.json').packages['node_modules/expo'].version")"
latest_expo_version="$(npm view expo dist-tags.latest)"

# Only the current-stable fixture tracks npm's latest tag; the minimum fixture
# mirrors the React Native SDK's supported minimum and is never freshness-checked.
if [[ "$locked_expo_version" != "$latest_expo_version" ]]; then
  echo "Expo compatibility fixture is stale: locked=$locked_expo_version latest=$latest_expo_version" >&2
  echo "Update it with: cd compatibility-tests/expo && npm install --save-exact expo@latest && npx expo install --fix" >&2
  exit 1
fi

"$repo_root/gradlew" -p "$repo_root" \
  -PPOM_VERSION_NAME="$sdk_version" \
  publishToMavenLocal

# Usage: build_fixture <label> <fixture dir> [extra expo prebuild args...]
build_fixture() {
  local label="$1"
  local dir="$2"
  shift 2
  local fixture_expo_version
  local started_at="$SECONDS"
  fixture_expo_version="$(node -p "require('$dir/package-lock.json').packages['node_modules/expo'].version")"

  echo "::group::Expo compatibility: $label (expo $fixture_expo_version)"
  npm ci --prefix "$dir"
  (
    cd "$dir"
    export OMS_KOTLIN_SDK_VERSION="$sdk_version"
    export NODE_ENV=production
    npx expo prebuild \
      --platform android \
      --clean \
      --no-install \
      "$@"
    ./android/gradlew -p android --refresh-dependencies app:assembleDebug
  )
  echo "::endgroup::"
  echo "Expo compatibility: $label (expo $fixture_expo_version) passed in $((SECONDS - started_at))s"
}

build_fixture "current stable" "$fixture_dir"
build_fixture "supported minimum" "$min_fixture_dir" --template "$min_prebuild_template"
