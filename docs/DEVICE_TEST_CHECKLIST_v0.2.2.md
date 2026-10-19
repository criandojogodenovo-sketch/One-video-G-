# One Video G — v0.2.2 Physical-Device Test Checklist (realme C33, Android 13)

Every step below must be executed on the physical device. NOTHING in this file
has been device-verified; the CI/JVM results are listed in the release notes.

## 1. Shared overlay render pipeline (P0)
1. Open a project with a video clip; add a TEXT overlay and an IMAGE overlay.
2. PLAY: overlays must appear/disappear exactly at their timeline windows,
   animated by keyframes if present, at the size/position/opacity shown.
3. Set opacity to 50%: the overlay must look 50% opaque (no darker/double
   ghost while paused).
4. Give the clip a colour adjustment (e.g. saturation 0): the preview colour
   must match the exported file.
5. Set clip speed to 2x: overlays must advance in real time while the video
   runs 2x; export and re-check the same timing.
6. Rotate an overlay 30°: preview rotation direction must match the export.
7. Use a 9:16 aspect project with a 16:9 source: overlays must sit in the
   same relative position in preview and in the exported file (letterbox
   parity).

## 2. Overlay editing in preview (P0)
8. Tap an overlay in the canvas once: it must select (orange bounds + three
   handles: top rotate knob, top-right red delete chip, bottom-right scale
   knob).
9. Drag the body: the overlay follows the finger; on release the position
   persists (undo works with one step back).
10. Drag the bottom-right knob outward/inward: size scales; release persists.
11. Drag the top knob in a circle: rotation follows the finger (clockwise
   drag = clockwise rotation); release persists.
12. Tap the red chip: the overlay is deleted immediately.
13. Tap empty canvas: selection clears.
14. Hit-test near edges: handles must trigger even when close to screen
    edges; two overlapping overlays: tapping selects the one on top.

## 3. Timeline lanes and bars (P0)
15. Create two overlapping text overlays: each must appear on its OWN lane
    row (never hiding each other).
16. Make two overlays touch exactly (end == start): they share one lane.
17. Tap a bar: it selects and opens its editor (no playhead jump).
18. Drag a bar body: it moves in time; keyframe diamonds move with it;
    start never goes below 0.
19. Drag the LEFT edge grip: start trims, duration shrinks, never below
    0.15 s; drag the RIGHT grip: end trims.
20. Audio bar: left grip slides the audio in time (moves start + trim
    together); right grip extends/shortens within the source length.
21. Drag the ruler: the playhead follows the finger EXACTLY (no jump, no
    revert when releasing — at any zoom, paused or playing).
22. Tap the ruler: the playhead lands under the finger (verify at zoom 1x
    and 4x — this is the density-bug regression check).
23. Playhead line must stay aligned with the ruler and the bars at all
    zoom levels.
24. Clip blocks: width proportional to duration (a 60 s clip is ~4x wider
    than a 15 s clip at the same zoom).

## 4. Split / trim / z-order (P1)
25. Playhead inside a clip: SPLIT creates two clips; message shown if too
    close to the edges.
26. Layers panel: "send to back" moves the overlay below the others in the
    canvas AND in the export.

## 5. Export at ~1 minute and beyond (P0 — NOT DEVICE-TESTED)
27. Export a 30 s project (with overlays + audio) -> success, plays, duration
    correct.
28. Export a 60 s project -> success.
29. Export a 2 min and (if storage allows) a 5 min project -> success.
30. Export with an audio clip that extends beyond the video end -> export
    succeeds (audio clamped to the video length; no silent failure).
31. Kill nothing; let the export run with the screen OFF for a 2 min export
    -> completes (foreground service keeps it alive).
32. On any failure: the error names the stage (storage/encoding/muxing...)
    and cache/exports/diagnostics.log gains a sanitized line.

## 6. Regression (must still work)
33. Autosave/restore, PT/EN strings for every new control, no watermark,
    existing projects load (v1 and v2), play/pause/transport below canvas.
