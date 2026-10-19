# One Video G — Product Analysis

Date: 2026-10-19 · Status: living document · Author: engineering (v1 baseline)

## 1. Scope and method

This document records what was learned before and during the first implementation
phase of One Video G, a mobile-first Android video editor. It separates:

- **Documented facts** — official Android/Media3 documentation and library source
  code (Media3 1.4.1 was inspected source-level for this project).
- **Reported user experience** — app-store reviews, Trustpilot and Reddit
  discussions about CapCut. Individual reports are not treated as representative
  of all users, but recurring patterns across independent sources are treated as
  real product risks worth designing against.

The goal is not to copy CapCut, but to adopt its *useful editing concepts* while
avoiding its documented failure modes.

## 2. What is worth adopting (as general editing concepts, not UI cloning)

| Concept | Why it is useful | How One Video G adopts it |
|---|---|---|
| One-hand portrait layout: preview on top, timeline below, contextual tool panel | Editing flows naturally top→bottom on a phone | Compose layout with bottom tool bar and bottom-sheet tool panels |
| Simple, discoverable core actions: trim, split, reorder, delete | These cover the majority of real edits | All implemented in v1 timeline with undo/redo |
| Playhead as the center of editing (split at playhead, seek, selection sync) | Keeps timeline and preview mentally in sync | Playhead position is the single source of truth in `TimelineState` |
| Fast project resumption (reopen last project) | Reduces friction for short sessions | Project list home screen with thumbnails + autosave |
| Overlays (text/stickers) and quick speed/volume tweaks | High value, low learning cost | Text/image overlays, per-clip volume and speed via Media3 effects |

## 3. Reported problems One Video G must avoid

Recurring patterns from independent sources (App Store reviews, Trustpilot,
Reddit r/CapCut, community groups):

1. **Paywall creep** — features that were free moving behind subscriptions
   (repeatedly mentioned across 2024–2026 reviews). *Individual reports, but the
   pattern is consistent and widely reported.*
2. **Unreliable export** — export failures, crashes at the end of long exports,
   or output files that cannot be played (Trustpilot pattern).
3. **Performance** — laggy playback while editing, freezes on ordinary devices
   (Reddit/App Store reports).
4. **Project loss / reliability** — edits or projects lost after crashes or
   interruptions (scattered reports).
5. **Aggressive upsell noise** — constant Pro ads disturbing the editing flow.

### Concrete commitments in One Video G

| Risk | Commitment | Measurable acceptance criterion (v1) |
|---|---|---|
| Paywall creep | Core editing + export are fully offline and free; no account required | Create→import→edit→save→export works with airplane mode on; no login screen exists in the app |
| Unreliable export | Real encoder pipeline (Media3 Transformer), validation after export, honest failure states | Export produces an MP4 that opens in `MediaMetadataRetriever` with ≥1 video track; failures surface a specific message and keep the project intact |
| Performance | Streaming pipelines; never load whole videos into RAM; bounded thumbnail caches | Thumbnails generated at ≤8 per clip, decoded lazily, cached on disk; preview uses `ExoPlayer` (hardware decode) |
| Project loss | JSON project file autosaved after each edit op; missing-media detection | Reopening a project restores the exact timeline; if a source file is gone, the clip is flagged instead of crashing |
| Upsell noise | No ads, no upsell UI exists | Zero promotional UI surfaces in the codebase |

## 4. Technology decisions (verified against Media3 1.4.1 sources)

**Stack:** Kotlin 2.0, Jetpack Compose (BOM 2024.09), Media3 1.4.1
(ExoPlayer preview + Transformer export), Storage Access Framework, kotlinx
.serialization for project JSON, GitHub Actions CI. minSdk 24 / targetSdk 34.

APIs verified at source level (tag `1.4.1` of `androidx/media`):

- `Transformer.start(Composition, String path)`, `Transformer.cancel()`,
  progress via `getProgress(ProgressHolder)` (`progress` is an **int**, 0–100).
- `Composition.Builder(List<EditedMediaItemSequence>)` — multi-sequence
  mixing supported; `EditedMediaItemSequence` has an `isLooping` flag, used for
  a background-music audio track.
- Trimming: `MediaItem.ClippingConfiguration` (start/end in ms) per clip.
- Speed: `SpeedChangeEffect(speed)` (video) + `SonicAudioProcessor.setSpeed()`
  (audio) — the documented combination.
- Volume: `EditedMediaItem.Builder` has **no** `setVolume` in 1.4.1 → custom
  `AudioProcessor` scaling PCM samples (16-bit and float paths).
- Resolution: `Presentation.createForWidthAndHeight(w, h, LAYOUT_SCALE_TO_FIT)`.
- Colour adjustments: `RgbMatrix` is an interface with
  `getMatrix(long presentationTimeUs, boolean useHdr)` → brightness/contrast/
  saturation implemented as standard 4×4/5×4 colour matrices.
- Overlays: `OverlayEffect(ImmutableList<TextureOverlay>)`;
  `BitmapOverlay.createStaticBitmapOverlay(Bitmap, OverlaySettings)`;
  positioning via `OverlaySettings.Builder.setBackgroundFrameAnchor(x, y)`
  (NDC −1..1). Time-windowed visibility implemented with a `BitmapOverlay`
  subclass that swaps to a transparent bitmap outside its window (returning
  `null` is not safe — `getTextureId` dereferences the bitmap).
- Bitrate/quality: `DefaultEncoderFactory.Builder.setRequestedVideoEncoderSettings(
  VideoEncoderSettings.Builder().setBitrate(...))`.

**FFmpeg:** not adopted for v1. Media3 covers trimming, concatenation, speed,
volume, overlays, colour and H.264/AAC MP4 export. FFmpegKit's LGPL/GPL
distribution constraints and added binary size are not justified yet.

**C++/NDK:** not adopted — no requirement beyond what Media3 provides.

## 5. Dependencies and licences

| Dependency | Licence | Notes |
|---|---|---|
| AndroidX / Compose / Media3 | Apache 2.0 | First-party Google stack |
| kotlinx.serialization | Apache 2.0 | Project JSON |
| Kotlin stdlib | Apache 2.0 | — |

No GPL, LGPL or copyleft-next components in the app binary. GitHub Actions uses
`ubuntu-latest` + Temurin JDK 17 (GPLv2+Classpath, standard for JDK builds).

## 6. Known limitations (v1, stated honestly)

- **Frame rate is passed through from the source.** `TransformationRequest` in
  Media3 1.4.1 exposes no frame-rate conversion; the UI therefore does not
  advertise 30/60 fps switches. (Documented limitation, not a hidden one.)
- **Transitions between clips** (crossfade/wipe) are *planned*: Media3 has no
  built-in inter-clip transition; implementing them safely requires custom
  compositor work that v1 does not attempt.
- **Multiple video layers** are planned; v1 has one video sequence + one
  looping audio (music) sequence.
- Runtime behaviour on physical devices (encoder quirks, GL effects on
  specific GPUs) requires on-device testing; CI verifies build + unit tests
  only. This distinction is repeated in the README.
- HDR inputs are tone-mapped by the pipeline to SDR H.264; exotic codecs may
  fall back or fail with a clear error.

## 7. Implementation plan (phases)

- **Phase 0 — Inspection & analysis** (done): empty repository confirmed;
  this document produced; Media3 1.4.1 APIs verified at source level.
- **Phase 1 — Runnable foundation** (done): branded app, two-dot icon,
  project home, pt/en switching, dark theme, CI workflow.
- **Phase 2 — First complete workflow** (done): SAF import → real ExoPlayer
  preview → trim/split/reorder/delete → autosave → real MP4 export.
- **Phase 3 — Timeline & audio** (partially done): undo/redo, zoom, music
  track, per-clip volume; drag-reorder replaced by move-left/move-right in v1.
- **Phase 4 — Creative editing** (partially done): text/image overlays, speed,
  aspect ratio, brightness/contrast/saturation; transitions planned.
- **Phase 5 — Reliability & optimisation**: on-device testing pass, memory
  profiling, export failure matrix. *Requires physical devices.*
- **Phase 6 — Release prep**: signed release APK via GitHub Secrets.

## 8. Acceptance criteria for the first milestone (Definition of Done)

1. `./gradlew assembleDebug` succeeds locally and in GitHub Actions. ✅
2. Launcher shows the two-dot One Video G icon. ✅
3. Interface switches pt ↔ en (settings + system default). ✅
4. User imports a local video through the system picker. ✅
5. User previews and trims/splits/reorders/deletes clips. ✅
6. Project is saved to app storage and reopens identically. ✅
7. Export produces a real playable MP4 (H.264/AAC), validated post-export. ✅
8. Test/build results reported from actual runs, never fabricated. ✅
9. GitHub Actions publishes a downloadable APK artifact + release. ✅
10. Limitations and next steps documented (this file + README). ✅
