# Android environment configuration — 7 November 2026

Android Traveler and Partner now have `development` and `production` flavors. Development uses `.dev` application IDs and separate labels; production retains the established IDs. Namespace and shared KMP modules are unchanged. Configuration and feature inventory: [environment guide](../../config/environments/README.md).

## Executed checks

| Check | Result |
| --- | --- |
| Task discovery through Gradle Wrapper `tasks --all` | PASS: `assembleDevelopmentDebug`, `assembleDevelopmentDebugAndroidTest`, `lintDevelopmentDebug` and production validation tasks exist |
| Traveler and Partner `assembleDevelopmentDebug` + `lintDevelopmentDebug` | PASS; Traveler lint: 0 errors / 42 warnings; Partner lint: 0 errors / 2 warnings |
| Traveler `assembleDevelopmentDebugAndroidTest` | PASS: instrumentation APK compiled; not an executed device test |
| Traveler `testAndroidHostTest` | PASS: 420 tests, 0 failures/errors/skips in the working tree, including pending curated-copy changes |
| Traveler + Partner `assembleProductionDebug` with explicit build-check inputs | PASS; this is a debug-signed compile check, not a production release |
| Missing production API origin, even with legacy development property supplied | PASS: expected nonzero exit at `validateProductionEnvironment` |
| HTTP production API origin | PASS: expected nonzero exit during origin validation |
| Missing Traveler or Partner release signing | PASS: each `preProductionReleaseBuild` fails at the respective signing validation |
| APK identity and generated BuildConfig inspection | PASS: both `.dev` IDs/labels, both production IDs/labels, environment-specific origin and provider identifiers |
| PowerShell env import | PASS: literal/quoted values, no expression execution, cross-environment rejection without partial mutation, duplicate rejection, production template parsing |
| Git ignores local env and Firebase client files | PASS: `git check-ignore` confirms paths are ignored |
| Production release/signing/distribution | NOT_RUN: approved production origin and real signing/provider values have not been supplied |
| Device/provider integration | NOT_RUN for this environment change; APK inspection does not prove Google/Maps/FCM/Integrity delivery or provider registration |
| iOS configuration/build | NOT_RUN: Android flavors do not configure Xcode; Mac/Xcode and host integration are required |

Development build used `FLOOPEL_DEVELOPMENT_API_ORIGIN` with the current authorized ngrok tunnel. Production debug used `https://production-build-check.invalid`, `production-build-check.apps.googleusercontent.com` and project number `123456789` solely to verify separate env binding. Those values are deliberately unusable provider/endpoint placeholders. No production-check APK was installed, and no production endpoint was contacted.

Commands executed:

```powershell
.\gradlew.bat :shared:features:traveler:testAndroidHostTest :apps:android:traveler:assembleDevelopmentDebug :apps:android:partner:assembleDevelopmentDebug :apps:android:traveler:assembleDevelopmentDebugAndroidTest :apps:android:traveler:lintDevelopmentDebug :apps:android:partner:lintDevelopmentDebug --no-parallel --max-workers=2
.\gradlew.bat :apps:android:traveler:assembleProductionDebug :apps:android:partner:assembleProductionDebug --no-parallel --max-workers=2
.\gradlew.bat :apps:android:traveler:preProductionDebugBuild -PfloopelProductionApiOrigin= -PfloopelApiOrigin=https://legacy-development-build-check.invalid --no-parallel --max-workers=2
.\gradlew.bat :apps:android:traveler:preProductionDebugBuild --no-parallel --max-workers=2
.\gradlew.bat :apps:android:traveler:preProductionReleaseBuild --no-parallel --max-workers=2
.\gradlew.bat :apps:android:partner:preProductionReleaseBuild --no-parallel --max-workers=2
```

Each negative check set its corresponding missing/HTTP input in an isolated command process and asserted the intended validation failure. Signing inputs were cleared only in that process. The Windows temporary-Java workaround in the main README was used. No source dependency/toolchain update, backend restart, database mutation, provider console change or emulator install was performed for these checks.

## Limits and evidence

Logs, expected-failure exit codes, APK identity/hash inspection, generated public config fields, helper checks and environment-source hashes are preserved in [environments-20261107](traveler-android-audit/environments-20261107). APKs were built from the working tree, which also contained pending UI/model changes and an existing uncommitted `styles.xml` edit; these checks establish environment selection and compilation, not a signed release attestation or Figma parity. The pre-existing `.env/` ignore entry and `styles.xml` edit remain outside the environment commit.

Maps remain deferred as instructed. Maps, Firebase Android/iOS client configuration, iOS OAuth/Maps and canonical public web origin have explicit **reserved / not consumed** entries. No SDK activation, push registration or iOS pipeline is implied by the templates. Google Sign-In and Play Integrity client identifiers are already consumed by the Android host; empty/zero keeps their existing unavailable state. Server credentials stay in backend/CI environments.
