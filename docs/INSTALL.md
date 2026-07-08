# Install zync

Zync has one Android app, which owns the local database and captures, and
optional desktop clients that pair with the phone over the local network. Install
Android first, then install any desktop clients you want to pair.

> **Note (2026-07-08):** this covers the **shipped v0.2** app (phone-hosted,
> LAN-paired desktop). Distribution and the desktop client will change as zync moves
> to a central server — see the 2026-07-08 architecture specs under
> `docs/superpowers/specs/`.

Release assets are attached to GitHub Releases:

- Android: `zync-<version>.apk`
- macOS: `zync-<version>-macos-aarch64.dmg` or `zync-<version>-macos-x64.dmg`
- Windows: `zync-<version>-windows-x64-setup.exe` or `zync-<version>-windows-x64.msi`
- Linux: `zync-<version>-linux-x64.AppImage` or `zync-<version>-linux-x64.deb`

Release tags should use `v<version>` format, for example `v1.0`. The published
asset names omit the leading `v`.

## Android

### One-Time Install

1. Open the latest GitHub Release on the Android phone.
2. Download the `zync-<version>.apk` asset.
3. Open the APK and allow installation from the browser or file manager when
   Android asks.

The APK must be signed by the same release keystore for every version. If the
signing key changes, Android will not install the new APK over the old one.

### Auto-Update With Obtainium

1. Install Obtainium from its official release channel.
2. Add `https://github.com/njreid/zync` as a new app source.
3. Select GitHub Releases as the update source.
4. Choose the `zync-<version>.apk` release asset.

After that, Obtainium can watch Releases and offer updates when a new signed APK
is published.

## macOS

### Homebrew

This repository includes a Homebrew cask. Because the repo is not named
`homebrew-zync`, tap it with the explicit URL:

```sh
brew tap njreid/zync https://github.com/njreid/zync
brew install --cask njreid/zync/zync
```

To update later:

```sh
brew update
brew upgrade --cask njreid/zync/zync
```

To uninstall:

```sh
brew uninstall --cask njreid/zync/zync
```

### Direct DMG Install

1. Open the latest GitHub Release on the Mac.
2. Download the matching DMG:
   - Apple Silicon: `zync-<version>-macos-aarch64.dmg`
   - Intel: `zync-<version>-macos-x64.dmg`
3. Open the DMG and drag `zync.app` into Applications.

## Windows

1. Open the latest GitHub Release on the Windows PC.
2. Download `zync-<version>-windows-x64-setup.exe`.
3. Run the installer.

The `zync-<version>-windows-x64.msi` asset is also published for managed or
scripted installs.

## Linux

### AppImage

1. Download `zync-<version>-linux-x64.AppImage` from the latest GitHub Release.
2. Mark it executable and run it:

```sh
chmod +x zync-<version>-linux-x64.AppImage
./zync-<version>-linux-x64.AppImage
```

### Debian/Ubuntu

Download `zync-<version>-linux-x64.deb`, then install it:

```sh
sudo apt install ./zync-<version>-linux-x64.deb
```

## Pair a Desktop Client

1. Open zync on Android and keep the phone on the same local network as the
   desktop.
2. Open the desktop client.
3. Use the desktop pairing flow and confirm the pairing prompt on the phone.

The desktop app does not store the source-of-truth database. It discovers the
paired phone on the LAN and loads the phone's web app through a pinned local
proxy.

## Release Keystore Setup

Create the real Android release keystore outside the repository:

```sh
keytool -genkeypair \
  -v \
  -keystore zync-release.jks \
  -alias zync-release \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

For local Android release builds, copy `key.properties.example` to
`key.properties` and point `storeFile` at the keystore. Both `key.properties`
and keystore files are ignored by git.

For GitHub Actions, configure these repository secrets:

- `ZYNC_RELEASE_KEYSTORE_BASE64`: base64-encoded `zync-release.jks`
- `ZYNC_KEYSTORE_PASSWORD`
- `ZYNC_KEY_ALIAS`
- `ZYNC_KEY_PASSWORD`

The release workflow builds the Android APK and desktop installers, then
attaches them to the GitHub Release.

## Versioning

The release workflow uses the release tag without its leading `v` as the public
version name for Android and desktop artifact names.

`versionCode` defaults to the GitHub Actions run number for Android, which is
monotonic for this repository. Manual workflow runs can override both values.

Every public Android release must use a higher `versionCode` than the previous
release or Android will refuse the update.

When publishing a new release, update `Casks/zync.rb` to the same version as the
GitHub Release tag so Homebrew installs the new DMG.

## Alternatives

F-Droid repositories, Accrescent, the Microsoft Store, and Linux package
repositories are possible future distribution channels. They require more setup
than GitHub Releases plus Homebrew/Obtainium and are not implemented yet.
