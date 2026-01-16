# Releasing LANflix

LANflix uses annotated tags for releases. F-Droid tracks tags like `v1.0`.

## Release steps
1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Update `fdroid/metadata/com.thiyagu.media_server.yml`:
   - Add a new entry under `Builds` with the new version.
   - Update `CurrentVersion` and `CurrentVersionCode`.
3. Build the release APK:

```sh
./gradlew clean assembleRelease
```

4. Tag and push:

```sh
git tag -a v1.0 -m "LANflix 1.0"
git push origin v1.0
```

5. (Optional) Create a GitHub release with notes.

## Versioning rules
- `versionCode` must increase on every release.
- `versionName` should match the tag without the leading `v`.
