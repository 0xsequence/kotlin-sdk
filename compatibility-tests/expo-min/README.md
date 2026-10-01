# Expo Minimum Compatibility Fixture

Build-only fixture that compiles the locally published Kotlin SDK through the
oldest Expo SDK supported by the
[OMS Wallet React Native SDK](https://github.com/0xPolygon/oms-wallet-react-native-sdk/tree/master/compatibility-tests/expo-min),
which consumes this SDK. It mirrors that repo's minimum: Expo SDK 56
(`expo` 56.0.23, `react-native` 0.85.3, `react` 19.2.3).

`tools/check-expo-compatibility.sh` prebuilds it with
`--template expo-template-bare-minimum@56.0.37` because `expo` 56.0.15 and
later bundle the SDK 57 template, which would silently generate an Expo 57
native project. Unlike `../expo`, this fixture is never freshness-checked, and
Dependabot only proposes patch updates.

Change these pins, the lockfile, and the template pin only when the React
Native SDK deliberately raises its supported minimum.
