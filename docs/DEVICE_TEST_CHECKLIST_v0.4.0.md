# Device test checklist — One Video G v0.4.0 (Etapa D + E)

Physical verification for the gizmos (Etapa D) and the contextual properties
panel + layer list (Etapa E). NOT run automatically — nothing below may be
claimed as verified until executed on a real device (same class as the
v0.3.0/v0.3.1 test device: Android 13, 4 GB RAM).

## Setup

1. Install `OneVideoG-v0.4.0-release.apk` over the existing install
   (signature must be accepted — same keystore identity as v0.2.x–v0.3.x).
2. Open a project from an earlier version (backward compatibility) AND a new
   project with a video clip.

## D1 — Selection

- [ ] Tap a text overlay in the preview: frame + handles appear around it.
- [ ] Tap an image overlay: same; only one selection at a time.
- [ ] Two overlapping overlays: tapping selects the one drawn ON TOP;
      tapping again on the other one (if partially visible) selects it.
- [ ] Tap empty preview area: selection clears.
- [ ] Timeline bar tap and layer list tap select the same overlay and the
      frame moves to it.

## D2 — Move

- [ ] Drag the selected overlay body: position follows the finger
      continuously (no jumps, no stutter) and stays inside the content area.
- [ ] Release: position persists; undo (top bar) reverts the whole drag in
      one step; redo re-applies it.

## D3 — Scale

- [ ] Drag the bottom-right corner handle: overlay grows/shrinks, aspect
      ratio preserved, minimum/maximum limits respected.
- [ ] With one finger dragging the overlay body, add a second finger and
      spread/pinch: the overlay resizes uniformly and keeps moving with the
      finger centroid.
- [ ] Scale slider (quick panel and full sheet) shows the same value as the
      gizmo after the gesture (as a percentage).

## D4 — Rotate

- [ ] Drag the top rotate knob slowly through the "up" position and keep
      going: rotation continues smoothly — NO sudden ~360° flip at any point
      (this was the quadrant-jump bug; the seam between -180° and +180° must
      be invisible).
- [ ] Two-finger twist on the overlay rotates it smoothly.
- [ ] Rotate, release, reopen the project: rotation persisted exactly.
- [ ] Rotation slider shows degrees and matches the gizmo state.

## D5/D6 — Coordinates + persistence

- [ ] 16:9 project: place an overlay near a corner, drag it to each of the
      four corners — it never escapes the video content area into the
      letterbox bars.
- [ ] Switch aspect to 9:16: overlay position stays at the same RELATIVE
      spot; export the project and compare a frame: overlay position, scale,
      rotation and opacity match the preview.
- [ ] Kill the app from recents, reopen: position/scale/rotation/opacity all
      persisted (autosave).

## E1 — Contextual properties panel

- [ ] Select an overlay in the preview: a compact one-row panel appears
      between the preview and the transport bar with the layer name.
- [ ] Expand it: sliders for Position X, Position Y, Scale (%), Rotation (°),
      Opacity (%).
- [ ] Move a slider: the overlay updates in the preview; release: one undo
      step per property.
- [ ] Drag the overlay with the panel open: panel values follow the gizmo
      (bidirectional sync through the same model).
- [ ] Opacity 0% makes the overlay invisible in preview AND in export.

## E2/E3 — Layers + visibility/lock

- [ ] Layer list (Layers tool): all overlays listed with type icons; the
      active one highlighted; z-order buttons work.
- [ ] Hide a layer: it disappears from preview and export but stays in the
      list; show it again: it returns.
- [ ] Lock a layer: gizmo chrome shows bounds only (NO handles), dragging it
      does nothing, sliders are replaced by the locked hint; unlock: full
      gizmos return.
- [ ] Old project (created in v0.2.x/v0.3.x) opens with all layers intact.

## E4 — Mobile ergonomics / no interference

- [ ] With the quick panel EXPANDED, the preview element is still reachable
      (panel is below the preview; collapse it when needed).
- [ ] Play/pause, ±10 s, scrubbing and import work with an overlay selected
      and the panel open.
- [ ] Transform edits do NOT cause the video to flash/reload (no decoder
      rebuild — v0.3.1 contract preserved).
- [ ] On a small screen, handles remain grabbable (≥ 24 dp zones).

## E5 — Undo/redo

- [ ] Sequence: move → scale → rotate → opacity change: four undo steps (one
      per logical operation), redo replays them in order.
- [ ] Undo/redo buttons enable/disable correctly.

## Regression sweep

- [ ] Playback: play/pause/seek across the whole timeline; replay after end.
- [ ] Export completes and the file plays with all overlays correct
      (position/scale/rotation/opacity/timing).
- [ ] No diagnostics errors in the in-app log during the whole session.

**Status: PENDING — must be executed on a physical device. Nothing in this
checklist may be reported as "verified" without that run.**
