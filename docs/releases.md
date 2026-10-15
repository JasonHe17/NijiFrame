# Release preparation

The current 0.3.0-dev APK is a development artifact, not a stable release. This document defines the next alpha distribution process; no production signing key or signed release is provided by the repository.

## Version and artifacts

- `app/build.gradle` owns the version name and monotonically increasing version code. A public alpha should use an explicit name such as `0.3.0-alpha.1` and a new version code; do not reuse a code for different release content.
- CI debug APKs expire after seven days and may use different debug signing identities on different runners. They are for checking a commit, not a guaranteed in-place update channel.
- For an alpha, retain a tagged source commit, APK, SHA-256 checksum, build/toolchain versions, dependency notices and release notes in a GitHub prerelease. Provide the exact corresponding source and portable build instructions with the binary.
- Release notes must describe supported input bounds, experimental project version, tested Android environments, untested phone performance, known limitations and project backup steps. Do not advertise untested Windows functionality.

## Signing and upgrade check

The maintainer controls and backs up the signing key; keep its keystore and passwords outside Git. Choose the signing identity and recovery policy before distributing the first alpha. Follow [Android app signing](https://developer.android.com/studio/publish/app-signing); do not assume a new release key can replace an existing debug installation in place.

A release build can be prepared with `./gradlew :app:assembleRelease`; this repository has no release signing configuration, so that output must not be presented as an installable signed release. Signing is a separate controlled step using the maintainer's key or an explicitly configured CI secret. Never generate or commit a shared production key as part of an ordinary build.

Before publishing an update, install the previous signed alpha on a dedicated device/emulator, import and edit a fixture, then install the new APK **without uninstalling**. Verify the signing certificate, version code, retained project list, original bytes, geometry, region data and exported pixels. Separately reopen exported v1/v2/v3 fixtures. If migration fails, stop distribution and retain the last working release; reinstalling and losing app data is not a passing upgrade test.

Physical-device testing and user acceptance remain release gates for device/performance claims. Drafting this process does not mark signing, upgrade testing or release publication complete.
