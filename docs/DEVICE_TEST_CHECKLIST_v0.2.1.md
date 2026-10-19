# One Video G v0.2.1 — Manual Device Test Checklist (realme C33 / Android 13, 4 GB)

Automated tests (78, JVM) pass locally and in CI. NONE of the items below were
verified on a physical device in this release cycle — please run this checklist
on the target phone and report any failing step with the exact item number.

## 0. Install
- [ ] Uninstall is NOT required: install v0.2.1 over v0.2.0 and open an old
      project — projects, autosave and undo history must survive.
- [ ] Fresh install also works.

## 1. Play / Pause (BUG 1)
- [ ] Import a video, press Play in the transport bar below the preview:
      video starts moving.
- [ ] Press Pause: video freezes at the current frame (not just the sound).
- [ ] Icon toggles between play ▶ / pause ⏸ and matches reality every time.
- [ ] With a music track added: Play starts video AND audio together; Pause
      stops both.
- [ ] Let playback reach the end: it stops by itself and the icon shows ▶.
- [ ] ±10 s buttons jump 10 seconds while playing and while paused.

## 2. Timeline scrubbing (BUG 2)
- [ ] Tap a point on the time ruler (top strip of the timeline): playhead
      jumps exactly there and STAYS there (does not snap back).
- [ ] Drag the ruler slowly across the whole project: playhead follows the
      finger 1:1; overlays/text move in real time while dragging.
- [ ] Release mid-drag: preview seeks to the released time; no bounce back.
- [ ] Start a drag while playing: playback pauses during the drag and resumes
      from the released point.
- [ ] Zoom in (2x, 4x) and repeat a tap + drag: positions stay accurate.
- [ ] Tap exactly on a clip boundary and inside a trimmed clip: correct time.
- [ ] With a long project (≥ 2 min): scrub to the very end and very beginning.

## 3. Audio import (BUG 3)
- [ ] Import an MP3 and an M4A from the picker: app does NOT crash; a block
      appears on an audio track and its name shows.
- [ ] Press Play over the audio block: music is audible and roughly in sync
      with the playhead.
- [ ] Move the audio block (Layers → audio entry, or drag on its row),
      trim it (audio sheet), change volume and loop: all applied in preview.
- [ ] Try importing a non-audio file (e.g. a PDF renamed or a corrupt file):
      app shows a friendly error dialog, does NOT crash.
- [ ] Export a project containing music: audio present and synchronized.

## 4. Layers & keyframes (BUG 4)
- [ ] A "Layers / Camadas" button exists in the bottom toolbar and opens a
      sheet listing video clips, audio tracks and text/image layers.
- [ ] From Layers: add video / audio / text / image.
- [ ] Select a text layer → editor opens; type text, change color/font.
- [ ] In Layers tap the ⏱ (animation) icon on a text layer: keyframe editor
      opens (add at playhead, move ±, delete, linear/smooth).
- [ ] Add a keyframe at 0 s, move the playhead to 2 s, drag the text in the
      preview, add another keyframe: playing from 0→2 s animates the text.
- [ ] Keyframe dots appear on the overlay's timeline block.
- [ ] Reorder video clips with the ◀ ▶ buttons in Layers; delete works.
- [ ] Close and reopen the project: layers + keyframes are restored.

## 5. Transport placement (BUG 5)
- [ ] The play/pause button is NOT in the middle of the video; the center of
      the canvas is free for tapping/moving overlays.
- [ ] Transport bar (below the canvas) shows current time / total duration.

## 6. Regression (must not have broken)
- [ ] Undo/redo still works for trims, overlays, audio.
- [ ] Export a 30 s project: succeeds, MP4 plays, overlays + audio included.
- [ ] Dark and light theme: Settings screen text readable.

## Known limitations (unchanged by this release)
- Preview audio volume caps at 100 % (export can go to 200 %).
- Rotation export direction and color pipeline differences are documented in
  docs/PREVIEW_EXPORT_PARITY.md.
