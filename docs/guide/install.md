# Install

## What you need

- A Fire TV device running Fire OS 6 or later (Android 7.1 or later). The app is built for a TV remote: everything
  works with the D-pad, Select, Back and Menu buttons.
- A [Jellyfin](https://jellyfin.org) server, version 12.0 or later, that the TV can reach. See
  [Set up your server](server-setup.md).

## Getting the app

There is no release yet: no app store listing and no download. This page will say where to get the app once the
first release is out.

When a release is published on GitHub, its notes list the SHA-256 of each APK and the fingerprint of the certificate
it is signed with. Check both before installing an APK by hand.

A copy from an app store and a copy installed by hand are signed with different keys. Android won't install one over
the other, so to switch you uninstall first, which removes the sign-in and the settings on that TV.

## Testers

Testers install builds made by CI. The steps are in
[Install a CI build on a Fire TV](https://github.com/StationBreak/stationbreaktv/blob/main/docs/testing/install-ci-apk.md).
