# Release preparation

The current 0.6.0 development-cycle APK is a development artifact, not a stable release. This document defines the next alpha distribution process; no production signing key or signed release is provided by the repository.

## Version and artifacts

- `version.properties` owns `productVersion` and `buildNumber`. The present milestone remains **0.6.0** across feature branches; a feature merge alone does not advance the minor version. Current test artifact: **0.6.0-dev.7**, Android version code **7**.
- Increment `buildNumber` before distributing a new test build, not for every commit, retry or CI job. Development fixes within an undistributed build can share the number; identify individual CI artifacts by commit and SHA-256. Never reuse a published build number for different test-delivery content. Historical 0.1–0.6 names are retained; no history or tags are rewritten.
- Advance the product version only when defining a new product milestone and its acceptance scope. Alpha/beta/stable naming describes readiness and needs an explicit release decision; automated builds do not create a release. Project schema (currently v5) evolves independently when persistent semantics change.
- Every APK embeds `assets/build-info.json` with version name/code and full source commit, and displays these under About. Local changes append `-dirty`; source archives without Git use `unknown` unless the builder supplies `-PsourceRevision=<40-character-commit>`. Distribute a reviewed, clean commit; do not label a dirty or unknown build as an exact source release.
- Run `python3 scripts/apk-provenance.py path/to/app.apk` to produce `.provenance.json` and `.apk.sha256` sidecars. These read the embedded identity; retain them with the APK, matching source and signing-certificate evidence.
- CI debug APKs expire after seven days and may use different debug signing identities on different runners. They are for checking a commit, not a guaranteed in-place update channel.
- For an alpha, retain a tagged source commit, APK, SHA-256 checksum, build/toolchain versions, dependency notices and release notes in a GitHub prerelease. Provide the exact corresponding source and portable build instructions with the binary.
- Release notes must describe supported input bounds, experimental project version, tested Android environments, untested phone performance, known limitations and project backup steps. Do not advertise untested Windows functionality.

## Signing and upgrade check

The maintainer controls and backs up the signing key; keep its keystore and passwords outside Git. Choose the signing identity and recovery policy before distributing the first alpha. Follow [Android app signing](https://developer.android.com/studio/publish/app-signing); do not assume a new release key can replace an existing debug installation in place.

A release build can be prepared with `./gradlew :app:assembleRelease`; this repository has no release signing configuration, so that output must not be presented as an installable signed release. Signing is a separate controlled step using the maintainer's key or an explicitly configured CI secret. Never generate or commit a shared production key as part of an ordinary build.

Before publishing an update, install the previous signed alpha on a dedicated device/emulator, import and edit a fixture, then install the new APK **without uninstalling**. Verify the signing certificate, version code, retained project list, original bytes, geometry, region data and exported pixels. Separately reopen exported v1/v2/v3/v4/v5 fixtures. If migration fails, stop distribution and retain the last working release; reinstalling and losing app data is not a passing upgrade test.

Physical-device testing and user acceptance remain release gates for device/performance claims. Drafting this process does not mark signing, upgrade testing or release publication complete.
