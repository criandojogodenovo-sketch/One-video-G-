# Device test checklist — One Video G v0.5.0 (Fases F + G)

Physical verification for the keyframe editing UX (Fase F) and the
per-word/per-letter text entrance animation (Fase G). NOT run automatically —
nothing below may be claimed as verified until executed on a real device
(same class as the v0.3.x–v0.4.0 test device: Android 13, 4 GB RAM).

## Setup

1. Install `OneVideoG-v0.5.0-release.apk` over the existing install
   (signature must be accepted — same keystore identity as v0.2.x–v0.4.0).
2. Open a project created in v0.4.0 (backward compatibility) AND create a
   new project with a video clip, one text overlay and one image overlay.

## F1 — Auto-key

- [ ] Select a text overlay with NO keyframes and drag it: base transform
      changes (behaviour identical to v0.4.0); no keyframe appears.
- [ ] Add a keyframe (Keyframe section). Move the playhead onto it
      (± 25 ms — the quick panel shows "● keyframe") and drag: the EXISTING
      keyframe updates (count unchanged, time unchanged).
- [ ] Move the playhead between keyframes ("○ between"): drag creates a NEW
      keyframe at the snapped playhead (50 ms grid).
- [ ] Same rules through the quick-panel sliders and the sheet Transform
      sliders (one undo step per slider release, one per gesture).

## F2 — Keyframe markers on the timeline

- [ ] Tap a keyframe dot on an overlay bar: playhead seeks to it.
- [ ] Drag a dot: it follows the finger and lands on a 50 ms slot; releasing
      commits one undo step.
- [ ] Drag a dot onto another dot: it lands on the nearest free 50 ms slot
      (or springs back when the area is full) — the other keyframe is NEVER
      deleted (pre-v0.5.0 this deleted it).
- [ ] A locked layer's markers are tap-to-seek only; dragging does nothing.

## F3 — Keyframe navigation

- [ ] With keyframes present, the sheet shows prev/next buttons: the
      playhead jumps to the nearest keyframe before/after it.
- [ ] Buttons are disabled when the overlay has no keyframes.

## F4 — Quick-panel indicator

- [ ] The quick panel shows "● keyframe" when the playhead is on one and
      "○ between" otherwise; the state flips as the playhead moves.

## G1/G2 — Model + segment layout

- [ ] A project saved in v0.4.0 opens with text/image intact (no
      animation field); saving from v0.5.0 and reopening keeps everything.
- [ ] Animated text without a background colour looks EXACTLY like the
      static text at the end of the animation (same position/wrap/size).

## G3/G4 — Entrance animation preview + export

- [ ] FADE: words/letters appear one after another (stagger) inside the
      overlay window; each stays visible after its entrance (hold).
- [ ] SLIDE_UP: each unit starts slightly below and slides up while fading.
- [ ] POP: each unit overshoots slightly and settles.
- [ ] TYPEWRITER: units appear instantly, in sequence.
- [ ] Export the project and compare with the preview: same stagger order,
      same final layout, same timing (parity).
- [ ] Hidden layer: no segments render in preview or export; locked layer:
      segments render but nothing can be edited.

## G5/G6 — Limits + UI

- [ ] Long text with LETTER: the sheet shows the limit warning; the
      animation falls back to WORD (still animates).
- [ ] Text with more than 96 words: warning shown; animation does nothing
      harmful (static text remains).
- [ ] Preset/unit/duration controls commit with one undo step each.
- [ ] Choosing an animation does NOT reload/flash the video (no decoder
      rebuild — v0.3.1 contract preserved).

## Regression sweep

- [ ] v0.4.0 gizmos still work on non-animated overlays (move/scale/rotate/
      pinch/twist, no rotation seam jumps).
- [ ] Playback: play/pause/seek across the whole timeline; replay after end.
- [ ] Export completes and the file plays with all overlays correct
      (position/scale/rotation/opacity/timing; animated text included).
- [ ] Old project (created in v0.2.x–v0.4.x) opens with all layers intact.
- [ ] Undo/redo: auto-key edits, marker moves and animation changes each
      take exactly one step.
- [ ] No diagnostics errors in the in-app log during the whole session
      (TEXT_ANIM_LIMIT warnings only when the limit is actually hit).

**Status: PENDING — must be executed on a physical device. Nothing in this
checklist may be reported as "verified" without that run.**
