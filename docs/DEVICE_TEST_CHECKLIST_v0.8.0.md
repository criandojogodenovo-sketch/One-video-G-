# One Video G — physical device test checklist (v0.8.0)

**Status: PENDING — nothing below is device-verified yet.**

**APK:** `OneVideoG-v0.8.0-release.apk` (release "latest", versionCode 23)
**Scope:** Fase P (multi-track audio — lanes P2, fades P3, crossfade P4) and
Fase Q (audio treatment — shared gain math Q1, shared processor Q2, volume
keyframes Q3, noise gate Q4, sync diagnostics Q5). Preview and export run
the SAME gain math (`AudioGainMath`) through the SAME processor class
(`GainAutomationAudioProcessor`) — one implementation, two consumers.
Contracts preserved: `projectDurationMs` = video sequence length (audio
NEVER extends it — `AudioTimeline.exportPlan` clamps, v0.3.0); preview GL
list is video-only and overlays stay outside `effectiveSignature` (v0.3.1);
the video pipeline order speed → color → curves → 2D effects → transitions
→ overlays is untouched (v0.6.0/v0.7.0). Audio lanes are DISPLAY-ONLY
(computed by `AudioLanePacker` at render time, never stored). Splash,
navigation and editing logic outside audio untouched.

## P — Multi-track audio

1. **P1 model migration:** open a pre-v0.8.0 project — audio clips load
   with no fades (`fadeInMs = fadeOutMs = 0`), no keyframes, gate off and
   behave exactly as before (schema v2/v3/v4 documents decode at the v5
   defaults; the JSON round-trip tests pin this on the JVM).
2. **P2 lanes:** place two audio clips that overlap in time — they appear
   on TWO timeline rows (packed by `AudioLanePacker`); move one so they no
   longer overlap — they share ONE row. All clips stay visible and
   interactive in every case (no row cap). Playback still MIXES the
   overlap (both audible) — the lane is display-only.
3. **P3 fades:** select an audio clip → Tools → Audio. Two sliders
   (Fade in / Fade out). A fade-in makes the clip start silent and rise
   linearly; the timeline bar draws the envelope live (the SAME
   `AudioGainMath` the pipelines apply). The exported file has the same
   fades.
4. **P4 crossfade automatic:** with two overlapping clips whose fades are
   set (via the crossfade button), the overlap attenuates the earlier clip
   while the later rises — the mix is a LINEAR crossfade (a slight dip
   mid-overlap is the documented simplification, PARITY item 24).
5. **P4 crossfade button:** tap "Crossfade with neighbour" on an audio
   clip with a neighbour — the later clip moves to create an 800 ms
   overlap (clamped to both durations, floor 150 ms) and both fades are
   set in ONE undo step. The button is disabled when the clip has no
   audio neighbour.

## Q — Audio treatment

6. **Q1 parity:** set a fade-in + a volume keyframe and export — the
   exported audio envelope matches the preview (same `AudioGainMath`, same
   `GainAutomationAudioProcessor` class on both paths; the export is
   sample-exact, the preview may lead by the sink buffer depth — PARITY
   item 23).
7. **Q2 live edits:** moving a fade slider or dragging a keyframe updates
   the preview audio IMMEDIATELY, with no video decoder re-prepare and no
   audio player rebuild (the processor profile is swapped per sync tick).
8. **Q3 keyframe editor:** the volume-automation canvas under the fades —
   tap adds a point (max 16), drag moves it, double-tap removes, "Clear
   volume curve" resets. The curve IS the applied gain (drawn from
   `AudioGainMath.gainAt`), with the 1.0 reference line.
9. **Q4 noise gate:** toggle the gate on a narration clip with background
   hiss — the low-level hiss between phrases attenuates; speech is not
   clipped (attack ~2 ms). Music with quiet passages will breathe (the
   gate is NOT ML/spectral — documented, PARITY item 25).
10. **Q5 sync diagnostics:** with diagnostics enabled, drag the playhead
    far from an active audio clip — the forced re-seek logs `AUDIO_SYNC`
    with `driftMs`/`targetMs` (the audio↔video offset evidence), rate
    limited to one event per clip per 3 s.

## Regression sweep

11. **Duration contract:** adding fades/keyframes/gate NEVER changes the
    project duration or any export plan window (audio stays clamped to the
    video end — `AudioTimelineExportPlanTest` locks plan equality with and
    without automation).
12. **Video pipeline untouched:** curves/ramps/transitions/2D effects on
    video clips still preview and export exactly as in v0.7.0 (video clips
    keep the constant `VolumeAudioProcessor`; the shared gain processor is
    audio-only).
13. **Overlays:** text (static + animated) and image overlays unchanged;
    the Compose layer is still the ONLY preview overlay renderer; overlay
    edits still never re-prepare the decoder (v0.3.1 contract intact).
14. **Audio placement:** trim, move, loop, volume — unchanged behaviour
    for clips without automation; looping clips with fades restart the
    envelope per loop iteration (documented, PARITY item 27).
15. **Editing:** undo/redo reverts fades/keyframes/gate/crossfade commits
    cleanly (one undo step per gesture); project save/load round-trips the
    new fields (schema v5).
16. **Video-less / empty projects:** audio-only projects still export with
    the >= 5 s floor; the gain automation applies there too.
17. **Icon + splash untouched:** launcher icon still the v0.5.8 OV
    monogram; splash unchanged; `LocalizationTest` green (new strings in
    pt + en).

## Diagnostics quick reference (new in v0.8.0)

- `AUDIO_GAIN_SET` — committed fade/keyframe/gate edits (fades in ms,
  keyframe count, gate on/off; "cleared" when the curve is reset).
- `AUDIO_KEYFRAME_INVALID` — keyframes dropped by commit-time validation
  (count + submitted count).
- `AUDIO_KEYFRAME_LIMIT` — a curve exceeding the 16-point cap (truncated).
- `AUDIO_CROSSFADE` — a crossfade commit (both clip fingerprints +
  overlapMs).
- `AUDIO_SYNC` — preview audio↔video drift re-seek evidence (driftMs,
  targetMs; ≤ 1 event per clip per 3 s) — the Q5 offset validation.
