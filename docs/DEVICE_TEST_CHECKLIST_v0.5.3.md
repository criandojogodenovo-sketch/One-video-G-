# Device Test Checklist — v0.5.3 (overlay preview Compose layer)

Bug fixed: text and image overlays did not appear in the preview — with
video AND in empty projects. The device log showed `ACTIVATION
activeOverlays=1` (time logic OK) but zero draw evidence; in empty projects
there was not even `EFFECT_ATTACH`/`PREVIEW_REBUILD` (expected: the preview
GL list has carried no overlay textures since v0.3.1 — the old Compose
draw-phase bitmap path was the silent failure). v0.5.3 renders overlays as
plain Compose composables in a layer above the preview. Export untouched.

Legend: [ ] pending · [x] pass · [~] partial · [-] n/a
`diag` = Diagnostics report (Editor → export/settings screen) exported to
clipboard right after the step.

## 1. Text overlay — project WITH video
- [ ] Add a text overlay over a video clip, WITHOUT selecting it afterwards
      (tap empty stage to deselect): the text IS visible on the preview.
- [ ] Move the playhead outside the text window: text disappears; back
      inside: reappears (sync with the timeline, no selection needed).
- [ ] Tap the text: selection frame + ✕ + rotate knob + scale corner appear
      ON TOP of the text. Drag moves it; pinch resizes; twist rotates;
      ✕ deletes.
- [ ] `diag`: events contain `OVERLAY_DRAW` per active overlay with
      `kind=TEXT`, x/y/scale/rotDeg/opacity/visible=true and
      `content=text block=WxH lines=N chars=M`.
- [ ] Export the project: the exported frame matches the preview position/
      size/rotation/opacity of the text (same normalized coordinates).

## 2. Image overlay — project WITH video
- [ ] Add an image overlay, deselect: image IS visible without selection.
- [ ] `diag`: `OVERLAY_DRAW` with `kind=IMAGE` and
      `content=image bitmap=WxH` (and a prior `DECODE_END` from the decode).
- [ ] Export: image appears where the preview showed it.

## 3. Empty project (no video at all)
- [ ] Create "Projeto vazio", add ONLY a text overlay: it renders on the
      black 16:9 stage; the "A linha temporal está vazia" hint is gone.
- [ ] Add an image overlay too: both render, stacked by z-order.
- [ ] Tap a text: the selection chrome (frame + handles) appears in the
      empty project too (previously it did not). Gestures work.
- [ ] Press play: the playhead advances (own clock), overlays appear/
      disappear at their windows; audio imported in an empty project plays
      in sync; at the end playback stops parked at the end; pressing play
      again restarts from 0.
- [ ] `diag` in the empty project: `OVERLAY_DRAW` events present (this was
      the project type with zero draw evidence before the fix).
- [ ] Export the empty project: video succeeds (v0.5.2 path untouched) and
      the overlays appear in the exported file.

## 4. Text animation (v0.5.0 contract preserved)
- [ ] A text overlay with a word/letter entrance animation renders its
      segments; selection/gestures still target the whole block (single
      chrome box around all segments).
- [ ] Dragging an animated block moves ALL segments together.

## 5. Regression guards
- [ ] Overlay edits (move/resize/text edit) never restart/flash the video
      decoder (effectiveSignature contract — no `PREVIEW_REBUILD` per edit).
- [ ] Hidden layer (eye off) draws nothing (no `OVERLAY_DRAW` for it).
- [ ] Locked layer: visible, selectable, draws bounds-only chrome, rejects
      move/scale/rotate/delete gestures.
- [ ] With-video export and empty-project export still succeed (export path
      untouched in v0.5.3).
- [ ] Undo/redo still reverts overlay moves one step per gesture.
