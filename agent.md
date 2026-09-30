# e-SOS Agent Instructions

- Treat `app/build.gradle.kts` `versionName` as the source of truth. It must be a valid `MAJOR.MINOR.PATCH` Semantic Version (no `-dev` suffix); `versionCode` must increase monotonically.
- Before every Gradle build that creates an installable APK, bump the SemVer version and increment `versionCode`. Use a patch bump for routine app changes; choose minor/major only when the change warrants it. Increment once per APK build, not once per Gradle task in the same invocation.
- Export distributable debug APKs with `./gradlew exportDebugApk`. The expected artifact is `dist/eSOS-v<versionName>.apk` (for example `dist/eSOS-v1.0.0.apk`). Do not rename it to the generic `app-debug.apk` for delivery.
- Keep the About section in the app displaying the installed package's `versionName`, so it always matches the APK filename.
