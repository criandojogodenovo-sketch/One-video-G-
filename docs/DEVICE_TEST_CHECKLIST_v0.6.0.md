# One Video G — physical device test checklist (v0.6.0)

**Status: PENDING — nothing below is device-verified yet.**

**APK:** `OneVideoG-v0.6.0-release.apk` (release "latest", versionCode 21)
**Scope:** Fase H (transitions between adjacent clips — H1–H6) and Fase I
(per-clip 2D effects: blur / vignette / sharpen — I1–I7). Preview and export
run the SAME pure planner and the SAME GLSL shaders through the shared
`ClipEffectsFactory` list. Contracts preserved: preview GL list is video-only
(no OverlayEffect), overlays are Compose-only, `effectiveSignature` excludes
overlays, `AnimatedTextLayout.segmentPlacement` untouched. Splash, navigation
and editing logic outside transitions/effects untouched.

## H — Transitions between clips

1. **H1/H6 model + self-heal:** open a pre-v0.6.0 project — it loads with no
   transitions and no behaviour change (schema v2 documents decode at the v3
   defaults). Remove or move a clip that had a transition: the orphaned
   transition disappears by itself (no stale badge, no broken export);
   diagnostics log `TRANSITION_INVALID` for the drop.
2. **H4 badge:** the timeline shows a dim `+` between every adjacent pair of
   video clips; after setting a transition the marker becomes ⚡ (accent).
   Tapping either marker opens the TransitionSheet for that pair.
3. **H5 sheet:** five kinds (Crossfade, Wipe left/right, Slide up/down) +
   None; the duration slider runs 200–1000 ms snapped to 100 ms and never
   offers a value the planner would reject (it is pre-clamped to ≤ 50% of
   BOTH clips — short clips show a smaller max).
4. **H3/H5 preview parity (crossfade):** with a 500 ms crossfade between two
   clips, scrub through the boundary — the tail of clip A dims to black over
   the window and the head of clip B fades back in. The preview runs the
   SAME GLSL as the export (live shader via `ExoPlayer.setVideoEffects`,
   not a static mock).
5. **H3/H5 preview parity (wipe/slide):** same check with Wipe right and
   Slide up: the content pushes out in the chosen direction and the next
   clip enters from the opposite side. What you see in the preview is what
   the export renders — same shader string, same uniforms.
6. **H2/H3 export:** export a project with a transition; the exported file
   shows the same fade/push through black at the same times as the preview.
   Transitions must not change the project duration (the v0.3.0 contract:
   windows render ON the content).
7. **H2 validity:** on two ~1 s clips the sheet caps the duration around
   500 ms; committing a transition never produces a broken export.

## I — Per-clip 2D effects

8. **I6 sheet:** select a video clip → Tools → Effects (BlurOn icon). Three
   kinds with ON/OFF chips; enabling defaults to 50% intensity; the slider
   moves in 0.1 steps with a live % label.
9. **I2 blur:** blur at 0.2 vs 0.8 — visibly stronger at 0.8, smooth (no
   banding artifacts worse than the source), and at intensity 0 the clip is
   bit-clean (no GL instance installed).
10. **I3 vignette:** edges darken progressively with intensity; the frame
    center stays untouched; intensity 1.0 approaches black corners.
11. **I4 sharpen:** edges crisper at 0.8; no halos worse than expected at
    1.0; intensity 0 is a no-op.
12. **I5 preview↔export parity:** export a clip with blur+vignette+sharpen —
    the exported frame matches the preview (same shaders on both paths by
    construction). Colour adjustments still stack in the documented order
    (speed → colour → effects → transitions → overlays).
13. **I5 decoder re-prepare (accepted cost):** toggling an effect or moving
    its slider re-prepares the preview decoder ONCE per commit (effects are
    GL inputs — `effectiveSignature` includes them). Overlay edits must
    STILL not re-prepare the decoder (v0.3.1 contract intact).
14. **I7 limit:** enabling a 4th effect is impossible from the sheet (chips
    disable + warning line); diagnostics log `EFFECT_LIMIT` if forced via
    the ViewModel.
15. **Performance (low-end watch):** 3 effects on one clip may cost some
    smoothness on 4 GB devices during preview playback; the export is
    unaffected (offline). Document any sustained frame drops in the report.

## Regression sweep

16. **Overlays:** text (static + animated entrance) and image overlays still
    match between preview and export; the Compose layer is still the ONLY
    preview overlay renderer (no double exposure on devices where the GL
    pipeline runs).
17. **Audio:** music + clip audio still aligned after transitions/effects
    (audio path untouched by H/I).
18. **Editing:** split, trim, speed, colour, aspect, undo/redo — unchanged.
    Undo after a transition/effect edit reverts it cleanly.
19. **Video-less / empty projects:** black base + overlays still export;
    transitions badges only appear between video clips.
20. **Icon + splash untouched:** launcher icon still the v0.5.8 OV monogram;
    splash unchanged; `LocalizationTest` green (new strings in pt + en).

## Diagnostics quick reference (new in v0.6.0)

- `TRANSITION_SET` / `TRANSITION_REMOVED` — committed transition edits.
- `TRANSITION_INVALID` — planner-rejected or sanitizer-dropped transitions
  (with the rejection reason).
- `EFFECT_SET` / `EFFECT_REMOVED` / `EFFECT_LIMIT` — per-clip effect edits.
- `EFFECT_ATTACH` now carries the transition/effect instances in the count
  (watch `effects` grow by 1 per transition window, 1 per non-blur effect,
  2 per blur — separable H/V passes).
