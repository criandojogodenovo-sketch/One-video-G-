# Diagnostics and Logs (v0.2.3 — temporary evidence-collection build)

This build adds a **temporary, opt-in diagnostic mode** whose only purpose is
to collect trustworthy evidence from a physical device for the three open
problems (image import/preview, export failures, timeline gestures). It is
infrastructure only — no editor behaviour was redesigned in this release.

## Where the controls are

**Settings → “Diagnostics and Logs” / “Diagnóstico e Registos”** (PT/EN):

| Control | What it does |
|---|---|
| *Enable diagnostic logging* | OFF by default; persisted. Applies to the signed release build too. |
| *Status* + *Recorded events · ~size* | Live state, event count and approximate on-device size (cap ≈ 2 MB). |
| *Copy Diagnostic Report* | Puts a sanitized text report on the clipboard. |
| *Clear Logs* | Deletes every diagnostic file after confirmation. |
| *Export Diagnostic Report* | Saves the report via the Storage Access Framework (user-chosen file). |
| Privacy notice | States logs stay on-device and are only copied/shared explicitly. |

## What is captured

- **Image import lifecycle** (one `img-*` correlation id per import): picker
  result, persisted permission, URI/authority/mime/size probe, bitmap bounds,
  decode start/end (dimensions, elapsed, thread) and every failure
  (unreadable stream, invalid bounds, thrown exception incl. `OutOfMemoryError`,
  null bitmap) plus the preview effect rebuild/attach timing — evidence for
  the “images do not appear / editor freezes” reports.
- **Export lifecycle** (one `exp-*` correlation id): request, project
  validation, source-media readability, storage preflight, composition plan,
  foreground-service lifecycle, Transformer start/progress (10 % steps)/
  completion, Transformer errors **with the full nested cause chain** and
  memory-pressure snapshot, output verification (exists, non-empty, plausible
  duration) and SAF copy result, cancellation, and the final classification.
- **Editor operations**: project open/save failures, autosave failures
  (now also crash-proofed), scrub start/end, split/trim commits and
  rejections, overlay transform commits, playback/audio-preview errors.
  Pointer-move events are never logged.
- **Uncaught exceptions**: one sanitized crash record, then the normal
  Android crash flow proceeds unchanged.
- **Interrupted exports**: a durable marker records `exp-*` + stage while an
  export runs; if the process dies, the next launch reports the export as
  **INTERRUPTED** — distinguishable from a confirmed failure.

## Storage, privacy and performance

- Files live in app-private storage: `files/diagnostics/diag-current.log`
  plus one previous file (`diag-prev.log`), ~1 MB each — hard total cap
  ≈ 2 MB, oldest events rotate out first.
- Writes are incremental from a single dedicated writer thread with a
  bounded 256-event queue (oldest events dropped under pressure); the
  UI/export thread never blocks on IO and a logging failure can never crash
  the editor.
- Logcat mirrors every event under the tag `OvgDiag`.
- Everything passes through a sanitizer before persisting: content/file URIs
  are replaced by `scheme://authority#fingerprint`, absolute paths by
  `<path:#fp>`, long opaque runs (tokens/base64) by `<tok>`, messages are
  length-capped. Image/video/audio bytes and rendered overlay text are never
  logged. No telemetry, no network, no automatic upload — ever.

## What the logger CANNOT capture (explicit limitations)

- **Native crashes** (SIGSEGV etc.), **OS kills** (LMK/vendor battery
  manager, cached-app freezer) and **abrupt power loss** are not captured as
  events. The interrupted-export marker is the only cross-restart trace for
  these cases.
- The in-memory operation registry (report “Recent operations”) is lost on
  process death; the persistent log files and the marker survive.
- Frames outside the app process, system-level codec internals and
  temperature/thermal events are not visible.
- If logging is OFF, nothing is recorded (including crashes) — the crash
  hook follows the user’s toggle by design.
- Instrumentation tests / connected-Android tests: the project has no
  `androidTest` infrastructure configured, so none were added; coverage is
  JVM unit tests (39 new tests in v0.2.3).

## Device procedure (for the reporter)

1. Install the v0.2.3 signed release APK (same signature as previous
   releases).
2. Settings → *Diagnostics and Logs* → enable the toggle.
3. Reproduce the image problem (import the same image). If an error snackbar
   appears, that is already the localized failure.
4. Settings → *Copy Diagnostic Report* → paste it into a message
   (`report-1`).
5. Reproduce the ~60 s export failure; when it fails, copy the report again
   (`report-2`).
6. Send both reports. Each contains `op=…` ids the developer can trace
   end-to-end.
