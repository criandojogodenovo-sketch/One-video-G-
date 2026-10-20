# Preview ↔ Export parity (One Video G)

## Principle

There is exactly **one** authoritative project model (`OvgProject`, schema v2)
and **one** set of pure math objects. The preview and the export pipeline both
call the same functions — neither has a second implementation:

| Concern                  | Shared code                              | Preview usage                     | Export usage                              |
|--------------------------|------------------------------------------|-----------------------------------|-------------------------------------------|
| Duration / playhead      | `ProjectMath`                            | playhead clamp, transport labels  | composition length, storage preflight      |
| Overlay transform @ t    | `KeyframeEvaluator.evaluate`             | overlay canvas                    | `KeyframedOverlay.getOverlaySettings`      |
| Position/scale/rotation  | `OverlayLayout` (NDC bridge)             | canvas placement                  | `OverlaySettings` anchors/scale/rotation   |
| Overlay bitmaps          | `OverlayBitmapRenderer` (LruCache)       | same bitmap instance              | same bitmap instance                       |
| Colour adjustments       | `ColorMatrixMath`                        | hardware-layer `ColorMatrix`      | `ColorAdjustmentEffect` (RgbMatrix)        |
| Audio placement          | `AudioTimeline.segmentsFor`              | `AudioPreviewController`          | audio `EditedMediaItemSequence` builder    |

Timeline, clip labels, preview and export all read duration from
`ProjectMath.projectDurationMs`. There is no other duration calculation.

## Project duration rule (v0.3.0 — authoritative)

1. **With video present**, the project duration is the **video sequence
   length — period. NOTHING extends it.** Media3 1.4.1 compositions are
   video-length: audio sequences are clamped to the video duration
   (`AudioTimeline.exportPlan`) and overlay windows only render inside clips
   (`ClipEffectPlan.overlayWindowsForClip`), so any editor timeline longer
   than the video describes time the exported file does not contain.
   - v0.2.4 device report: importing a long audio stretched the editor
     timeline while the exported file stayed video-length — fixed in
     v0.2.5 (audio never extends).
   - v0.3.0 device report: an image bar dragged past the video end still
     stretched the timeline (65 s on a 54.9 s video); playback beyond the
     video end then froze (player STATE_ENDED + frozen playhead) and
     diagnostics logged misleading `video=none` / `videoW=0` events. Fixed
     by making the video define the duration; overlays no longer extend it
     either.
2. **Elements outside the video range are never discarded.** An overlay or
   audio bar placed past the video end (or before a later clip's start)
   persists, stays selectable and draggable in the timeline, and renders
   nothing — in preview AND export (one rule, one implementation). The
   timeline shades the region past the video end and dims such bars; the
   playhead range (`projectDurationMs`) never enters that region.
3. **Audio-only projects** (no video clips) keep an element-driven duration
   (longest audio/overlay), so such projects remain playable. v0.3.0 adds a
   clip-less playback driver so the playhead advances in real time there
   (previously it froze at 0 while audio stuttered against the drift
   corrector).

Preview and export read the SAME values from the SAME function
(`ProjectMath.projectDurationMs`); the export plan
(`CompositionFactory.Plan.plannedDurationMs`) and
`ExportManager.verifyPlayable` use the same number, so duration validation,
storage preflight and the reported output duration all match the produced
file. `ProjectMath.timelineSpanMs` (widest element) exists ONLY for laying
out the editor timeline surface.

## Time base (important)

Media3 Transformer resets item timestamps to 0 at the clip window start, and
`SpeedChangeEffect` scales timestamps so that **output time == project-local
time** for overlays. Preview playback uses the identical mapping
(`ProjectMath.projectToItemLocalMs`). v0.1.0 added `trimStartMs` to these
positions, which shifted overlays/audio on trimmed clips — fixed in v0.2.0
and covered by `ProjectMathTest`.

## Coordinate contract

Model space: overlay centre `(x, y)` in 0..1 of the **content area** (the
fitted video rectangle), `scale` = width fraction of the content width,
rotation **clockwise-positive**, opacity 0..1. Export converts via
`OverlayLayout.toBackgroundNdc` (`y+` up in NDC) and negates rotation for
Media3's counter-clockwise convention. Round-trip and visual-agreement tests:
`OverlayLayoutTest`.

The preview viewport computes its letterbox/pillarbox with the same
`OverlayLayout.fitRect` used conceptually by the export `Presentation`
effect, and overlays are drawn **clipped to the content rect** — overlays
cannot sit in the letterbox bars in either path (Media3 1.4.1 composites
overlays per-clip, before `Presentation`; composition-level
`setEffects` supports only `Presentation`).

## Known, documented limitations

1. **Colour space difference.** Media3 applies `RgbMatrix` in linear RGB;
   the preview applies the equivalent matrix in sRGB on the TextureView's
   hardware layer. Direction and approximate magnitude match; pixels are not
   bit-identical. Verified visually on device (see test plan), not bit-exact.
2. **Audio volume > 1.0 in preview.** `ExoPlayer.setVolume` caps at 1.0; the
   export's `VolumeAudioProcessor` amplifies PCM above 1.0. Preview clips at
   100%, export reaches up to 200%.
3. **Overlays are per-clip at export.** Overlays spanning a clip boundary are
   split per clip; within a clip the placement is identical to preview.
   Overlays cannot extend into letterbox bars (both paths clip).
4. **Preview playback transitions.** ExoPlayer reloads when the playhead
   crosses a clip boundary; there can be a brief stall between clips during
   preview playback (export is seamless).
5. **Frame rate** follows the source clips (no retiming in Media3 1.4.1).
6. **Text wrap** happens inside a fixed 1920×1080 canonical bitmap shared by
   both paths, so wrap points are identical; apparent size relative to a
   differently-shaped *source* clip in `Original` format projects varies per
   clip in both preview and export equally.

## What "parity" means here (verification status)

- **Unit-verified:** transform evaluation, NDC/scale/rotation conversion,
  duration math, audio segment plans, colour matrix composition
  (71 JVM tests, 0 failures at v0.2.0).
- **Not yet verified:** pixel-level frame comparison and audio sync on a
  physical device. `docs/DEVICE_TEST_PLAN.md` defines the exact procedure
  (including exporting a test project and comparing representative frames).
  **Parity must not be claimed as device-verified until that plan is run.**
