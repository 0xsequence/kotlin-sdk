#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture_dir="$repo_root/compatibility-tests/expo"
release_version="$(sed -n 's/^POM_VERSION_NAME=//p' "$repo_root/gradle.properties")"
sdk_version="${release_version}-expo-compat"
locked_expo_version="$(node -p "require('$fixture_dir/package-lock.json').packages['node_modules/expo'].version")"
latest_expo_version="$(npm view expo dist-tags.latest)"

if [[ "$locked_expo_version" != "$latest_expo_version" ]]; then
  echo "Expo compatibility fixture is stale: locked=$locked_expo_version latest=$latest_expo_version" >&2
  echo "Update it with: cd compatibility-tests/expo && npm install --save-exact expo@latest && npx expo install --fix" >&2
  exit 1
fi

"$repo_root/gradlew" -p "$repo_root" \
  -PPOM_VERSION_NAME="$sdk_version" \
  publishToMavenLocal

npm ci --prefix "$fixture_dir"

(
  cd "$fixture_dir"
  export OMS_KOTLIN_SDK_VERSION="$sdk_version"
  export NODE_ENV=production
  npx expo prebuild \
    --platform android \
    --clean \
    --no-install
  ./android/gradlew -p android --refresh-dependencies app:assembleDebug
)
