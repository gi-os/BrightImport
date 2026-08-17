## v1.0 — Fujifilm over Wi-Fi

First release. Pulls photos off a Fujifilm body onto the phone and into the roll.

**Cameras**

- Fujifilm X series over the camera's own access point, including the X-Pro3. Both the older
  full-access bodies and the newer ones that require remote mode are handled — the app reads the
  camera's version properties and asks for whichever client mode that body wants.
- Ricoh GR III and Sony RX100 VI are not in this build. The backend interface they will slot into
  is here; their protocols are not.

**How it connects**

- Joins the camera access point through a system picker. **No location permission** — not reduced,
  not optional at runtime, absent from the manifest, and CI fails the build if one appears.
- Pins this app's sockets to the camera network with `bindProcessToNetwork`, so a phone with LTE
  up does not quietly send every request to the wrong network. This is the failure everyone hits:
  the camera pairs, then nothing transfers.
- Hands the network back when the screen is left. A process left bound to a sleeping camera has no
  working network at all.

**Importing**

- Thumbnail grid off the camera, tap to select, JPEG / RAW / VIDEO filters.
- Files land in `DCIM/BrightImport`, so they show up in Roll's camera roll and in every other gallery
  on the phone, at the time the camera says the shot was taken rather than at import time.
- Anything already imported is skipped by filename, so reconnecting to grab what's new is cheap.
- A cancelled or failed transfer deletes its partial file instead of leaving a truncated JPEG that
  would look imported and be skipped forever after.

**Protocol notes**

Ported from petabyt's `fuji.c` (Apache-2.0, credited in NOTICE) rather than reverse-engineered
again. The parts that bite:

- Chunked at exactly 1 MB per `GetPartialObject`. Larger reads work on most bodies most of the
  time and then stall permanently on one image.
- `GetThumb` blocks forever on newer bodies unless `GetObjectInfo` was called for the same handle
  first — it does not error, the socket just goes quiet.
- The event list is polled as a keepalive, not only for events: too many object operations without
  one and the camera stops answering.
- The session closes with Fuji's own 8-byte goodbye. Without it the camera holds the session open
  and refuses the next connection until it is power-cycled, which looks like an app that only
  works once.

**Verified**

17 JVM unit tests over the packet framing, transaction handling and `ObjectInfo` parsing. Not yet
tested against a physical camera.
