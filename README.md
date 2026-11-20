<a href="https://github.com/sponsors/gi-os"><img src="https://raw.githubusercontent.com/gi-os/gi-os/main/images/bright-sponsors.svg" width="100%" alt="Thank you to my sponsors"></a>

# BrightImport

[**⬇ Download the latest APK**](https://github.com/gi-os/BrightImport/releases/latest) · free, open source.

Pull photos off a camera onto the Light Phone III, over the camera's own Wi-Fi, and into the roll.

Imports land in `DCIM/BrightImport`, which is inside the folder [Roll](https://github.com/gi-os/Roll)
reads — so a frame off the X-Pro3 appears in the roll above the viewfinder next to the ones the
phone took, with no shared database between the two apps.

## Cameras

| Body | Transport | Status |
|---|---|---|
| Fujifilm X-Pro3 (and most X series) | Fuji's forked PTP/IP on TCP 55740 | working |
| Ricoh GR III / GR IIIx | HTTP REST on `192.168.0.1/v1/photos` | planned |
| Sony RX100 VI | SSDP discovery + SOAP ContentDirectory | planned |

## Using it

1. Put the camera into transfer mode. On a Fuji that is the playback menu → wireless
   communication; the exact wording moves around by body.
2. Open BrightImport, pick the make. Android shows a dialog listing matching access points —
   choose the camera.
3. Press OK on the camera. Twice, on newer bodies: once to admit the client, once to accept
   the mode it asked for.
4. Tap the thumbnails you want, choose JPEG / RAW / VIDEO, import.

Re-running an import skips anything already in the roll, so it is cheap to connect and grab
whatever is new.

## Why it does not ask for location permission

Every other camera app on Android wants "all the time" location, because scanning for access
points requires it. BrightImport joins the camera through
[`WifiNetworkSpecifier`](https://developer.android.com/reference/android/net/WifiNetworkSpecifier)
instead: the system shows the picker, the user chooses, and this app never sees a scan result.
CI fails the build if a location permission ever appears in the manifest.

The other half of that layer matters just as much. A camera network has no internet, so Android
keeps routing through LTE and every socket this app opens goes to the wrong place —
`bindProcessToNetwork` is what pins them to the camera. It is the most common bug in apps of this
kind and it presents as a camera that pairs and then refuses to transfer.

## The Fuji protocol

Fuji does not document this. What is implemented here is a Kotlin port of the state machine in
[petabyt/fudge](https://github.com/petabyt/fudge)'s `lib/fuji.c`, with the PTP layer following
[petabyt/libpict](https://github.com/petabyt/libpict) — both Apache-2.0, and between them the only
public description of how these cameras actually behave. See `NOTICE`.

Four things about it are worth knowing before touching `FujiSession.kt`:

- **It is not PTP/IP.** After one non-standard handshake, every packet is a USB-style bulk
  container over TCP — 12-byte header, 16-bit type and code. Fuji forked their MTP stack and
  pointed it at a socket.
- **The setup order is load-bearing.** `configInitMode` must run before `configVersion`. Out of
  order it does not fail; it silently corrupts the packets of every file operation after it.
- **`GetObjectHandles` does not work over Wi-Fi.** The handles are `1..n` against a count that
  only ever arrives in an event. Asking properly returns an empty list, which reads as an empty
  card.
- **Sizes lie by default.** Every file reports 100 kB until `EnableCorrectFileSize` is set, and a
  download that trusts the default writes a truncated JPEG.

## Building

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` tests and builds
locally. The debug APK uses Android's debug key and cannot upgrade a release install.
The local release APK is unsigned; branch and PR checks test signing with disposable keys
and do not publish. A push to `main` (except Markdown-only changes) or a manual run on
`main` publishes a GitHub release when the release workflow is enabled.

### Rotate the release signing key before merging

1. Disable [Build & Release APK](https://github.com/gi-os/BrightImport/actions/workflows/build.yml)
   before merging to `main`: `gh workflow disable build.yml --repo gi-os/BrightImport`.
2. In a private directory outside the checkout, use `apksigner` from Android Build Tools
   35.0.0 to create the lineage. Use fresh passwords when prompted. The old key is needed
   offline for this step only; never upload it to CI.

   ```sh
   keytool -genkeypair -storetype JKS -keystore brightimport-new.jks -alias brightimport-release \
     -keyalg RSA -keysize 3072 -validity 10000

   apksigner rotate --out brightimport.lineage \
     --old-signer --ks /path/to/original/brightimport.jks --ks-key-alias brightimport \
     --set-installed-data true --set-rollback false \
     --new-signer --ks brightimport-new.jks --ks-key-alias brightimport-release

   apksigner lineage --in brightimport.lineage --print-certs
   ```

3. Verify that signer #1's SHA-256 digest matches `signing-lineage-root-fingerprint.txt`.
   Set `signing-fingerprint.txt` to signer #2's SHA-256 digest in the signing PR.
   Leave the original pin unchanged. The release workflow refuses to publish until the pins
   are distinct and match the signed APK's lineage.
4. In [Settings → Environments](https://github.com/gi-os/BrightImport/settings/environments),
   create an environment named `release`. Under **Deployment branches and tags**, choose
   **Selected branches and tags**, and add one **Branch** rule for `main` only.
   Do not add any tag rules or put these secrets at repository or organization scope.
   Populate the five environment secrets using the following commands on Linux. Enter the
   new keystore password, `brightimport-release`, and the new key password when prompted,
   in that order:

   ```sh
   base64 -w 0 brightimport-new.jks | gh secret set RELEASE_KEYSTORE_BASE64 --env release --repo gi-os/BrightImport
   base64 -w 0 brightimport.lineage | gh secret set SIGNING_LINEAGE_BASE64 --env release --repo gi-os/BrightImport
   gh secret set KEYSTORE_PASSWORD --env release --repo gi-os/BrightImport
   gh secret set KEY_ALIAS --env release --repo gi-os/BrightImport
   gh secret set KEY_PASSWORD --env release --repo gi-os/BrightImport
   ```

5. With publishing disabled, merge the signing PR into `main`, then sync `main` into
   the default branch `init` so it also stops shipping the exposed key and password.
   Preserve the `init` README content when syncing. The old key remains compromised
   even after the files are removed.
6. Coordinate any BrightMarket catalog or updater pins before re-enabling the workflow
   for an authorized release. BrightImport requires API 29, so v3 key rotation supports
   in-place updates on all supported Android versions, but an updater that only trusts
   the original fingerprint can still reject the rotated APK.

## Licence

MIT, except the Light SDK design language in `ui/theme` (MIT, © The Light Phone, see
`LICENSE-light-sdk`) and the protocol work credited in `NOTICE`.

<!-- bright-footer:begin -->
---

## Bright\*

**It's not Light, it's Bright.**

26 open-source apps for the **Light Phone III** — camera, music, maps, messages,
reading, transit, games. The phone has no app store, so they install by sideload: scan one
code from **[brightmarket.gzl.dev](https://brightmarket.gzl.dev)** and BrightMarket keeps them updated.

[Roll](https://github.com/gi-os/Roll) · [BrightNotebook](https://github.com/gi-os/BrightNotebook) · [BrightControl](https://github.com/gi-os/BrightControl) · [BrightWay](https://github.com/gi-os/BrightWay) · [BrightChat](https://github.com/gi-os/BrightChat) · [browse all 26 →](https://brightmarket.gzl.dev)

The Light Phone does not sponsor or endorse any of these. Built by
[Giovanni Lupo](https://github.com/gi-os) — if this one is useful to you, a ⭐ helps the next
person find it.
<!-- bright-footer:end -->

## Support

These apps are free, open, and built on my own time. Sponsorship pays the bills that don't go away: build servers, test hardware, and the crash reporter that keeps them shipping. Donation or not my code is always free for the world to use.

[Sponsor on GitHub](https://github.com/sponsors/gi-os)
