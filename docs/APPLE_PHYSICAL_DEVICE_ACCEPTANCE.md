# Apple Auris physical-device acceptance

Hosted CI and live HTTP checks prove that the build and server routes exist.
They do not prove playback, lyrics, navigation, or latency on Android. Complete
this checklist on a physical device before declaring Apple Auris successful.

## Test build

Use the signed APK produced by the green **Apple Auris APK** workflow for the
candidate commit. Record:

- Git commit SHA
- GitHub Actions run ID
- APK artifact ID
- APK SHA-256
- Device model
- Android version
- App version (`3.0.41-apple.1`, version code `149`)
- Package (`com.dev.bitchord`)
- Signer SHA-256 (`fe7d54041b91fc9383ac0945d1a2dbffac608e38498a2aa7d7c7ffc4e037cd29`)

Do not install an unsigned or locally rebuilt APK. Local Android builds and
physical-device instrumentation are not part of this acceptance run.

## Fresh install and migration

1. Install the candidate APK over the previous Apple Auris build.
2. Open **Settings → Playback**.
3. Confirm **Content source** and **Recommendation algorithm** are separate.
4. Confirm recommendation choices are exactly **Apple Music**, **Spotify**,
   **YouTube Music**, and **All**.
5. Change each recommendation choice. Confirm the persisted value survives
   process death and relaunch.
6. On a migrated install without an explicit recommendation choice, confirm the
   algorithm inherits the migrated content provider.

## Apple library and catalogue

1. Confirm the public manifest retains `catalogueProvider: "apple-music"` and
   its `library` block.
2. Open Apple playlists, albums, and songs. Confirm each list loads and empty
   pagination terminates cleanly.
3. Confirm an Apple row opens the Apple catalogue edition, not a generic
   YouTube or Spotify substitute.
4. Confirm `All` discovery rows retain exact Apple catalogue IDs in Apple mode.

## Explicit recording and uncensored lyrics

Use Apple catalogue ID `1701640112` (explicit) and clean control ID
`1715484615`.

1. Play the explicit track from Apple search, library, Home, Flow, and
   Autoplay where each surface exposes it.
2. Confirm the explicit master is retained. Never accept a silent clean-edition
   substitution.
3. Confirm the UI, queue, cache entry, download metadata, and MediaItem
   metadata carry the explicit state.
4. Open lyrics for the explicit recording. Confirm Apple TTML/lyrics are
   uncensored and contain no app-generated `*` masking.
5. Confirm the clean control behaves as expected and is not used as a fallback
   for the explicit recording.
6. Listen through the full recording and across a seek. Confirm no
   content-dependent fade, mute, gain reduction, or silence occurs.
7. Confirm user crossfade, EQ, and DSP settings remain active and unchanged.

## Recommendation modes

For each **Apple Music**, **Spotify**, **YouTube Music**, and **All** setting:

1. Reload Home and confirm the selected feed builds.
2. Start and grow Flow. Confirm the queue uses the selected algorithm.
3. Enable Autoplay and request more than one batch. Confirm later batches do
   not repeat a fixed feed.
4. Use the player's **More like this** queue search. Confirm all selected feeds
   are represented rather than one feed consuming the visible limit.
5. In **All**, confirm rows are interleaved and deduplicated by exact Apple
   catalogue ID in Apple mode.
6. Switch algorithms while a Flow build is active. Confirm the final snapshot
   belongs to the latest algorithm and is not restored under a mismatch.

## Navigation and queue restoration

1. Open Search from each visible tab. Confirm Back returns to the intended
   visible tab.
2. Disable Podcasts while Search is open. Confirm Back cannot target the now
   hidden Podcasts tab.
3. With a search keyboard open, confirm Back priority is overlay/detail, then
   keyboard, then visual Search.
4. Play a track, queue several tracks, rotate the device, and relaunch.
   Confirm queue order, selected track, Apple catalogue ID, explicit state,
   content rating, and ISRC survive restoration.
5. Like and Flow-expand an explicit Apple track. Confirm explicit state remains
   distinguishable from unknown rating after relaunch.

## Cache, download, and quota intent

1. Play an Apple track once, then seek and replay it. Confirm the valid warm
   cache is used and asynchronous validation does not block playback.
2. Download an explicit Apple track. Confirm explicit metadata and ISRC are
   saved and restored.
3. Play normal Apple media and request ranges. Confirm normal playback never
   carries download-quota intent.
4. Confirm only an explicit download request carries download intent.
5. Confirm direct `content://` and `file://` rows remain playable and are not
   replaced by an Apple download.

## Playback and performance

Measure on the same device and network before and after the candidate build.
Keep the same track, quality, and starting position.

| Metric | Before | Candidate | Acceptance |
|---|---:|---:|---|
| Time to first byte (TTFB) | | | unchanged or better |
| Seek-to-audio latency | | | unchanged or better |
| Start latency | | | unchanged or better |

Also record:

- Audio format and Apple catalogue ID
- Buffering events during first play and after seek
- Track transition into the next queue item
- Crossfade/EQ/DSP behavior across the transition

Do not change `START_PLAYBACK_MS`, buffer policy, or decoder path without
measured evidence and an explicit before/after report.

## Result

Attach device logs and screenshots covering:

- four recommendation modes
- explicit playback and uncensored lyrics
- cache/download metadata
- queue restoration
- navigation Back behavior
- performance table

A green hosted build is necessary but not sufficient. Missing physical-device
evidence means acceptance remains incomplete.
