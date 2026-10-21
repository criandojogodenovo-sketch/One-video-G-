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
| Auto-key edit (v0.5.0)   | `KeyframeEditing.applyTransformEdit`     | gizmo commit, quick panel, sheet  | (model-level — export reads the result)    |
| Text segments (v0.5.0)   | `TextSegmentPlanner` + `OverlayBitmapRenderer.textMetrics`/`wrapTextLines` | `computePlacements` render list | `ClipEffectsFactory.overlayTextures` |
| Text entrance (v0.5.0)   | `TextAnimationPlanner` + `ExpandedOverlays.build` (ONE expansion) | `computePlacements` render list | `ClipEffectsFactory.overlayTextures` |
| Unified clip view (v0.5.1) | `TimelineClips` (`ClipKind` + `TimelineClip`) | timeline rows, exclusive selection, creation | (editing-layer model — export unaffected) |
| Audio preview coords (v0.5.1) | `AudioTimeline.windowPositionAt` | `AudioPreviewController` seek/drift | (export plan unchanged — `AudioTimeline.exportPlan`) |
| Video-less base (v0.5.1) | `ProjectMath.MIN_EMPTY_DURATION_MS` + `ClipEffectsFactory.texturesForWindows` | black stage + `computePlacements` overlay layer | `CompositionFactory.buildBlackBaseItem` (black PNG item carrying the same textures) |

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
   corrector). v0.5.1 floors a video-less project at 5 s
   (`ProjectMath.MIN_EMPTY_DURATION_MS`) so a freshly created "Projeto
   vazio" is scrubbable and exportable before the first element exists,
   and grows as text/image/audio clips are added.

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

## Overlay renderer split (v0.3.1 — device-reported fix)

Device evidence (v0.3.0): text/image overlays were invisible in the preview
unless the overlay was selected/manipulated, while the export showed them
correctly. Root cause: preview overlay pixels were produced by the ExoPlayer
GL pipeline (`OverlayEffect` textures via `setVideoEffects`), which did not
composite on the affected device's GL stack; the Compose canvas only drew
content as a fallback (no clip / player not ready) or during a live gesture.

v0.3.1 contract:

1. **The preview has ONE overlay renderer: the Compose layer**
   (`OverlayLayer`). It draws the content of EVERY overlay active at the
   current playhead (`startMs <= playheadMs < endMs`, visible), always, on
   top of the PlayerView. Selection chrome (frame, rotate knob, delete chip,
   scale corner) is a separate pass drawn only for the selected overlay, on
   top of all content.
2. **The preview GL effect list is video-only** (speed + colour +
   Presentation). `ClipEffectsFactory.effectsFor(forPreview = true)` attaches
   NO `OverlayEffect` — otherwise overlays would double-render where the GL
   pipeline does work.
3. **The export path is unchanged**: Transformer still composites overlays
   through `KeyframedOverlay` textures (`effectsFor(forPreview = false)`),
   which was verified working on device.
4. Both renderers evaluate the same normalized model (0..1 positions, width-
   fraction scale, clockwise-positive rotation) through `KeyframeEvaluator`
   and `OverlayLayout`, so the preview shows exactly what the export
   composes.
5. Preview content is clipped to the content rect (`clipRect`) so overlays
   never bleed into the letterbox bars — matching the export's per-clip
   compositing.
6. `ClipEffectPlan.effectiveSignature` (preview effects-rebuild key) no
   longer includes overlays: an overlay edit re-renders the Compose layer
   directly and must NOT re-prepare the decoder (previously every overlay
   edit paid one decoder init + frame flash). Clip identity/trim/speed/
   colour/aspect remain rebuild inputs; a playhead move still never rebuilds.

## Unified clip identity and the video-less project (v0.5.1)

**Clip identity.** Every new timeline element — video clip, text overlay,
image overlay, audio clip and split halves — is minted by ONE generator
(`TimelineLogic.newId`, a full random UUID) and APPENDED to its list; no
code path replaces or reuses an existing element by kind. The legacy v1
migration now also mints a fresh UUID for the migrated `music` track
(previously it derived `"music_" + uri.hashCode()` — a fixed id shared by
unrelated documents). The bitmap cache key uses the FULL text content
instead of a 32-bit `hashCode`, so two different texts can no longer share
a cached bitmap. All of this is pinned by `TimelineLogicTest`
(uniqueness/UUID-ness), `ProjectMigrationTest` (fresh UUID + idempotency)
and `TimelineClipsTest` (cross-kind uniqueness).

**Exclusive selection.** Every selection path routes through
`EditorViewModel.selectTimelineClip(kind, id)` (or `clearSelection`); the
legacy per-kind setters are thin aliases, so no code path can leave two
clips of different kinds selected at once.

**Video-less projects.** A project without video clips renders its black
stage in the preview (Compose overlay layer + `AudioPreviewController` on
top) and exports a generated black PNG base item spanning the whole
planned duration (`CompositionFactory.buildBlackBaseItem`). The base item
carries the overlay textures through the SAME
`ClipEffectsFactory.texturesForWindows` the per-clip path uses, and its
frame aspect equals the preview's clip-less `contentAspect` (SOURCE →
16:9, fixed aspect → the project ratio), so normalized overlay positions
are identical in both paths by construction.

**Audio preview coordinates.** The preview audio ExoPlayer is created with
`ClippingConfiguration` (trimStart..trimEnd); Media3 rebases item
timestamps to 0 at the window start. `AudioTimeline.windowPositionAt`
converts project time to that window-local coordinate; the pre-v0.5.1
scheduler compared against the ABSOLUTE source position and computed a
permanent false drift of `trimStartMs`, re-seeking the player every sync
tick (stutter/silence for any trimmed clip). The export plan is unchanged.

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
7. **Text animation segment cost (v0.5.0).** Per-word/per-letter entrance
   animation expands a text overlay into up to 96 segment overlays
   (`ExpandedOverlays.MAX_SEGMENTS`), each with its own bitmap (≈36 KB for
   a typical letter, shared per identical word) + one export texture —
   bounded but real: worst case ≈3.5 MB inside the shared 20 MB LruCache
   and 96 `KeyframedOverlay` instances in the export effect list. Overlong
   LETTER texts fall back to WORD; WORD overflow disables the animation
   (UI warns; diagnostics `TEXT_ANIM_LIMIT`).
8. **Text animation background pill (v0.5.0).** With a background colour
   set, each animated segment renders its own pill (per-word karaoke
   look); without a background the assembled segments are pixel-aligned
   with the un-animated block (same measureText prefix advances, same
   baseline stack, same int rounding — `TextSegmentPlanner` shares
   `OverlayBitmapRenderer.textMetrics`/`wrapTextLines`/`blockSizePx`).
9. **Composed keyframe interpolation (v0.5.0).** When a parent has BOTH
   authored keyframes and a text animation, segments carry keyframes
   composed at the union of authored + entrance times; between two union
   points the parent's motion is interpolated linearly by the evaluator —
   exact at every authored time, piecewise-linear between them.
10. **Segment composition aspect (v0.5.0).** Segment positions are composed
   against the project-level content aspect (project aspect, else first
   clip's source aspect). A SOURCE-aspect project whose clips have
   different resolutions inherits the parent overlay's existing per-clip
   geometry limitation in both paths equally.
11. **Video-less export base (v0.5.1).** The black background is a single
   PNG image item spanning the project duration (one decoder init, no
   per-clip seams — there is no video). Its frame aspect equals the
   preview's clip-less content aspect; overlay parity holds by
   construction because both paths read the same normalized model and the
   same texture builder.

## What "parity" means here (verification status)

- **Unit-verified:** transform evaluation, NDC/scale/rotation conversion,
  duration math, audio segment plans, colour matrix composition
  (71 JVM tests, 0 failures at v0.2.0).
- **Not yet verified:** pixel-level frame comparison and audio sync on a
  physical device. `docs/DEVICE_TEST_PLAN.md` defines the exact procedure
  (including exporting a test project and comparing representative frames).
  **Parity must not be claimed as device-verified until that plan is run.**
12. **Video-less export base frame rate (v0.5.2).** The black base
    image item now carries `EditedMediaItem.setFrameRate(30)` in addition
    to `setDurationUs`. Media3 1.4.1's `ImageAssetLoader` constructor
    checks BOTH (`checkState(durationUs != TIME_UNSET)` and
    `checkState(frameRate != RATE_UNSET_INT)`); v0.5.1 set only the
    duration, so every video-less export threw IllegalStateException at
    Transformer start. `setFrameRate` is ignored for inputs with an
    implicit frame rate (video), so with-video exports are untouched.
    The duration is confirmed on both levels (EditedMediaItem durationUs
    for the composition sequence, MediaItem.setImageDurationMs for image
    playback), and a failed black-PNG write is now diagnosed explicitly
    (`BLACK_BASE_MISSING`) instead of surfacing as an opaque empty-sequence
    composition error.
13. **Preview overlay pixels are Compose composables (v0.5.3).** The
    preview no longer renders overlay pixels from bitmaps in a draw-phase
    Canvas. The overlay layer is now a plain Compose `Box` above the
    PlayerView: TEXT renders with `Text` primitives laid out from the SAME
    canonical block the export bitmap uses (`OverlayTextRender` ->
    `textMetrics`/`wrapTextLines`/`blockSizePx`, one `Text` per pre-wrapped
    line so Compose never re-wraps), IMAGE renders with `Image` at the
    bitmap's own aspect; x/y (0..1), scale (width fraction), clockwise
    rotation, opacity, z-order and content clipping are identical to the
    export path. The selection chrome (frame + handles) moved to a separate
    unclipped layer above the content, drawn only for the selected item.
    Evidence contract: every active overlay logs `OVERLAY_DRAW` (id, kind,
    x, y, scale, rotation, opacity, visible, bitmap/text size) when the
    drawn set changes or at most once per second while playing. Known
    cosmetic delta: glyph rasterization differs slightly between Compose's
    text stack and the export's `Paint` bitmap (same typeface, same size,
    same line assignment); block geometry, transforms and timing are shared
    and unit-tested.
