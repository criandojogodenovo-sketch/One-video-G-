# Device Test Checklist — v0.5.4 (stage collapse + image persistence)

Fixes the v0.5.3 device audit (`docs/AUDIT_v0.5.3_problems.md`): the preview
surface collapsed 720×405 → 53×30 px (stage starved by fixed chrome —
landscape, or the transform panel expanded), overlay gestures then corrupted
saved transforms, and image overlays could lose their source resource after
grant loss/source deletion. Export untouched.

Legend: [ ] pending · [x] pass · [~] partial · [-] n/a
`diag` = diagnostics report (clipboard) right after the step.

## 1. Transform panel can no longer starve the preview (portrait)
- [ ] Add a text or image overlay (it gets selected) and EXPAND the
      TransformQuickPanel (tap the title/arrow): the preview STAYS a full
      usable stage; the five sliders scroll inside the capped panel.
- [ ] Use the capped sliders (drag each of the 5): the overlay moves/scales/
      rotates/fades exactly as before; one undo step per slider release.
- [ ] `diag`: NO `OVERLAY_GESTURE_REJECTED`; `VIEWPORT_GEOMETRY` never logs
      `surfaceW=53 surfaceH=30` during this flow.

## 2. Landscape / short windows usable
- [ ] Rotate the device to landscape in the editor: the preview keeps a
      fixed usable stage (200 dp); the column scrolls vertically so the
      transport bar, timeline and tool row are all reachable.
- [ ] Drag an overlay in landscape: the move follows the finger 1:1 and the
      committed position matches what the preview shows.
- [ ] Rotate back to portrait: layout is exactly as before (flex stage,
      no visual change).

## 3. Gesture corruption hard-stop (evidence only)
- [ ] `diag` after normal editing: `OVERLAY_GESTURE_REJECTED` does NOT
      appear (the layout guards make degenerate spaces unreachable).
- [ ] Negative path (optional, adb): `adb shell settings put system
      display_size_forced 720x213` (very short window) → editor opens,
      gestures on the (tiny) stage do nothing harmful, no transform
      corruption; `diag` shows `OVERLAY_GESTURE_REJECTED` with contentW/H.
      Reset with `adb shell settings delete system display_size_forced`.

## 4. Image persistence (new imports)
- [ ] Import an image overlay, export the project, kill the app, reopen:
      the image still renders (local `file://` copy).
- [ ] `diag`: the import op shows `IMAGE_LOCAL_COPY` = "image persisted to
      app storage (grant-loss-proof)" before `OVERLAY_CREATE`.
- [ ] Delete/rename the SOURCE image in the gallery/files app, reopen the
      project: the overlay STILL renders (the app-local copy is used).
- [ ] Export with that overlay: the image appears in the exported file
      exactly where the preview showed it.
- [ ] Old projects (created before v0.5.4) still open, render and export
      their `content://` image overlays unchanged.

## 5. Regression guards (v0.3.x–v0.5.3 contracts)
- [ ] Text/image overlay creation in an EMPTY project still works (5 s
      floor); overlay edits never restart the video decoder (no
      `PREVIEW_REBUILD` per edit).
- [ ] Selection chrome (frame + ✕ + rotate + scale) works in portrait AND
      landscape; locked layers draw bounds-only chrome and reject edits.
- [ ] Undo/redo still reverts one step per gesture/slider.
- [ ] With-video export and empty-project export still succeed (export path
      untouched in v0.5.4).
- [ ] "Novo projeto" (name → video → create once) and "Projeto vazio"
      behave exactly as in v0.5.2/v0.5.3.
