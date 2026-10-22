# One Video G — physical device test checklist (v0.5.8)

**APK:** `OneVideoG-v0.5.8-release.apk` (release "latest", versionCode 19)
**Scope:** (A) animated text export parity + diagnostics, (B) new AI-generated
launcher icon. Export engine, editor logic, navigation and splash screen
untouched.

## A. Animated text export parity (mission "Kotlin")

1. **Editor reference frame** — create a project (9:16), add a video, add a
   text overlay `Kotlin`, set entrance animation (LETTER unit, FADE or
   SLIDE_UP). Note how the block looks at rest (size + letter spacing) —
   this is the ground truth.
2. **Export the project.** The exported text must have the SAME size and the
   SAME letter spacing as the editor (at rest). The known pre-v0.5.8 symptom
   (smaller text, wide letter gaps) was caused by the export using the
   SOURCE aspect instead of the PROJECT aspect for the overlay y-scale —
   visible when the project aspect differs from the video's aspect
   (e.g. 720×1600 video in a 9:16 or 1:1 project).
3. **Fixed-aspect check (the fixed bug):** same project, switch the project
   aspect to 1:1 (or 9:16 if the source is 720×1600) and export again — the
   text must NOT be squashed/stretched and must match the editor preview.
4. **Mid-animation check:** scrub the exported video during the entrance —
   letters appear with the same stagger/timing as the editor playhead shows.
5. **Diagnostics evidence:** after the export, pull the diagnostics file and
   confirm the new events exist:
   - `ANIM_TEXT_BUILD` — one per animated text (unit, segments, blockPx,
     frameAspect, contentAspect). `frameAspect` must equal the project ratio
     for fixed-aspect projects.
   - `ANIM_TEXT_FRAME` — during the entrance (one per 100 ms): progress
     (`projectMs`), `parentScale`, `blockFracW/H`, `blockPx` and per-segment
     `op/sc/wh/@cx,cy`. Compare `blockFracW` with the editor's
     OVERLAY_DRAW `scale` — they must match.

## B. New launcher icon (mission ATTTY.png)

6. **Installer/launcher icon** — install the APK; the launcher must show the
   new orange `OV` monogram on the dark #101116 background (square mask on
   most launchers; circular on round-icon launchers).
7. **Themed icons (Android 13+):** enable themed/dynamic colour in the
   launcher settings — the monochrome silhouette (white OV) must appear.
8. **Adaptive shape:** long-press the app icon — the icon must reshape
   (circle/squircle/rounded square) WITHOUT clipping the OV monogram
   (safe-zone compliant foreground at ≤66/108).
9. **Splash screen unchanged:** opening the app must show the SAME splash as
   v0.5.7 (no splash resource was touched — verified: no splash references
   exist in the manifest/resources).
10. **All previous features** — one smoke pass: new project, import video,
    image overlay, keyframes on an overlay (no preview crash — v0.5.7 fix
    still holding), export, audio.

## Regression guards (JVM, executed in CI)

- `AnimatedTextOverlayParityTest` — preview↔export letter-for-letter parity
  (positions/widths/opacity over the animation + px conversion at 1080-wide).
- `ClipEffectPlanTest` — frameAspect = project ratio for fixed aspects,
  source aspect for SOURCE, fallback chain for un-probed clips.
- Full suite: **322 tests, 0 failures** (v0.5.7 baseline 314 + 8 new).

Status: PENDING (to be executed by the user on device).
