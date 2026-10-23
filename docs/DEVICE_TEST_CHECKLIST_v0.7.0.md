# One Video G — physical device test checklist (v0.7.0)

**Status: PENDING — nothing below is device-verified yet.**

**APK:** `OneVideoG-v0.7.0-release.apk` (release "latest", versionCode 22)
**Scope:** Fase J (advanced colour correction — tonal curves J1–J5) and Fase K
(speed ramps — K1–K7). Preview and export run the SAME pure planners
(`CurveMath`, `SpeedRampPlanner`) and the SAME GLSL through the shared
`ClipEffectsFactory` list. Contracts preserved: preview GL list is video-only
(no OverlayEffect), overlays are Compose-only, `effectiveSignature` excludes
overlays, project duration = video sequence length (ramp-aware by
construction), v0.3.0/v0.3.1/v0.6.0 behaviour untouched for clips without
curves/ramps. Splash, navigation and editing logic outside curves/ramps
untouched.

## J — Colour curves

1. **J1 model migration:** open a pre-v0.7.0 project — it loads with no
   curves (`curves = null`) and no behaviour change (schema v2/v3 documents
   decode at the v4 defaults; `LocalizationTest` and the JSON round-trip
   tests pin this on the JVM).
2. **J5 sheet:** select a video clip → Tools → Curves (Tune icon). Four
   channel tabs (Master/Red/Green/Blue), a graph with the identity diagonal,
   draggable control points (tap adds up to 8, double-tap removes, drag
   shapes the curve), the presets Linear / S-Curve / Fade / Cinematic and a
   "No curves" reset.
3. **J2 spline correctness:** an S-Curve preset visibly darkens shadows and
   lifts highlights with NO reversal or banding between points (the
   Fritsch–Carlson monotonic cubic spline — CurveMathTest locks
   monotonicity, linear = identity, 2-point identity, round-trip).
4. **J3 preview parity:** apply a strong Master curve — the preview shows
   the graded image live; the exported file matches (same shader string,
   same 256-entry LUTs uploaded as 8-bit textures — within ≤ 1 LSB).
5. **J3 per-channel:** a Red-only curve changes only the red channel in
   both preview and export; Master applies to all three channels BEFORE the
   per-channel curves (order locked by tests).
6. **J4 pipeline order:** colour adjustments stack in the documented order
   (speed → color → curves → 2D effects → transitions → overlays) — a clip
   with brightness + curves + blur renders identically before and after the
   v0.7.0 upgrade when curves are off.
7. **J4 decoder re-prepare (accepted cost):** committing a curve edit
   re-prepares the preview decoder ONCE per gesture (draft is local; the
   commit happens on drag end). Overlay edits must STILL not re-prepare the
   decoder (v0.3.1 contract intact).
8. **J3 no-GPU-cost rules:** "No curves" (and never touching the sheet)
   builds no GL instance (`EFFECT_ATTACH` count unchanged); all-identity
   curves are normalized to null by the ViewModel.

## K — Speed ramps

9. **K1 model migration:** pre-v0.7.0 clips decode with
   `speedRamps = emptyList()` — constant speed, `effectiveDurationMs`
   unchanged (JVM-locked).
10. **K6 sheet:** select a video clip → Tools → Ramp (Timeline icon).
    Velocity-vs-time graph (y = 0.1x..4.0x with the clip-speed reference
    line), tap to add a point, drag to shape, double-tap to remove; presets
    Normal 1.0x (clears), Slow 0.5x, Ultra slow 0.25x, Bullet time 0.1x,
    Fast 2x. The sheet shows the live segment count (≤ 16) and the clip
    length before → after.
11. **K2/K3 timeline footprint:** a 0.5x ramp on half of a 4 s clip grows
    the clip bar to ≈ 6 s on the timeline and shifts later clips — the
    exported file has exactly that length (v0.3.0 contract: duration =
    video sequence length, ramp-aware by construction).
12. **K3 export segmentation:** export a ramped clip; the output plays the
    ramp (slow then fast) with the exact planned segments; no black frames
    or seams at segment boundaries (each segment is one EditedMediaItem
    with its own trim + SpeedChangeEffect).
13. **K4 preview behaviour:** the preview follows the ramp in constant-speed
    steps (live `setPlaybackSpeed` at segment boundaries) — small speed
    jumps between segments are the DOCUMENTED limitation (PARITY item 14);
    scrubbing lands at the same content the export shows at that time.
14. **K5 audio:** the clip's own audio follows the ramp speed WITHOUT pitch
    correction (same character as the constant-speed path; documented in
    PARITY item 15).
15. **K1 validity:** out-of-range points (time outside the clip, factor
    outside 0.1..4.0) are dropped with `SPEED_RAMP_INVALID`; fewer than 2
    valid points = constant speed; trim/split keeps the ramp but drops
    points that fall outside the new domain (self-healing, PARITY item 16).
16. **K7 limit:** a ramp with more than 16 segments is truncated (the sheet
    and `SPEED_RAMP_LIMIT` evidence); the export never produces more than
    16 items per clip.

## Regression sweep

17. **Overlays:** text (static + animated entrance) and image overlays still
    match between preview and export; the Compose layer is still the ONLY
    preview overlay renderer; overlay windows on a RAMPED clip render at
    the same project times in preview and export (ramp-aware footprint).
18. **Transitions:** the v0.6.0 transition windows still render; the 50%
    validity rule now reads the RAMP-AWARE clip length (a transition on a
    ramped clip can be longer than 50% of its constant-speed basis —
    planner-tested); transition windows on ramped clips land at the same
    project times in both pipelines.
19. **2D effects:** blur/vignette/sharpen unchanged; effects still stack
    AFTER curves in the pipeline order; the 3-effects limit still enforced.
20. **Audio timeline:** music + clip audio still aligned (audio sequences
    still clamped to the ramp-aware video duration).
21. **Editing:** split, trim, constant speed, colour, aspect, undo/redo —
    unchanged for clips without curves/ramps; undo reverts a curve/ramp
    commit cleanly (one undo step per gesture).
22. **Video-less / empty projects:** black base + overlays still export;
    curves/ramp tools need a video clip (disabled otherwise).
23. **Icon + splash untouched:** launcher icon still the v0.5.8 OV monogram;
    splash unchanged; `LocalizationTest` green (new strings in pt + en).

## Diagnostics quick reference (new in v0.7.0)

- `CURVES_SET` — committed curve edits (point counts per channel; also
  logged with "cleared" when normalized to null).
- `CURVE_INVALID` — a curve rejected by validation (channel + reason +
  point count); never committed.
- `SPEED_RAMP_SET` — committed ramp edits (points, planned segments,
  ramp-aware output duration; also "cleared").
- `SPEED_RAMP_INVALID` — dropped ramp points (with the reason).
- `SPEED_RAMP_LIMIT` — a ramp exceeding the 16-segment limit (at commit
  time and again at export build time).
- `EFFECT_ATTACH` now names the segment window (`segmentMs`) and carries
  the curves instance in the count (watch `effects` grow by 1 per graded
  clip).
