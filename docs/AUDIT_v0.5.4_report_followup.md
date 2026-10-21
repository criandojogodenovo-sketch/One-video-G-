# Audit follow-up — v0.5.4 device report (2026-10-21)

Input: the sanitized diagnostic report the user generated **while running
v0.5.4 (versionCode 15)** (`app_version=0.5.4 (15)`, device realme RMX3624,
Android 13 / SDK 33, 104 events, ~18 KB). This document supersedes the parts of
`docs/AUDIT_v0.5.3_problems.md` that the report disproves. Export engine code
(`ClipEffectsFactory` / `CompositionFactory` / `ExportEngine` / `ImageAssetLoader`)
was NOT modified by the findings below; one shared pure helper they call
(`ExpandedOverlays`) is corrected (Ponto 1) — the export behaviour is RESTORED,
not rewritten.

---

## 1. PROVEN — overlays without an entrance animation are dropped from BOTH
###    pipelines (images never show; export loses overlays)

**Files / symbols**
- `app/src/main/java/com/onevideog/editor/editor/ExpandedOverlays.kt`
  — `build()` (lines ~90-103) + `expandOverlay()` (lines ~160-169, ~173, ~181-194).
- Introduced in v0.5.0 commit `a379a19`; unchanged since. No unit test ever
  called `ExpandedOverlays.build()` (verified: zero references in `app/src/test`).

**Mechanism (code-proven)**

```kotlin
// expandOverlay()
if (overlay.kind != OverlayKind.TEXT || !anim.isEnabled || !overlay.visible) {
    return emptyList()      // ← images, plain text, hidden: "nothing to expand"
}
// build()
for (overlay in project.overlays) {
    val segments = expandOverlay(overlay, contentAspect, limited)
    for (segment in segments) { renderList += segment }   // ← empty ⇒ DROPPED
}
```

`build()` only appends what `expandOverlay()` returns. Everything that needs no
expansion — **every IMAGE overlay, every TEXT overlay without an entrance
animation** (`TextAnimation()` default = preset NONE → `isEnabled == false`) —
returns `emptyList()` and is silently removed from the ONE render list that
both pipelines consume:

- PREVIEW: `PreviewStage` → `OverlayLayer(overlays = expanded.overlays)` →
  `computePlacements`. No item ⇒ no placement ⇒ no pixels, **no hit-test**,
  **no gizmos**, no transform, no `OVERLAY_DRAW`.
- EXPORT: `ClipEffectsFactory.overlayTextures` (line 152) and
  `CompositionFactory.buildBlackBaseItem` (line 159) both call
  `ExpandedOverlays.build(project)` and build textures from
  `expanded.overlays` only ⇒ the overlay never reaches the output video.

**Device-report evidence (all from the 2026-10-21 file)**
- Image import `img-1368f6ff`: `PICKER_RESULT → PERMISSION persisted=true →
  ACCESS → BOUNDS 720×1600 → DECODE_END w=720 h=1600 → OVERLAY_CREATE
  ovId=c936ead3 durationMs=5000` — every stage succeeded, and the overlay was
  created full-span (`startMs=0, endMs=duration`, EditorViewModel.addImageOverlay).
  **No `OVERLAY_DRAW` for `c936ead3` exists anywhere in the log**, including
  after the scrub to 8496 ms (inside the overlay window).
- Export `exp-1c861095` (project `clips=1 overlays=1 durationMs=12042`):
  `EFFECT_ATTACH op=exp-1c861095 effects=0 textures=0` — **the export built
  ZERO overlay textures for a project that HAS one overlay.** The export
  "succeeds" while silently producing a video without the overlay.
- Sessions 15:59 and 18:24: `ACTIVATION activeOverlays=1` on projects with
  overlays and **no** `OVERLAY_DRAW` afterwards — same drop.

**Fix (Ponto 1)** — `expandOverlay()` passes un-expanded overlays THROUGH
(`listOf(overlay)`) instead of dropping them; hidden layers keep returning
`emptyList()` (v0.3.0 rule: hidden renders nothing in preview AND export).
Blank animated text and the G5 >96-segment fallback also pass through
(rendered un-animated) instead of vanishing. One shared change restores the
pre-v0.5.0 behaviour of both pipelines; the export engine itself is untouched.
Covered by the new pure `ExpandedOverlaysTest` (the passthrough paths return
before any `android.graphics.Paint` use, so they run on plain JVM).

---

## 2. PROVEN — the stage still collapses (53×30) in v0.5.4: the 140 dp floor
###    is a no-op where it matters

**File / symbol**
- `app/src/main/java/com/onevideog/editor/ui/editor/EditorScreen.kt` lines
  271-282 (flex branch), added in v0.5.4:

```kotlin
Box(
    modifier = Modifier
        .fillMaxWidth()
        .weight(1f)              // ← allocates min = max = leftover space
        .heightIn(min = 140.dp), // ← coerced INTO [leftover, leftover] ⇒ no-op
    ...
)
```

Compose semantics: inside a `Column`, `weight(1f)` (fill = true) measures the
child with `min = max = allocated`. A `heightIn` chained AFTER it coerces its
min into the incoming `[min, max]` — when the allocation is 30 px the floor
coerces to 30 px. **The floor is dead exactly in the case it was written for.**

**Device-report evidence**
- 16:00:17.044 and 16:00:39.531 (`op=pvw-007c066f`): `surfaceW=53 surfaceH=30
  ... containerW=720 containerH=30`. `containerW/H` is the measured size of the
  PreviewStage root `BoxWithConstraints` (PreviewStage.kt lines 448-455), so
  the EDITOR gave the stage 720×30 px. Both events precede a text
  `OVERLAY_ADD` (+5 s / +6 s) — the transform quick panel was expanded for the
  newly selected overlay; fixed chrome below the stage ≈ 770 dp > 800 dp
  window ⇒ 15 dp leftover reached the weighted stage. The 140 dp floor was in
  the build and did nothing (mechanism above).
- 18:25:43 (`op=pvw-f8f90240`): `surface 711×400, containerH=400` — 200 dp
  leftover with the panel expanded: same mechanism, floor not yet violated
  (200 > 140), no collapse that time. Consistent.

**Fix (Ponto 2)** — stop fighting the weight: cap the chrome BELOW the stage in
the flex branch (`heightIn(max = maxHeight - 212.dp)` = window − ~72 dp top-bar
reserve − 140 dp floor) and give it `verticalScroll` (same pattern the compact
branch already ships). Invariant by construction: the chrome can never claim
more than the cap, so the weighted stage always receives ≥ 140 dp. Normal
portrait (chrome ≈ 420 dp < cap) is pixel-identical to v0.5.4. The compact
(< 660 dp) branch is untouched. The v0.5.4 gesture hard-stop
(`OverlayLayout.isUsableContentSpace` + `OVERLAY_GESTURE_REJECTED`) stays as
the second line of defence.

---

## 3. EXPLAINED — `OVERLAY_DRAW ovId` ≠ `OVERLAY_ADD / OVERLAY_TRANSFORM ovId`

Benign for behaviour, confusing in every report. `OVERLAY_ADD` /
`OVERLAY_TRANSFORM` log the PARENT overlay id (EditorViewModel / PreviewStage);
`OVERLAY_DRAW` logs `p.overlay.id` (OverlayCanvas.kt line 440), which for an
animated text is the generated segment id `parent#sN`
(`ExpandedOverlays.segmentId`) → `fingerprint` differs:
- ADD `40899641` vs DRAW `4b24c79f` (parent `40899641#s0`)
- ADD `0fde479b` vs DRAW `9bef6005` (parent `0fde479b#s0`) — stable across
  rebuilds because segment ids are deterministic (`parent#sN`).

Selection/gestures were never broken by this: `PlacedOverlay.parentId` maps
segments back and `targetFor()` resolves the parent (OverlayCanvas lines
150-153, 467-472). **Fix (Ponto 3, evidence-only):** log the parent-first id
(`p.parentId ?: p.overlay.id`) plus a `seg` meta with the segment id when it
differs, so report trails join across `OVERLAY_ADD` → `OVERLAY_DRAW` →
`OVERLAY_TRANSFORM`.

---

## 4. EXPLAINED — text drawn with `opacity=0.0` right after creation

Not a render bug — it is the entrance animation math working (and the parity
contract holding):

- 16:00:27 `opacity=0.0` at `playheadMs=0`: the user had enabled a FADE
  entrance (the draw id proves expansion — a non-animated text would have been
  dropped entirely per §1). The FADE's first relative keyframe multiplies
  opacity by 0 ⇒ at t = 0 the text is invisible until playback passes the fade.
- 16:01:09 `opacity=0.026666667`: **exactly 16 ms ÷ 600 ms** (the
  `TextAnimation.durationMs` default) — the draw caught the playhead one frame
  after the scrub released (SCRUB_END logged 0 at 16:01:08.047).
- 16:00:59 `opacity=1.0` at playhead ≥ fade end. Consistent.

Consequences for UX, both fixed/mitigated by Ponto 1:
- Text WITHOUT animation (the default!) was invisible at ALL times (§1 drop).
  After Ponto 1 it renders immediately.
- Text WITH a fade stays invisible at playhead 0 until played — by design and
  identical in the export (parity). Documented in the device checklist.

---

## 5. RE-CLASSIFIED — `videoW=0 / videoH=0` is NOT only a clip-less artefact

The v0.5.4 audit verdict ("expected: only for items without a video track /
empty project") is **incomplete**. The report shows `videoW=0 videoH=0` in
EVERY `VIEWPORT_GEOMETRY` event, including projects WITH clips (2-clip project
at 15:59:46 and 18:24:50; 1-clip 9:16 project at 16:01:56 and 16:04:22), and
across all 104 events there is **no `PREVIEW_RENDER` with w > 0** — i.e.
`Player.Listener.onVideoSizeChanged` never delivered a size during the whole
recording, even though the export read the same sources fine
(`SOURCE_MEDIA videoReadable=1/1`).

What IS proven:
- The LAYOUT never depends on videoW/H: `contentAspect` comes from
  `project.aspectEnum` / `clip.sourceAspect` (PreviewStage lines 108-111) —
  the 9:16 project fitted 332×588 (`contentAspect=0.5641`) and 16:9 fitted
  720×405 in the same sessions. The video-size value is evidence-only.
- `videoSize` starts as `VideoSize.UNKNOWN` and only updates via
  `onVideoSizeChanged` (PreviewStage lines 165, 179-217); the tracker logs on
  change. So `videoW=0` at attach time is expected; it staying 0 forever means
  the callback never fired (or never carried a positive size) on this device.

Not provable from the log: whether the preview surface actually showed video
pixels in those sessions. **Action (Ponto 3):** `VIEWPORT_GEOMETRY` now also
carries `playerState` (the player's playback state name at log time), so the
next report distinguishes "decoder never reported" from "player never
prepared". The physical checklist asks the user to confirm the video is
visible in the preview for a project with clips.

---

## 6. Verified WITHOUT defects (again, against this report)

- Project lifecycle: `CREATE` (16:00:10, 18:25:35) / `OPEN` (3×) with correct
  clip/overlay/audio counts, `missing=0` — creation, selection, persistence
  and reopening behave; the two opens of project `78f885bd` produced identical
  geometry.
- Export pipeline: `REQUEST → SERVICE_CREATE → SERVICE_START → VALIDATE
  problem=none → SOURCE_MEDIA 1/1 → PREFLIGHT → PLAN → TRANSFORMER_START →
  COMPLETED → DONE (output verified, totalMs=2787)` — untouched and healthy.
- Timeline scrubbing: `SCRUB_START/END` map pointer→ms consistently
  (`zoom=1.0 density=2.0`); the v0.5.4 gesture guard was never triggered
  because no gesture ran while the stage was degenerate.
- Image import chain up to overlay creation: every stage OK
  (`persisted=true`, decode 63 ms, elapsed 203 ms) — the failure is
  exclusively the §1 drop.

## Fix plan (one point at a time, compile after each)

| Ponto | Change | Files |
|-------|--------|-------|
| 1 | Passthrough for un-expanded overlays + pure tests | `ExpandedOverlays.kt`, new `ExpandedOverlaysTest.kt` |
| 2 | Real stage floor: cap + scroll the flex-branch chrome | `EditorScreen.kt` |
| 3 | Evidence: parent-first `ovId` (+`seg`), `playerState` in `VIEWPORT_GEOMETRY` | `OverlayCanvas.kt`, `PreviewStage.kt` |
| 4 | Version 0.5.5 (16) + PARITY items + device checklist + workflow APP_VERSION | `app/build.gradle.kts`, `.github/workflows/*`, `docs/*` |

Export engine files: **zero modifications** (§1 restores the shared expansion
the export already calls; §3-4 are log meta and version strings).
