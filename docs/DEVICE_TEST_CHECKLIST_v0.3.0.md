# One Video G — v0.3.0 Physical-Device Test Checklist (realme RMX3624, Android 13)

Every step below must be executed on the physical device. NOTHING in this
file has been device-verified yet; CI/JVM results (211 unit tests, 0
failures; signed release build + apksigner verification) are listed in the
release notes and are the ONLY verification done so far.

## 1. TEMPORAL BUG — image bar outside the video range (P0, the reported bug)

1. Import the same ~54.9 s video used in the report. Import an image.
2. Drag the image bar so it starts AFTER the video end (e.g. 60–65 s).
   Expected: the ruler region past the video end is shaded (orange hatch);
   the bar is dimmed and labelled "fora do vídeo / outside video"; the
   total shows the VIDEO length (54.9 s), NOT 65 s.
3. Play from the start to the end: playback must run normally and STOP at
   the video end (playhead parked at 54.9 s). No freeze, no black hang with
   the pause icon showing.
4. With the playhead parked at the end, press PLAY again: the video must
   RESTART from 0 and play (replay-after-end fix).
5. Press pause mid-playback, then play: must resume normally.
6. Scrub to the very end (drag the ruler to 54.9 s), press play: must
   restart from 0.
7. Drag the image bar back inside the video range: the bar returns to full
   opacity, the label drops the suffix, and it renders in the preview at
   its window.
8. Seek backwards/forwards repeatedly (±10 s transport + ruler taps) while
   the out-of-range image exists: no freeze, no playhead jump-back.
9. Reopen the app (kill + relaunch) after step 2–8: project loads, video
   plays, timeline unchanged.
10. Export this project: the file duration must be ~54.9 s (video length),
    the exported file valid, and the reported duration in the export
    screen/diagnostics must MATCH the file (no 65 s anywhere).
11. Check the diagnostic report afterwards: `video=none` events (if any)
    must carry `playerState=ENDED` + `playheadInsideVideo=false` — the
    report must no longer show video=none / videoW=0 h=0 presented as a
    video loss after normal playback.

## 2. Partial overlap (P0)

12. Position the image bar to cross the video end (e.g. 50–60 s on the
    54.9 s video): it must render ONLY inside 50–54.9 s in the preview and
    in the exported file; nothing beyond the video end.
13. Export and verify: the overlay appears in the file exactly during the
    intersection window.

## 3. Layer visibility + lock (P1)

14. Layers panel: toggle the eye icon on a text layer — it disappears from
    the preview immediately; its timeline bar dims with the hidden suffix.
15. Export with the layer hidden: the exported file must NOT contain it.
16. Toggle the eye back: the layer reappears.
17. Lock a layer (padlock icon): dragging its timeline bar does nothing;
    dragging/rotating/scaling it in the preview does nothing; delete via
    the red chip does nothing; the layers panel disables front/back/delete
    for it. Unlock: everything works again.
18. Undo/redo after hide/lock toggles: each toggle is one undo step.

## 4. Gizmos regression (P1 — must not have regressed)

19. Select/move/scale/rotate an overlay with gestures; selection must
    survive a preview effects rebuild (e.g. move a keyframe while the
    overlay stays selected).
20. Two overlapping overlays: tap selects the top one; the z-order buttons
    still work for unlocked layers.

## 5. Timeline touch targets (P2)

21. Trim an overlay/audio bar with the edge handles: the 24 dp-wide zones
    must be easier to grab on the phone than in v0.2.5; body drags still
    move, handles still trim (no accidental mode switch).
22. Zoom in/out (buttons): ruler labels, bars and the playhead line stay
    aligned; scrub mapping stays correct at 0.5x and 4x.

## 6. Audio-only project playback (P2)

23. Create a new project WITHOUT video, add an audio clip: pressing play
    must advance the playhead in real time and the audio must NOT stutter
    or restart every ~300 ms; at the end the playhead stops and the play
    state clears.

## 7. Export / identity (P0)

24. Export a normal video+overlay+audio project: success, valid file,
    duration matches the timeline, overlays/audio in place (Transformer
    threading and ExportGate behaviour unchanged from v0.2.4/0.2.5).
25. Rapid double-tap export: only one export runs.
26. Cancel mid-export: clean cancel, a new export can start immediately.
27. The APK must install OVER the v0.2.5 build (same signing identity,
    versionCode 8 > 7) without uninstalling — preserving projects.
