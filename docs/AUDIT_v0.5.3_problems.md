# Audit report — v0.5.3 simultaneous problems (projects, images, text, gizmos)

Scope: creation and presentation of projects/images/text/gizmos, plus the two
geometry symptoms from the device report (`videoW=0/videoH=0` and the preview
surface change `720×405 → 53×30`). Export path audited but NOT modified.

Device: realme RMX3624, Android 13 (SDK 33), 720 px-wide display
(~411 dp at density ≈ 1.75), portrait-first editor.

---

## 1. PROVEN — preview surface collapse `720×405 → 53×30`

### Evidence chain (exact numbers)
The device viewport in the healthy state is exactly `720×405 px`:
`EditorScreen` gives the preview `Box(Modifier.weight(1f))` the leftover
height; `PreviewStage` fits a 16:9 rect into it via
`OverlayLayout.fitRect(containerW, containerH, contentAspect)`.

- Container width = screen width = **720 px** (density ≈ 1.75).
- Healthy leftover height ≥ 231 dp → `fitRect` yields **720×405 px** — the
  first value in the device report.
- When the leftover stage height falls to **≈ 17 dp (30 px)**,
  `fitRect(411 dp, 17 dp, 1.778)` = `(30 × 1.778) × 30` = **53×30 px** — the
  second value in the device report. No other code path can produce 53×30 on
  this device; the collapsed container height is the only input that fits.

### Root cause
`EditorScreen.kt` stacks fixed-height children above/below the stage:

| child | height |
|---|---|
| EditorTopBar | ≈ 52 dp |
| TransformQuickPanel (collapsed) | ≈ 40 dp |
| TransformQuickPanel (**expanded**) | ≈ **370–380 dp** (5 sliders × ~66 dp + title row) |
| TransportBar | ≈ 50 dp |
| TimelineStrip | **276 dp fixed** (zoom row 48 + tracks 220 + padding 8) |
| ToolBarRow | ≈ 56 dp |

Portrait window ≈ 842 dp. With the QuickPanel **expanded**, the fixed chrome
sums ≈ 804–822 dp → the `weight(1f)` stage receives ~17–38 dp → surface
53×30 (or ~68×38). In **landscape** the window is ≈ 341 dp < 474 dp of
collapsed chrome → the stage receives 0 → the preview disappears entirely
(landscape has never been usable; the manifest does not lock orientation).

### Consequences (the "images, text, gizmos" symptoms)
- The video, overlays and selection chrome all render inside the 53×30 box —
  visually "everything disappears / draws wrong".
- The overlay gesture layer stays ACTIVE in the collapsed space: placements
  are computed against `contentWidth = 53 px`, so `pan.x / 53` turns a 20 px
  drag into a 0.38 normalized jump, and the 24 dp gizmo zones cover the whole
  box — a tap near the top-right of the selected overlay classifies as
  DELETE and **deletes the overlay without a drag**. Committed transforms are
  therefore corrupted ("draws/transforms the wrong object, invalid geometry")
  and persist in project.json.

### Reproduction
1. Open a project, add a text or image overlay (it gets selected).
2. In the TransformQuickPanel tap the title/expand arrow.
3. The preview collapses to a sliver; `VIEWPORT_GEOMETRY` logs
   `surfaceW=53 surfaceH=30` (deduped once per geometry state).
   Same collapse class by rotating to landscape (stage height 0).

## 2. PROVEN — `videoW=0/videoH=0` is expected (not a defect)

`PreviewStage.onVideoSizeChanged` (v0.2.5 rule): a 0×0 callback means "this
item has no video track" (audio-only item / empty project) and is logged as
`PREVIEW_RENDER video=none`, NOT as geometry. In a video-less project the
decoder never reports a size, so `VIEWPORT_GEOMETRY` legitimately logs
`videoW=0 videoH=0` — the value stays `VideoSize.UNKNOWN` by design. In a
project WITH video, `videoW=0` would mean the decoder never produced output
(prepare/effects failure) — that case is already instrumented via
`PLAYER_ERROR` / `PREVIEW_RENDER video=none`.

## 3. PROVEN — image overlay loses its source resource (no recovery)

`EditorViewModel.addImageOverlay` stores the picked `content://` URI and
calls `takePersistableUriPermission` **best-effort** (the log can already
show `persisted=false`; some providers refuse persistable grants, and the
system caps persistable grants). If the grant is lost, the source is moved/
deleted, or the provider is virtual/cloud:
- Preview: `OverlayCanvas.failedImageIds` marks the decode as failed and
  never retries (v0.2.4 rule) — the overlay stays in the project but renders
  nothing, silently.
- There is NO relink flow for overlays (video clips have `relinkClip`).
- Export silently composes without the image (`imageBitmap == null` → no
  texture).

Fix in this round (FASE 2): the import copies the image into app storage
(`files/overlay_media/img_<uuid>.jpg`, bounded 1024 px decode, JPEG 88) and
the overlay stores the `file://` URI. `contentResolver.openInputStream`
reads `file://` transparently, so the preview decode and the export
(`ClipEffectsFactory` → `OverlayBitmapRenderer.imageBitmap`) keep working
UNCHANGED — no export edit. Existing projects keep their `content://` URIs.

## 4. Audited WITHOUT defect (no change)

- **Project creation**: "Novo projeto" (name → picker → create exactly once;
  cancel creates nothing), "Projeto vazio" (5 s floor via
  `ProjectMath.MIN_EMPTY_DURATION_MS`), double-tap guard (`creating` flag),
  auto-delete of completely empty projects on exit (autosave-cancelled +
  re-delete). The `open()` after `newProjectWithVideo` cannot race the video
  import: `open()` early-returns when `_ui.value.project?.id == projectId`.
- **Project presentation**: list reads `repository.list()` on re-entry;
  thumbnails, duration and dates come from the same `ProjectMath`/repository
  the editor uses.
- **Text lifecycle**: creation (UUID, own lane, full-span window), debounced
  text edits, style edits, auto-key transform edits (`applyTransformEdit`),
  keyframe add/move/delete with 50 ms snap — one implementation shared by
  preview and export through `KeyframeEvaluator`/`OverlayLayout` (pure,
  unit-tested).
- **Gizmos** (at normal stage size): seam-safe incremental rotation,
  pinch/twist, locked bounds-only chrome, segment-block chrome bounding box —
  all preserved intact.
- **Timeline**: single `TimelineScale` mapping, lane packing, trim handles,
  keyframe markers, dead-zone shading.

## 5. Unconfirmed hypotheses (need a device diag after the fix)

- **H1** — which action triggered the user's collapse (expanded
  TransformQuickPanel vs landscape rotation vs a short-window state). The
  mechanism is proven; the trigger depends on the session. After the fix the
  diag gains `OVERLAY_GESTURE_REJECTED` (degenerate space) and
  `VIEWPORT_GEOMETRY` keeps deduped states, so a future report attributes it
  conclusively.
- **H2** — whether the user's image failure was `persisted=false` or the
  source file disappearing (both covered for NEW imports by the local copy;
  old imports keep SAF semantics).

## 6. Fixes applied (FASE 2) — one point at a time

1. **The stage can no longer collapse** (`EditorScreen` + `TransformQuickPanel`):
   the expanded panel's sliders block is capped at 240 dp with its own
   vertical scroll; a compact-window branch (< 660 dp of window height, e.g.
   landscape) renders the editor in a vertically scrollable column with a
   fixed 200 dp stage; the normal portrait branch adds `heightIn(min = 140.dp)`
   as a hard floor. Portrait layout is unchanged when the window fits.
2. **Degenerate-space gesture guard** (`OverlayCanvas` + `OverlayLayout`
   `isUsableContentSpace`): no gesture handling (move/scale/rotate/DELETE,
   tap-classification included) when the content space is below 64 px on
   either axis — the corruption path is closed even if some future layout
   regresses the stage; logged as `OVERLAY_GESTURE_REJECTED`.
3. **Image persistence** (`OverlayMediaStore` + import copy): new image
   overlays reference an app-local `file://` copy, immune to grant loss and
   source deletion; falls back to the SAF URI if the copy fails.
