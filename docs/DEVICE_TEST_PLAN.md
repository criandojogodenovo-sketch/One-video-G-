# Device test plan — realme C33 RMX3624 (Android 13, 4 GB RAM, Unisoc T612, ARM64)

This plan is written to be executed by a human with the target phone.
**No physical device was available in the development environment — nothing in
this document has been executed yet.** Items below must not be reported as
"passed" until they are run and logged.

## 0. Build under test

- Signed Release APK only (`apksigner verify` passes, v2 scheme true).
- `adb install -r OneVideoG-v0.2.0-release.apk`.

## A. Long-export stability (the reported bug)

For each duration, build one project from the **same source file**
(1080p H.264/AAC MP4, 30 fps), no effects added:

| #  | Duration | Steps                                                                                   | Record |
|----|----------|-----------------------------------------------------------------------------------------|--------|
| A1 | ~30 s    | Export 720p Medium; screen ON, app foreground                                            | wall time, result, peak RAM (adb shell dumpsys meminfo), output validity |
| A2 | ~60 s    | Export 720p Medium; screen ON                                                            | same |
| A3 | ~2 min   | Export 720p Medium                                                                       | same |
| A4 | ~5 min   | Export 720p Medium                                                                       | same |
| A5 | ~15 min  | Export 720p Medium (expect several GB free; check storage first)                         | same |
| A6 | ~5 min   | Export 1080p High                                                                        | same |

For each row repeat **twice**: (a) screen on/foreground the whole time,
(b) lock the screen at 25% progress and leave the phone for the duration.
Case (b) is the regression test for the foreground-service fix: export must
continue (notification progress) and complete. Failure to complete in (b)
= the long-export bug is not fixed.

Output validity per case:
- File exists at the chosen location, size > 0 and plausible
  (±15% of `ExportConfig.estimatedBytes`).
- `MediaMetadataRetriever` duration within 60%–200% of planned (app does this
  automatically; the UI shows "Finalizing" → success only if it passes).
- Manual: play the exported file in the device player end-to-end; audio and
  video stay in sync at 0%, 50%, 100%.

## B. Audio + overlays project

1. Project with 2 clips + 1 audio track (start 2 s, volume 80%) + 1 text
   overlay with 2 keyframes (position) + 1 image overlay.
2. Verify in preview: text moves during playback; audio audible from ~2 s.
3. Export 720p; compare:
   - exported text position at the keyframe times vs preview screenshots,
   - audio present and starting ≈2 s (not at 0 s, not at trimStart),
   - overlay positions at clip boundaries.
4. Record pass/fail per item. Note any colour difference between preview and
   export (documented sRGB/linear limitation).

## C. Memory pressure (4 GB)

1. Repeat A4 with 4 apps open (browser + camera + maps).
2. Watch for export failure or process death; check
   `cacheDir/exports/diagnostics.log` lines afterwards.
3. Confirm no `export_*.mp4` temp files accumulate after success/failure
   (`adb shell run-as com.onevideog.editor ls files/../cache/exports` —
   debuggable builds only; otherwise check free storage trends).

## D. Rotation / lifecycle

1. Rotate the device at 25% export progress — export must continue, screen
   state must return correctly.
2. Navigate Home mid-export, return via the notification — progress screen
   reflects the service state (`ExportBus`), not a dead composition.

## E. Unsupported media

1. Import a broken/renamed `.mp4` (e.g. a text file) → expect the
   "unsupported" error, no crash.
2. Import a 4K 60 fps HEVC clip → either import succeeds and export works, or
   a mapped error (`error_decoding` / `error_encoding`) appears — never a
   silent failure. Record which.
3. Import an audio file the platform cannot decode → mapped error on the
   audio panel.

## F. Cancellation

1. Cancel at ~30% and at ~90%: state returns to Cancelled, no temp files
   left, storage freed (compare free space before/after).
2. Cancel from the notification with the screen off.

## G. Output playback matrix

For each successful export: play in the device player, verify duration
matches the timeline total from `ProjectMath`, no watermark anywhere, and
(when the project has audio) lip-sync spot checks.

## Reporting template

```
Case | Build | Duration | Result | Wall time | Peak RSS | Output size | Validity | Notes
A1b  | v0.2.0| 30s      |        |           |          |             |          |
```

Anything not executed must be reported as **not tested**.
