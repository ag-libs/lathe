# Lathe × Emacs demo

A scripted [VHS](https://github.com/charmbracelet/vhs) demo of Lathe driving **vanilla `emacs -Q` +
built-in Eglot** — no plugins, no config. The `.tape` is the source of truth; the video and GIF are
regenerated from it, not hand-recorded.

Shown (Understand → Navigate → Change), all from the real Maven build:
hover → JDK javadoc, cross-module go-to-definition, completion, extract-variable, rename, and live
`javac` diagnostics. Recorded against the public `multi-module` invoker fixture (`com.example`).

## Files

- `tour.tape` — the demo (the finished cut).
- `tour.ass` — burned-in lower-third captions, timed to the beats.
- `title-card.ass` / `end-card.ass` — the intro/outro slices.
- `prepare.sh` — builds Lathe + the invoker fixture (so `.lathe/` exists) and `git init`s it.

## Reproduce

```bash
./dev/demo-emacs/prepare.sh                         # build Lathe + the fixture (one time)
vhs dev/demo-emacs/tour.tape                         # -> docs/videos/emacs-tour.mp4

# burn captions:
ffmpeg -y -i docs/videos/emacs-tour.mp4 -vf "subtitles=dev/demo-emacs/tour.ass" \
  -c:v libx264 -pix_fmt yuv420p -crf 20 docs/videos/emacs-tour-captioned.mp4

# render intro/outro cards (match the tour's 1600x900):
ffmpeg -y -f lavfi -i "color=c=0x16181d:s=1600x900:r=25" -t 2.6 \
  -vf "subtitles=dev/demo-emacs/title-card.ass" -c:v libx264 -pix_fmt yuv420p -crf 20 docs/videos/emacs-title.mp4
ffmpeg -y -f lavfi -i "color=c=0x16181d:s=1600x900:r=25" -t 3.0 \
  -vf "subtitles=dev/demo-emacs/end-card.ass"   -c:v libx264 -pix_fmt yuv420p -crf 20 docs/videos/emacs-end.mp4

# stitch title -> tour -> end (re-encode for uniform params):
printf "file '%s'\n" "$PWD/docs/videos/emacs-title.mp4" "$PWD/docs/videos/emacs-tour-captioned.mp4" \
  "$PWD/docs/videos/emacs-end.mp4" > /tmp/list.txt
ffmpeg -y -f concat -safe 0 -i /tmp/list.txt -c:v libx264 -pix_fmt yuv420p -crf 20 -r 25 \
  docs/videos/emacs-tour-final.mp4

# GIF (two-pass palette), published to the tracked path:
ffmpeg -y -i docs/videos/emacs-tour-final.mp4 \
  -vf "fps=10,scale=1000:-1:flags=lanczos,palettegen=max_colors=256:stats_mode=diff" /tmp/pal.png
ffmpeg -y -i docs/videos/emacs-tour-final.mp4 -i /tmp/pal.png \
  -lavfi "fps=10,scale=1000:-1:flags=lanczos[x];[x][1:v]paletteuse=dither=none:diff_mode=rectangle" \
  docs/emacs-tour.gif
```

All intermediates land in `docs/videos/` (gitignored); only `docs/emacs-tour.gif` is committed.

## Notes

- The tape launches Emacs with a one-line `--eval` that registers Lathe's launcher for `java-mode`
  and calls `eglot-ensure` — the "one line of Elisp" a real user keeps in `init.el`. It points at a
  specific installed server (`~/.cache/lathe/servers/<version>/lathe-launcher.sh`); adjust the version
  if yours differs.
- Meta keys are sent as real `Alt+` chords (atomic and reliable) — never faked with `Escape`, which
  intermittently self-inserts and corrupts the buffer.
- Rendering VHS on Linux uses a headless Chromium. On Ubuntu 23.10+ its AppArmor user-namespace
  restriction blocks Chrome's sandbox — prefix the render with `VHS_NO_SANDBOX=true`. A GL-less box
  additionally needs software WebGL (`--use-gl=angle --use-angle=swiftshader`) via a launcher shim.
- Only navigation / completion / refactor / diagnostics are shown. Run/test/debug and scaffolding are
  Neovim-client features, not part of the plain-LSP surface Eglot consumes.
```
