# One Video G — physical device test checklist (v0.5.9)

**APK:** `OneVideoG-v0.5.9-release.apk` (release "latest", versionCode 20)
**Scope:** the 7 prescriptions for the animated-text preview↔export parity —
ONE layout function with ONE reference (the project frame), ONE text
measurer, no drawing while video=none/BUFFERING, stale `PREVIEW_REBUILD`
ignored, sampled export log with x/y/scale/boxPx in `Locale.US`. Export
decoder/audio/mux paths untouched; no splash/UI/navigation changes.

## A. Animated text: preview == export

1. **Reference (editor):** project 9:16 + video + text overlay `Kotlin`
   (entrance LETTER + FADE or SLIDE_UP). Screenshot the block at rest —
   size and letter spacing are the ground truth. The editor now lays the
   letters per frame through the SAME function the exporter uses
   (`AnimatedTextLayout.segmentPlacement`), so nothing can differ by
   construction; this step records the reference.
2. **Export** the project. The text must have the SAME size and letter
   spacing as the editor, at rest AND mid-entrance (scrub the exported
   file). No vertical squash, no letter spread.
3. **Fixed-aspect check:** same project with aspect 1:1 (and the source
   video 16:9 or 720×1600) — the text must still match the editor. The
   layout's ONLY aspect input is the project frame; the source aspect does
   not enter anymore (and since v0.5.8 never entered the y-scale).
4. **Overlay gate (new):** while a seek is buffering (or on a clip with no
   video geometry), the overlays briefly disappear and come back — never
   float on a stale/black frame. Diagnostics show `OVERLAY_GATE`
   suspended/resumed with `videoSource` + `playerState`. Projects WITHOUT
   clips (overlay-only) keep drawing overlays always.
5. **Diagnostics evidence (export side):** during the entrance, one
   `ANIM_TEXT_FRAME` per 100 ms with:
   - `x`, `y`, `scale` — the SAME fractions the preview's `OVERLAY_DRAW`
     logs;
   - `boxPx` — the composite block's FINAL size on the output frame, and
     `framePx` — the output resolution used as basis (e.g. `1080x1920`);
   - per-segment `op/sc/wh/@cx,cy` — block-local px.
   All decimals use `.` (Locale.US) regardless of device language.
6. **1:1 comparison:** pick one sampled frame — `OVERLAY_DRAW` (preview)
   and `ANIM_TEXT_FRAME` (export) must show the same `x`, `y`, `scale`;
   `boxPx`/`framePx` (export) must equal the preview `boxPx` ratio scaled
   by `framePx / contentPx` of the stage. Same `ovId` (fingerprint) joins
   the trail (`ANIM_TEXT_BUILD` correlates the build).

## B. Regression guards

7. **Plain text / image overlays:** without entrance animation, static
   text and images still match between preview and export (the static
   `KeyframedOverlay` path is untouched).
8. **Gestures still work:** move/scale/rotate an animated text block —
   segments follow the parent, selection chrome wraps the block, auto-key
   commits once per gesture.
9. **No stale rebuild:** edit a clip colour/trim and quickly revert — the
   diagnostics may show `PREVIEW_REBUILD_SKIPPED` (stale revision ignored);
   the preview must always reflect the CURRENT edit state.
10. **Icon + splash untouched:** launcher icon still the v0.5.8 OV
    monogram; splash screen unchanged; export of a video WITHOUT text
    still works end-to-end (decoder/audio/mux paths untouched).
