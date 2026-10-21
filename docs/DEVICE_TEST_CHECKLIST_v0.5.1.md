# Device test checklist — One Video G v0.5.1 (clipes unificados + projeto vazio)

Physical verification for the unified clip model (UUID identity, exclusive
selection, always-visible preview, audio preview sync, video-less "Projeto
vazio"). NOT run automatically — nothing below may be claimed as verified
until executed on a real device (same class as the v0.3.x–v0.5.0 test device:
Android 13, 4 GB RAM).

## Setup

1. Install `OneVideoG-v0.5.1-release.apk` over the existing install
   (signature must be accepted — same keystore identity as v0.2.x–v0.5.0).
2. Keep a project created in v0.5.0 (with video + overlays + audio) for the
   backward-compatibility sweep, and create a fresh "Projeto vazio".

## ID único — UUID para todo clipe novo

- [ ] Import a video: it is APPENDED to the timeline (nothing replaced),
      lands on the video track and becomes the selected clip.
- [ ] Add a text overlay, an image overlay and an audio clip one after
      another: each gets its own entry (text/image open a NEW overlay lane;
      audio opens its own row) and becomes the selected clip immediately.
- [ ] Add a second text with the same content as the first: both exist
      independently (no reuse) and have distinct ids (verify via in-app
      diagnostics overlay ids when needed).
- [ ] Split a video clip: both halves play independently; neither reuses
      the other's id.
- [ ] Open a project saved in v0.4.0/v0.5.0: all clips keep their content
      and order (ids untouched).
- [ ] Open a legacy project whose music came from the v1 `music` field: the
      migrated audio clip works (plays, moves, trims) and appears exactly
      once (no duplicate on re-open — migration is idempotent).

## Preview sempre visível

- [ ] With two text overlays and one image active at the same time, ALL of
      them render on top of the player regardless of which (if any) is
      selected.
- [ ] The selection frame, ✕ and scale/rotate handles appear ONLY around
      the selected item; tapping another visible item moves the chrome
      there; tapping empty stage clears the selection.
- [ ] An item selected in the layers panel stays drawn with all other
      active items simultaneously.
- [ ] Drag the selected item: the others do not move (per-item transforms).
- [ ] Preview matches the exported frame for the same moment (positions
      are normalized 0..1 in both paths).

## Áudio no preview

- [ ] Two audio tracks whose windows overlap both sound SIMULTANEOUSLY in
      the preview (per-track ExoPlayer).
- [ ] Play, pause and seek from the transport: every audio track follows
      the video playhead (starts, pauses, seeks together).
- [ ] Drag an audio bar while playing: no stutter/re-seek loop and no
      silence after moving a bar whose trim start > 0 (v0.5.1
      window-coordinate fix).
- [ ] Per-track volume: lowering one track's volume in the audio sheet
      changes only that track in the preview.
- [ ] A trimmed clip (trimStart > 0) plays from the correct point when the
      playhead enters its window.
- [ ] Audio-only (or text/image/audio) project: playhead advances in real
      time and audio plays in sync (clip-less driver).

## Projeto vazio

- [ ] Home screen: the "Projeto vazio" action creates and opens a project
      without any video import dialog.
- [ ] The editor shows a BLACK preview stage with the empty hint over it;
      the timeline starts at 5 s (scrubbable playhead).
- [ ] The editor toolbar offers text, image and audio immediately (tools
      are not blocked by the missing video).
- [ ] Adding text/image/audio works as with video: drag, trim (adjust
      duration), move in time; the timeline duration grows past 5 s to the
      longest element.
- [ ] Export the video-less project: completes successfully; the file
      plays with a BLACK background and all text/image/audio content
      placed exactly as in the preview; duration equals the timeline
      duration.
- [ ] The export screen shows the "black background" note for video-less
      projects instead of blocking with "add at least one clip".
- [ ] Add a video afterwards: the project behaves exactly like a normal
      video project (duration = video length; audio/overlay rules return).

## Estrutura única (sem lógica duplicada por tipo)

- [ ] Selection is exclusive across kinds: selecting a video clip
      deselects any audio/overlay selection and vice versa (one selected
      clip of any kind at any time) — verify by switching between the
      timeline rows and the layers panel.
- [ ] The audio timeline shows one row per audio clip (no "+N mais" cap);
      the column scrolls with many tracks.
- [ ] Deleting a clip of any kind deselects cleanly (no stale chrome).

## Regression sweep

- [ ] v0.5.0 keyframe UX intact: auto-key drag, marker tap/drag, prev/next
      navigation, quick-panel keyframe indicator.
- [ ] v0.5.0 text animation intact: FADE/SLIDE_UP/POP/TYPEWRITER by
      word/letter with hold and stagger (preview == export).
- [ ] v0.4.0 gizmos still work (move/scale/rotate/pinch/twist, no rotation
      seam jumps; locked chrome bounds-only).
- [ ] Playback: play/pause/seek across the whole timeline; replay after
      end.
- [ ] Export a normal video project completes and the file plays with all
      overlays correct (position/scale/rotation/opacity/timing).
- [ ] Old project (created in v0.2.x–v0.5.x) opens with all layers intact.
- [ ] Undo/redo: each creation, drag, trim and selection-driven edit
      commits exactly one step.
- [ ] Text with the same content but different styling renders distinctly
      (bitmap cache key uses the full text + style).
- [ ] No diagnostics errors in the in-app log during the whole session.

**Status: PENDING — must be executed on a physical device. Nothing in this
checklist may be reported as "verified" without that run.**
