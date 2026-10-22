# Device test checklist — v0.5.7

Mission: preview crash when adding keyframes (PLAYER_ERROR
VIDEO_FRAME_PROCESSING_FAILED, GlException "Error making context current"
0x3006 EGL_BAD_CONTEXT) + the six-point prescription. Export engine untouched
except the prescribed single-draw animated-text texture.

Legend: [x] verified locally (JVM/build level) · [ ] PENDING on device (this
section is what you must confirm on the realme RMX3624).

## 1. Keyframes + preview stability (pontos 1-4)

- [ ] With a project that has a video clip: add an overlay, add keyframes
      (sheet button + gizmo auto-key + timeline marker drag) while paused,
      while playing and right after a scrub — the preview never dies
      (no PLAYER_ERROR / black frame).
- [ ] If the preview player DOES hit an error, it recovers by itself
      (diagnostics: PLAYER_ERROR followed by PLAYER_RECOVERED) and stays at
      the playhead; pressing play resumes normally.
- [ ] Rapid structural edits (trim/speed/colour back-to-back) produce ONE
      PREVIEW_REBUILD with `debounced=true` ~300 ms after the last edit.

## 2. video=none geometry (ponto 5)

- [ ] VIEWPORT_GEOMETRY in a new diagnostic report shows videoW/videoH from
      the clip (videoSource=clip) for projects with video, even while the
      decoder has not reported a size (playerState IDLE/BUFFERING).

## 3. Animated text = single draw (ponto 6)

- [ ] Export a project with an animated text overlay (FADE/SLIDE_UP/POP,
      WORD and LETTER units): the video shows the same staggered entrance as
      v0.5.6 (no visual regression).
- [ ] Diagnostics EFFECT_ATTACH for export shows fewer textures (1 per
      animated overlay instead of N segments).
- [ ] Plain text and image overlays export unchanged.

## 4. No regressions

- [ ] Preview: speed/colour/aspect edits still apply (PREVIEW_REBUILD
      debounced=true path), clip changes still seamless.
- [ ] Full export (video + overlays + audio) completes and the output is
      verified (export DONE event) — export engine untouched.

## Executed locally (evidence)

- compileReleaseKotlin green after EACH point (JDK 17 + SDK 34).
- testReleaseUnitTest: 314 tests / 0 failures / 0 errors / 0 skipped
  (311 baseline v0.5.6 + 3 new ExpandedOverlaysAnimationSpecTest).
