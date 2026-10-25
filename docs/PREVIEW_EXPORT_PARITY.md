# Preview ↔ Export parity (One Video G)

## Principle

There is exactly **one** authoritative project model (`OvgProject`, schema v6 — v0.9.0; additive over v5)
and **one** set of pure math objects. The preview and the export pipeline both
call the same functions — neither has a second implementation:

| Concern                  | Shared code                              | Preview usage                     | Export usage                              |
|--------------------------|------------------------------------------|-----------------------------------|-------------------------------------------|
| Duration / playhead      | `ProjectMath`                            | playhead clamp, transport labels  | composition length, storage preflight      |
| Overlay transform @ t    | `KeyframeEvaluator.evaluate`             | overlay canvas                    | `KeyframedOverlay.getOverlaySettings`      |
| Position/scale/rotation  | `OverlayLayout` (NDC bridge)             | canvas placement                  | `OverlaySettings` anchors/scale/rotation   |
| Overlay scale px (v0.8.1) | `OverlayLayout.overlayExportScale` (THE ONE scale math: `s = widthFrac × frameW / bitmapW`, UNIFORM — verified against Media3 1.4.1 `OverlayShaderProgramPixelTest` goldens: drawn px = bitmap px × scale, clean rotation) | canvas placement via `previewSize` (same box) | `KeyframedOverlay`/`AnimatedTextOverlay` capture the real background px in `configure(videoSize)` and set `setScale(s, s)` |
| 2.5D camera (v0.9.0) | `CameraMath` (THE ONE camera math: `evaluate` pose @ t, `affineForDepth(pose, zDepth)`, `applyToTransform`, `videoPlaneMatrixValues` — pure, JVM-tested) + `CameraTransformEffect` (Media3 1.4.1 `MatrixTransformation`, stock shader) | `computePlacements` maps every overlay through the SAME `CameraMath.applyToTransform`; the video plane runs the SAME effect via `setVideoEffects` | `CameraTransformEffect` in the per-clip list (PIPELINE_ORDER "camera"); overlays map through `CameraMath` then the UNCHANGED v0.8.1 paths (`toBackgroundNdc` + `setScale(s,s)` + `toExportRotation`) |
| Overlay styles (v0.9.0) | `OverlayStyleBake` (pure IntArray bake: separable Gaussian shadow + glow, deterministic goldens) + `OverlayBitmapRenderer.styledBitmap` (ONE cache, `styledKey` = content+style, 12 MB dedicated cap, OVERLAY_BAKE evidence) | `resolveRender` returns `BakedImage` from the SAME styled cache entry (async bake, raw render until it lands) | `texturesForOverlays` fetches the SAME `styledBitmap` instance and passes its `boxScale` into `KeyframedOverlay` |
| Overlay bitmaps          | `OverlayBitmapRenderer` (LruCache)       | same bitmap instance              | same bitmap instance                       |
| Colour adjustments       | `ColorMatrixMath`                        | hardware-layer `ColorMatrix`      | `ColorAdjustmentEffect` (RgbMatrix)        |
| Audio placement          | `AudioTimeline.segmentsFor`              | `AudioPreviewController`          | audio `EditedMediaItemSequence` builder    |
| Auto-key edit (v0.5.0)   | `KeyframeEditing.applyTransformEdit`     | gizmo commit, quick panel, sheet  | (model-level — export reads the result)    |
| Text segments (v0.5.0)   | `TextSegmentPlanner` + `OverlayBitmapRenderer.textMetrics`/`wrapTextLines` | `computePlacements` render list | `ClipEffectsFactory.overlayTextures` |
| Text entrance (v0.5.0)   | `TextAnimationPlanner` + `ExpandedOverlays.build` (preview) / `ExpandedOverlays.animationSpec` (ONE plan, both pipelines, v0.5.7) | `computePlacements` render list | `ClipEffectsFactory.texturesForOverlays` — ONE `AnimatedTextOverlay` composite per animated text (v0.5.7) |
| Text layout (v0.5.9)     | `AnimatedTextLayout.segmentPlacement` — THE ONE frame-referenced function (single reference: the project frame `frameW×frameH`) + `OverlayBitmapRenderer.textMeasurer` (ONE Paint) | `computePlacements` evaluates parent + relative entrance per frame and places segments with it | `AnimatedTextOverlay.getBitmap` derives the composite block px from the same placement (`blockRect`) |
| Transition windows (v0.6.0) | `TransitionPlanner` (pure plan) + `TransitionShaders` (ONE GLSL string + ONE CPU spec) + `TransitionEffects.effectsForClip` | same effect list via `ExoPlayer.setVideoEffects` — the preview renders the LIVE shader, not a static mock | `ClipEffectsFactory.effectsFor` appends the same `TransitionEffect` instances per clip |
| Per-clip 2D effects (v0.6.0) | `ClipEffectMath` (GLSL + CPU spec) + `ClipGlEffects.effectsFor` | same effect list via `ExoPlayer.setVideoEffects` | `ClipEffectsFactory.effectsFor` appends the same blur/vignette/sharpen instances |
| Colour curves (v0.7.0) | `CurveMath` (Fritsch–Carlson monotonic spline + 256-entry LUT + CPU spec `applyCurves` + ONE GLSL string) + `ColorCurvesEffects.effectsFor` | same effect list via `ExoPlayer.setVideoEffects` — the SAME LUT bytes, quantized identically | `ClipEffectsFactory.effectsFor` appends the same `ColorCurvesEffect` (Master → R → G → B in one shader) |
| Speed ramps (v0.7.0) | `SpeedRampPlanner` (pure plan: segments, ramp-aware `outputDurationMs`, `projectToItemLocalMs`/`itemLocalToProjectMs`) | timeline/playhead mappings via `ProjectMath`/`TimelineLogic`; playback speed follows the segment (`SpeedRampPlanner.speedAtProjectLocalMs`) | `CompositionFactory.planVideoSequence` → one constant-speed `EditedMediaItem` per segment (max 16) with the segment's source trim and speed |
| Audio gain automation (v0.8.0) | `AudioGainMath` (THE ONE gain math: `gainAt = volume × keyframeAt × fadeEnvelope`) + `GainAutomationAudioProcessor` (ONE processor class, TWO injections) + `AudioLanePacker` (display-only lanes) | one `GainAutomationAudioProcessor` per audio clip ExoPlayer, injected via `DefaultAudioSink.setAudioProcessors` (`DefaultRenderersFactory.buildAudioSink`, 1.4.1 3-arg form); the profile is swapped + re-anchored every sync tick — live edits, no re-prepare | every audio `EditedMediaItem` in `buildAudioSequence` carries the SAME processor (identity → `NOT_SET` passthrough); timeline lanes drawn from `AudioLanePacker.pack` |
| Unified clip view (v0.5.1) | `TimelineClips` (`ClipKind` + `TimelineClip`) | timeline rows, exclusive selection, creation | (editing-layer model — export unaffected) |
| Audio preview coords (v0.5.1) | `AudioTimeline.windowPositionAt` | `AudioPreviewController` seek/drift | (export plan unchanged — `AudioTimeline.exportPlan`) |
| Video-less base (v0.5.1) | `ProjectMath.MIN_EMPTY_DURATION_MS` + `ClipEffectsFactory.texturesForOverlays` | black stage + `computePlacements` overlay layer | `CompositionFactory.buildBlackBaseItem` (black PNG item carrying the same textures) |

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
7. **Text animation segment cost (v0.5.0, revised v0.5.7).** Per-word/per-letter
   entrance animation expands a text overlay into up to 96 segment overlays
   (`ExpandedOverlays.MAX_SEGMENTS`), each with its own bitmap (≈36 KB for
   a typical letter, shared per identical word). **v0.5.7: the EXPORT builds
   ONE `AnimatedTextOverlay` composite texture per animated overlay** (one GL
   draw; segments are composited into the block frame per frame by the same
   evaluator/planner) — the per-segment GL texture/draw cost left the export
   effect list. Overlong LETTER texts fall back to WORD; WORD overflow
   disables the animation (UI warns; diagnostics `TEXT_ANIM_LIMIT`).
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
12. **Animated-text entrance delta direction (v0.5.7).** In the export's
   single-composite texture the entrance deltas (SLIDE_UP travel) ride the
   text block's frame, so on a ROTATED parent the slide direction rotates
   with the text; the pre-v0.5.7 per-segment pipeline slid in screen space
   (un-rotated). Un-rotated parents (the norm) render pixel-identically:
   same segment bitmaps, same planned positions, same evaluator times.
13. **Colour curves are 8-bit LUTs (v0.7.0).** The four tonal curves are
   sampled into 256-entry LUTs; the GPU stores them in 8-bit LUMINANCE
   textures and the CPU reference (`CurveMath.lutBytes`) quantizes to the
   SAME 8-bit table, so preview/export/CPU agree within <= 1 LSB. A curve
   is therefore not bit-identical to a floating-point per-pixel mapping
   (banding on extreme curves is possible in both paths equally).
14. **Speed-ramp preview jumps between segments (v0.7.0, K4).** The preview
   plays ONE ExoPlayer item and follows the ramp through live
   `setPlaybackSpeed` changes at segment boundaries — the speed INSIDE a
   segment is constant, so the preview approximates the ramp in steps
   (the export renders the exact quantized plan: one constant-speed item
   per segment, up to 16). Scrubbing maps through the same segment plan
   (`SpeedRampPlanner.projectToItemLocalMs`), so playhead positions match
   the export even where the motion between them is stepped.
15. **Ramp audio keeps the original pitch (v0.7.0, K5).** Each ramp segment
   speeds its audio with `SonicAudioProcessor` (tempo change, no pitch
   correction — the same processor the constant-speed path already used).
   Complex pitch-preserving time stretch would need a custom FFT
   AudioProcessor and is intentionally deferred; quality on extreme
   factors (0.1x/4x) degrades the same way constant-speed audio does.
16. **Split does not redistribute ramps (v0.7.0).** Splitting a ramped clip
   copies the ramp to both halves on the ORIGINAL constant-speed basis;
   points outside a half's new domain are dropped automatically by
   `SpeedRampPlanner.sanitizePoints` (self-healing), but the ramp is not
   re-scaled to the new extents. Trim behaves the same way (out-of-domain
   points are ignored, never crash).
17. **Overlay scale semantics (v0.8.1 — device-reported BUG A fix).** Media3
   1.4.1's `OverlayShaderProgram` draws an overlay at
   `bitmap_px × (scale.x, scale.y)`, rotated cleanly by `rotationDegrees`
   (`OverlayMatrixProvider` conjugates the rotation with the overlay aspect)
   and centred on `backgroundFrameAnchor` — proven against Media3's own
   `OverlayShaderProgramPixelTest` goldens (bitmap 304×84 on 1080×720 draws
   304×84 at scale (1,1); 84×304 at rotation 90; 1080×84 at scale
   (1080/304, 1)). The pre-v0.8.1 formula assumed the overlay quad spans the
   whole frame and collapsed rotated text into a ~2 px diagonal line (drawn
   757×10.8 px instead of 679×108 px for the reported block). The export now
   sets ONE uniform scale (`OverlayLayout.overlayExportScale`); the previous
   `overlayScaleSettings` function is REMOVED. The three golden-anchor tests
   in `OverlayExportParityTest` fail if a Media3 upgrade ever changes these
   semantics; the corner-parity tests (−55°/45°/90°/0°, portrait + landscape)
   hold preview and export within 1 px.
18. **Overlay background = pre-Presentation frame (documented, unchanged).**
   Export overlays composite on the DECODED frame of each item — the
   composition-level `Presentation` (resolution/aspect) is applied after the
   per-item effects, so `TextureOverlay.configure(videoSize)` receives the
   pre-Presentation px. For SOURCE projects (and any project whose source
   matches the output aspect) that frame IS the content; for fixed-aspect
   projects with mismatched sources the overlay maps onto the pre-P frame
   (the same convention the NDC anchors already used before v0.8.1). The
   uniform scale keeps the BITMAP aspect exact in every case; if a future
   release wants output-frame-relative geometry for mismatched
   fixed-aspect projects, a per-item Presentation must move BEFORE the
   overlay effect (contract change — out of scope for a fix release).

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
14. **The preview stage can no longer collapse; gestures refuse degenerate
    spaces (v0.5.4).** The editor's fixed chrome could exceed the window
    height (landscape, or the transform panel expanded) and starve the
    `weight(1f)` stage down to a 53×30 px sliver — the reported surface
    change — while the overlay gesture layer kept running there, corrupting
    saved transforms (deltas divide by the content size) and letting a plain
    tap classify as DELETE. Guards, in layer order: (a) the expanded
    transform panel's sliders scroll inside a 240 dp cap; (b) compact
    windows (< 660 dp) render the editor in a vertically scrollable column
    with a fixed 200 dp stage; (c) the normal portrait flex layout keeps a
    140 dp stage floor; (d) `OverlayLayout.isUsableContentSpace` (< 64 px on
    either axis) makes the gesture layer refuse every interaction and log
    `OVERLAY_GESTURE_REJECTED`. The coordinate model is untouched — the
    normalized 0..1 contract with the export is unchanged (device audit:
    `docs/AUDIT_v0.5.3_problems.md`).
15. **Image overlays persist their resource app-locally (v0.5.4).** The
    import copies the validated bitmap (bounded 1024 px decode, JPEG 88)
    into `files/overlay_media/img_<uuid>.jpg` and the overlay stores the
    `file://` URI, so grant loss / source deletion can no longer blank the
    overlay in preview AND export (both read the same stored URI through
    `contentResolver`; `OverlayBitmapRenderer`/`ClipEffectsFactory` are
    untouched). Existing projects keep their `content://` URIs.
16. **Un-expanded overlays pass through the shared render list (v0.5.5).**
    Since v0.5.0 `ExpandedOverlays.build()` dropped every overlay that needs
    no expansion — ALL image overlays and every text overlay without an
    entrance animation — from the ONE render list both pipelines consume: the
    preview never drew them (no placement → no pixels, no hit-test, no
    gizmos) and the export composition built zero overlay textures for them
    (device report 2026-10-21: a project with `overlays=1` exported with
    `effects=0 textures=0`). `expandOverlay()` now passes such overlays
    through unchanged (hidden layers still render nothing in both pipelines;
    blank animated text and the >96-segment fallback render un-animated
    instead of vanishing). The export engine is untouched — it already called
    the shared expansion; the fix restores what it receives
    (`docs/AUDIT_v0.5.4_report_followup.md` §1). Locked by the pure
    `ExpandedOverlaysTest` (7 tests).
17. **The preview stage floor holds by construction (v0.5.5).** The v0.5.4
    `heightIn(min = 140.dp)` chained AFTER `weight(1f)` was a no-op exactly
    in the starvation case (weight measures the child with min = max =
    leftover, so the floor coerced into `[leftover, leftover]`) — the 53×30
    sliver recurred on v0.5.4 at 16:00:17 with the transform panel expanded.
    The flex branch now caps the chrome BELOW the stage
    (`heightIn(max = window − 212.dp)` = window − top-bar reserve − floor)
    and scrolls it internally, so the weighted stage always receives
    ≥ 140 dp; normal portrait (chrome ≈ 420 dp < cap) is layout-identical.
    Compact windows and the gesture hard-stop are unchanged
    (`docs/AUDIT_v0.5.4_report_followup.md` §2).
18. **Evidence joinability (v0.5.5).** `OVERLAY_DRAW` logs the parent-first
    overlay id (`parent#sN` segments also carry `seg`), matching what
    `OVERLAY_ADD` / `OVERLAY_TRANSFORM` log, and `VIEWPORT_GEOMETRY` adds
    `playerState` so the next report can distinguish "decoder never reported
    a size" (videoW=0 with `playerState=ready`) from "player not prepared
    yet" (`idle`/`buffering`). Log meta only — zero behaviour change.
19. **ONE animated-text layout function, ONE reference (v0.5.9).** The
    user-reported symptom (animated text big/tight in the editor, small/
    spread in the export) is structurally closed: preview and export now
    BOTH lay segments out through `AnimatedTextLayout.segmentPlacement`,
    which returns FRAME fractions — the single reference is the project
    frame (`frameW×frameH`, `frameAspect`). No source-video aspect exists
    anywhere in the layout path (only `frameAspect` + the canonical block/
    segment bitmap aspects, measured by the ONE shared Paint
    `OverlayBitmapRenderer.textMeasurer`). The export composite derives its
    block px via `AnimatedTextLayout.blockRect` (algebraically identical to
    the v0.5.8 formula — `AnimatedTextOverlayParityTest` pins the
    round-trip on an 80-point time grid); the preview evaluates parent +
    relative entrance per frame from the SAME `ExpandedOverlays.build`
    `SegmentPlan`s the export consumes, so the two pipelines cannot drift.
    `ANIM_TEXT_FRAME` now logs x, y, scale and boxPx ON THE OUTPUT FRAME
    (with `framePx` naming the basis, all numbers `Locale.US`) — the exact
    fields `OVERLAY_DRAW` logs for the preview, for 1:1 comparison.
20. **Comparison hygiene gates (v0.5.9).** The preview does not draw
    overlays while the video is not actually rendering — `video=none` (no
    decoder geometry AND no clip probe dims) or the player `BUFFERING`
    (stale frame) — so overlay pixels always correspond to a real video
    frame; flips are logged as `OVERLAY_GATE` and clip-less projects are
    exempt (always drawn). Stale effect rebuilds are ignored: a
    `PREVIEW_REBUILD` whose revision is no longer the current one is
    skipped (`PREVIEW_REBUILD_SKIPPED`), so an older effect list can never
    overwrite a newer one.
21. **Transitions render as windows on the adjacent clips (v0.6.0).**
    Media3 1.4.1 has NO Transition API (verified against the shipped
    artifacts: `Composition.Builder` has no `setTransitions`; the
    `Transition`/`CrossFadeTransition` classes do not exist — they arrive
    in later Media3). Transitions are therefore rendered as per-clip GLSL
    effects built by the SAME shared pipeline as the 2D effects: the OUT
    window on the tail of the previous clip (identity -> black/shifted-out)
    and the IN window on the head of the next clip (black/off-frame ->
    identity). CROSSFADE is a linear fade THROUGH BLACK; WIPE/SLIDE are a
    directional push through black in the kind's direction. Because a
    sequential 1.4.1 composition has no second stream to blend, the total
    perceived window is out+in = 2×durationMs and the mid-point passes
    through black instead of a true two-frame blend — identical on both
    pipelines by construction (same shader string, same uniforms, same
    plan windows from `TransitionPlanner`). The preview is NOT a static
    mock: `ExoPlayer.setVideoEffects` runs the identical shader, so both
    WIPE/SLIDE and CROSSFADE preview live. Transitions never change the
    sequence length (the v0.3.0 duration contract is untouched) and
    invalid ones (non-adjacent pair, window > 50% of either clip) are
    dropped by `TransitionPlanner.sanitize` on every commit and project
    open (`TRANSITION_INVALID` evidence).
22. **Effect and transition edits re-prepare the decoder (v0.6.0).**
    `ClipEffectPlan.effectiveSignature` now includes `clip.effects` and
    `project.transitions`: they change the GL effect LIST itself (shader
    instances are inputs to the decoder's output stage), so each effect/
    transition edit pays one decoder re-prepare — the same class of edit
    as a colour change. The v0.3.1 contract is untouched: overlay edits
    still NEVER change the signature (locked by
    `ClipEffectsTest.overlay edits still never change the signature`).
    Up to 3 effects per clip (`ClipEffectsFactory.MAX_EFFECTS_PER_CLIP`,
    enforced in the ViewModel, trimmed in the glue, warned in the sheet);
    intensity 0 builds no GL instance (no GPU cost).
23. **Audio gain automation is ONE math, ONE processor, TWO injections
    (v0.8.0).** `AudioGainMath.gainAt(volume, keyframes, durationMs,
    fadeInMs, fadeOutMs, t)` is the ONLY gain formula in the codebase; both
    pipelines apply it through the SAME `GainAutomationAudioProcessor`
    (export: one instance per audio `EditedMediaItem`, t=0 = the item's
    first sample — sample-exact; preview: one instance per audio clip
    ExoPlayer injected via `DefaultAudioSink.setAudioProcessors`, gain
    timeline re-anchored to the player clock every 100 ms sync tick, so
    edits apply live with NO decoder re-prepare — audio is not part of
    `effectiveSignature`, which stays video-only). Documented preview
    approximations: (a) the gain for a sample is computed when the sample
    is PROCESSED, which leads the moment it is HEARD by the sink buffer
    depth (~50–200 ms device-dependent) — time-varying fades/curves
    therefore land slightly early in the PREVIEW; constant gains are
    unaffected; the EXPORT is sample-exact. (b) `AUDIO_SYNC` logs the
    audio↔video drift at every re-seek for device-side validation. The
    v0.3.0 duration contract is untouched: fades/keyframes/gate change
    NOTHING about placement — `AudioTimeline.exportPlan` output is
    byte-identical with and without automation (locked by
    `AudioTimelineExportPlanTest`).
24. **Crossfade is a simplified LINEAR overlap (v0.8.0).** Overlapping
    audio clips with fades crossfade automatically through the linear
    `fadeEnvelope` on both sides — the sum mid-overlap is NOT constant-power
    (a dip of a few dB is audible on long overlaps). Equal-power curves are
    a future phase. The "crossfade" button/gesture creates the overlap
    (`AudioCrossfade.plan`: nearest neighbour, overlap = 800 ms clamped to
    both durations, floor 150 ms) and sets BOTH fades — one model commit,
    no destructive PCM editing.
25. **Noise gate is a simple time-domain gate (v0.8.0).** NOT ML and NOT
    spectral subtraction: an envelope follower + threshold (0.02 ≈
    -34 dBFS, attack ~2 ms, release ~120 ms) attenuates low-level
    background between phrases. Noise DURING speech stays (the gate is
    open while level ≥ threshold); music-with-hiss is not a target.
    Advanced (ML/spectral) reduction is a future phase — the same honest
    scoping as J (no scopes) and K (no pitch adjust).
26. **Pitch adjust is still absent (v0.8.0).** The K5 limitation carries
    over unchanged: clip audio that follows speed changes has no pitch
    correction, and audio gain automation does not interact with pitch.
27. **Looping audio restarts its gain envelope per loop segment (v0.8.0).**
    Fades/keyframes are defined on the clip-local domain
    [0, trimmedDurationMs]; a legacy looping clip repeats that domain, so
    each loop iteration fades in/out again (musically sensible for
    background loops; documented so the behaviour is not read as a bug).
    Audio keyframes cap at 16 per clip (`AudioGainMath.MAX_KEYFRAMES`,
    `AUDIO_KEYFRAME_LIMIT`); invalid points are dropped at commit time
    (`AUDIO_KEYFRAME_INVALID`) and defensively re-sanitized in
    `GainAutomationAudioProcessor.profileOf` (documents edited externally
    cannot poison the pipeline).
28. **The 2.5D camera is affine, keyframed and parity-locked (v0.9.0).**
    The video plane carries the project camera through a Media3 1.4.1
    `MatrixTransformation` (`CameraTransformEffect`) — verified against the
    REAL 1.4.1 sources during the L2b spike: the stock transformation vertex
    shader maps the frame NDC affinely (`gl_Position = uTransformationMatrix`
    `* aFramePosition`), the output frame KEEPS its size and pixels pushed
    outside NDC are CLIPPED (a camera crop). The spike first named
    `ScaleAndRotateTransformation`, but its real `configure` RESIZES the
    output frame to preserve every pixel (bbox compensation) — a uniform
    zoom is a visual no-op downstream of the SCALE_TO_FIT Presentation and
    it offers no pan — so the MatrixTransformation vehicle is the one the
    gate passed on (`VideoPlaneCameraParityTest`: probe-quad corners
    preview↔export ≤ 1 px, 9:16 AND 16:9, incl. keyframed poses). Every
    overlay receives the camera through the SAME pure `CameraMath` with
    depth-proportional strength (zDepth 0 rides the video plane EXACTLY;
    −1 moves half, +1 moves 1.5×), then flows through the UNCHANGED v0.8.1
    paths (`OverlayExportParityTest` extended: camera × depths × times ×
    2 aspects ≤ 1 px). Camera edits change the preview GL list →
    `effectiveSignature` includes `project.camera` (one decoder re-prepare
    per camera commit, the same class as colour edits). Identity cameras
    add ZERO effect instances (pre-v0.9.0 behaviour byte-exact).
29. **Overlay styles are ONE baked bitmap, not GPU work (v0.9.0).**
    Shadow/glow are baked by the pure `OverlayStyleBake` (separable
    Gaussian over the alpha, deterministic, JVM goldens) into ONE bitmap
    that BOTH pipelines consume from the SAME cache entry
    (`styledBitmap`/`styledKey` = content+style) — style parity by
    construction, zero new shaders (Opção A rule). The bake is symmetric
    around the content, and both paths multiply the placement scale by the
    SAME `boxScale` (bakedWidth/sourceWidth), so the CONTENT box stays
    exactly the unstyled one and the style only adds pixels around it
    (locked by `OverlayExportParityTest.styled overlays keep the content
    box identical on both frames`). Rules and limits: (a) animated text
    IGNORES the style in v1 (the per-segment model is planned from the
    unstyled block metrics — `effectiveStyle` is the ONE rule, UI hints
    it); (b) styled bitmaps live in a DEDICATED 12 MB LruCache with
    eviction evidence (`OVERLAY_BAKE`: ms, px, hit/miss, evictions);
    (c) a failed bake falls back to the RAW render on both paths — never
    a crash, never a blank overlay; (d) the bake is CPU one-off per
    content/style state — no per-frame cost, no GPU cost.
30. **Depth planes: draw order, anchors and honest edges (v0.9.0).**
    `zDepth` (−1 deepest .. +1 closest, 0 = the video plane) defines the
    draw order when it differs (deeper drawn FIRST — Media3 draws the last
    texture on top); the existing `zOrder` is the tie-break and remains
    what bring-to-front/send-to-back manipulate. The mapped overlay centre
    clamps to the content edge (0..1) in the SHARED `CameraMath` — the
    same border the export anchors already coerce to
    (`coerceIn(-1,1)`), so an overlay pushed past the edge sticks at the
    border IDENTICALLY on both paths (its box still clips visually); the
    local suite caught the asymmetry before the release and the clamp is
    the parity-first fix. The depth/parallax mapping for fixed-aspect
    projects with mismatched sources follows the SAME pre-Presentation
    convention the anchors already use (limitation 18) — coherent for
    SOURCE projects and matching aspects, drift documented for mismatched
    letterboxed sources.
