# Lathe × Zed demo

A scripted, headless recording of Lathe in **stock Zed** — the official Java extension with Lathe in
its `jdtls` server slot via one `.zed/settings.json`.
The scripts are the source of truth; the video and GIF are regenerated from them, not hand-recorded.

Shown, all from the real Maven build: hover → JDK javadoc, cross-module go-to-definition,
go-to-implementation, extract-variable, rename, completion, and live `javac` diagnostics.
Recorded against the public `multi-module` invoker fixture (`com.example`).

## Files

- `prepare.sh` — builds Lathe + the invoker fixture (so `.lathe/` exists), drops `fixture-settings.json`
  in as the fixture's `.zed/settings.json`, and `git init`s it.
- `record.sh` — records the tour headlessly → `docs/videos/zed-tour.mp4`.
- `tour.sh` — the tour itself: one `wtype` argument list (keys, text, pauses).
- `zed-config/` — the sandboxed Zed profile's settings and demo keymap.
- `tour.ass` — burned-in captions, timed to the beats; `title-card.ass` / `end-card.ass` — intro/outro.
- `render.sh` — captions + cards → content-hashed `docs/zed-tour-<hash>.gif`, and rewrites the embed in
  `docs/guide/editors/zed.md`.

## Reproduce

```bash
./dev/demo-zed/prepare.sh      # build Lathe + the fixture (one time)
./dev/demo-zed/record.sh       # -> docs/videos/zed-tour.mp4
./dev/demo-zed/render.sh       # -> docs/zed-tour-<hash>.gif + embed update
```

Needs `zed`, `sway`, `grim`, `wtype`, `ffmpeg`, and Zed's Java extension installed in your normal
profile (record.sh copies it into the sandbox).
All intermediates land in `docs/videos/` (gitignored); only the content-hashed GIF is committed.

## How the recording works

- A private **headless sway** session (`WLR_BACKENDS=headless`) hosts Zed, so the recording never touches
  your desktop; `grim` captures frames, `ffmpeg` encodes them with wall-clock timestamps.
- Zed runs with `--user-data-dir` in a throwaway sandbox (fresh state, the demo keymap/theme, the Java
  extension copied in); it starts in Restricted Mode, and record.sh trusts the project with Enter.

## Gotchas (why tour.sh looks the way it does)

- **One `wtype` process for the whole tour.** Each `wtype` exit destroys its virtual keyboard, which Zed
  treats as focus loss and dismisses any open popup (hover, completion, code actions).
- **Every key is press → hold → release.** Zero-length taps (`wtype -k`, plain text) are intermittently
  dropped while Zed is busy with Lathe responses.
- **`wtype -d 0` is rejected** ("Invalid sleep time"); the minimum is 1.
- **Completion and diagnostics come last.** After a completion popup has been shown and dismissed, Zed
  intermittently ignores the F-key bindings; typing and ctrl chords keep working.
- **Rename goes through the command palette** for the same reason — after the navigation beats, the `F2`
  binding is intermittently ignored, while `editor: rename` from the palette is reliable.
- **Dismiss popups before deleting a line.** An open completion menu swallows the delete-line chord.
