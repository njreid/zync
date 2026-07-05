# Install zync

Zync is distributed as a universal signed Android APK attached to GitHub
Releases. Play Store and AAB distribution are out of scope for now.

## Install Once

1. Open the latest GitHub Release for this repository on the Android phone.
2. Download the `zync-*.apk` asset.
3. Open the APK and allow installation from the browser or file manager when
   Android asks.

The APK must be signed by the same release keystore for every version. If the
signing key changes, Android will not install the new APK over the old one.

## Auto-Update With Obtainium

1. Install Obtainium from its official release channel.
2. Add this repository URL as a new app source.
3. Select GitHub Releases as the update source.
4. Choose the `zync-*.apk` release asset.

After that, Obtainium can watch Releases and offer updates when a new signed APK
is published.

## Release Keystore Setup

Create the real release keystore outside the repository:

```sh
keytool -genkeypair \
  -v \
  -keystore zync-release.jks \
  -alias zync-release \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

For local release builds, copy `key.properties.example` to `key.properties` and
point `storeFile` at the keystore. Both `key.properties` and keystore files are
ignored by git.

For GitHub Actions, configure these repository secrets:

- `ZYNC_RELEASE_KEYSTORE_BASE64`: base64-encoded `zync-release.jks`
- `ZYNC_KEYSTORE_PASSWORD`
- `ZYNC_KEY_ALIAS`
- `ZYNC_KEY_PASSWORD`

The release workflow builds `app-release.apk`, verifies its signature with
`apksigner`, renames it to `zync-<version>.apk`, and attaches it to the GitHub
Release.

## Versioning

The workflow uses the release tag without a leading `v` as `versionName`.
`versionCode` defaults to the GitHub Actions run number, which is monotonic for
this repository. Manual workflow runs can override both values.

Every public Android release must use a higher `versionCode` than the previous
release or Android will refuse the update.

## Alternatives

F-Droid repositories and Accrescent are possible future distribution channels,
but both require more setup than GitHub Releases plus Obtainium. They are not
implemented yet.
