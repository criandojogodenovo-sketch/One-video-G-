# Device test checklist — v0.5.5 (versionCode 16)

Device reference: realme RMX3624 · Android 13 (SDK 33) · density 2.0 ·
720×1600 px. Build: `OneVideoG-v0.5.5-release.apk` (GitHub Actions, signed).

Legend: **[executed]** = verified (locally or by the referenced evidence);
**[pending]** = needs the physical device. Every step names the diagnostic
event that proves it — send the app's diagnostic report after testing
(`op=` ids link the full trail; since v0.5.5 `OVERLAY_DRAW` logs the SAME id
as `OVERLAY_ADD`/`OVERLAY_TRANSFORM`, and `VIEWPORT_GEOMETRY` adds
`playerState`).

## 1. Overlay drop fix (Ponto 1 — the main v0.5.5 fix)  [pending on device]

Code-proven and unit-tested (`ExpandedOverlaysTest`, 7 tests, green);
the on-device behaviours below were NEVER verifiable before because the
overlays never reached the render list since v0.5.0.

1.1 Empty/new project → import an image → **the image APPEARS in the preview
    immediately** (window [0..duration], scale 0.4, centred). Evidence:
    `OVERLAY_DRAW ... kind=IMAGE content=image bitmap=WxH`.
1.2 With the image selected: drag / pinch / rotate — gizmos follow the finger,
    release commits. Evidence: `OVERLAY_TRANSFORM` with the SAME `ovId` as the
    draw.
1.3 Export that project → **the exported video contains the image** at the
    edited position. Evidence: export `EFFECT_ATTACH ... effects>=1
    textures>=1` (v0.5.4 logged `effects=0 textures=0` for an
    overlays=1 project — that was the bug).
1.4 Create a text overlay, do NOT touch the animation sheet → **the text shows
    immediately** at the playhead. Evidence: `OVERLAY_DRAW kind=TEXT` with the
    parent id.
1.5 Text WITH a FADE entrance stays invisible at playhead 0 until played past
    the fade (~0.6 s) — that is the animation working (export shows the same;
    parity). Not a bug — confirm it matches your expectation.
1.6 Re-open the saved project → image and text still render
    (`project OPEN ... missing=0` + draws on seek).

## 2. Stage collapse fix (Ponto 2)  [pending on device]

2.1 Select any overlay → expand the transform quick panel → **the preview
    NEVER collapses to a sliver**; it keeps at least 140 dp (280 px) of
    height. Evidence: `VIEWPORT_GEOMETRY ... containerH>=280` while the panel
    is expanded (v0.5.4 logged `containerH=30` at 16:00:17).
2.2 With the panel expanded, the chrome below the stage may scroll
    internally — every control stays reachable (top of the scroll = panel).
2.3 Open a text/image sheet and type (keyboard up) → preview stays ≥ floor.
2.4 Landscape / split screen → compact layout with the fixed 200 dp stage
    (unchanged from v0.5.4).
2.5 Try gestures while the panel is expanded: no transform corruption; if the
    stage ever degenerates the gesture layer must log
    `OVERLAY_GESTURE_REJECTED` (defence-in-depth, expected to stay silent).

## 3. Evidence upgrades (Ponto 3)  [pending on device]

3.1 Any animated-text draw: `OVERLAY_DRAW ovId=<parent> seg=<segment>` — the
    ovId now equals the ADD/TRANSFORM ids (v0.5.4 report showed different
    ids: ADD 40899641 vs DRAW 4b24c79f).
3.2 For a project WITH a video clip: `VIEWPORT_GEOMETRY` carries
    `playerState`. **Check whether the preview actually shows the video
    frames** — the 2026-10-21 report recorded `videoW=0` in every event and
    no decoder size callback at all; if the video shows but `playerState=ready`
    keeps `videoW=0`, that is a Media3 callback quirk on this device
    (layout is unaffected — it uses the clip's source aspect) and we will
    only document it; if the video does NOT show, say so — that is a separate
    preview bug to hunt with the new evidence.

## 4. Export regression guard (always)  [pending on device]

4.1 Export a project with clips + overlays + audio → completes, output plays
    with overlays visible (v0.5.5 must be ≥ v0.5.4 here — the engine is
    untouched; Ponto 1 only restores the overlays it silently lost).
4.2 Export the empty/overlay-only project → completes (black base + overlays).

## 5. Already verified locally  [executed]

- 311 JVM unit tests green (304 prior + 7 new `ExpandedOverlaysTest`),
  0 failures / 0 errors (`testReleaseUnitTest`, Temurin JDK 17).
- `compileReleaseKotlin` green after each point (1, 2, 3) — one point per
  compile, per the working rule.
- Audit document: `docs/AUDIT_v0.5.4_report_followup.md` (§6 lists what was
  re-verified WITHOUT defects against the 2026-10-21 report: project
  lifecycle, export pipeline health, scrub mapping, image import chain).
