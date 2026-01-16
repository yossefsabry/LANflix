# F-Droid submission

This repository is set up for F-Droid builds via Gradle. F-Droid builds from a
tagged commit.

## Build requirements
- JDK 17
- Android SDK 34 (compileSdk/targetSdk)
- Gradle wrapper (included)

## Local build
Run a release build from the repo root:

```sh
./gradlew clean assembleRelease
```

## Metadata template
The template lives at `fdroid/metadata/com.thiyagu.media_server.yml`. Copy it
into your fork of `fdroiddata` under `metadata/` and adjust:

- `Builds` entry for each release (versionName, versionCode, commit tag)
- `CurrentVersion` and `CurrentVersionCode`

## Submission steps (overview)
1. Tag a release in this repo (see `docs/RELEASING.md`).
2. Fork `https://gitlab.com/fdroid/fdroiddata`, add the metadata file, and
   open a merge request.
3. Respond to reviewer feedback and update the metadata if requested.

## Notes for reviewers
- No proprietary SDKs or analytics are used.
- LAN-only streaming; no external service dependencies.
