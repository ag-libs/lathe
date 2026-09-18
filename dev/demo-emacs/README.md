# Emacs demo (work in progress)

Reproducible pipeline for recording the Lathe + Emacs (Eglot) demo. **The rendered
videos are not committed** — they are regenerated from the scripts here and land
in `docs/videos/emacs-*` (gitignored). This is WIP: the clips are not
publish-ready and Emacs is not yet advertised in the README.

## Prerequisites

- **Emacs 29+** (verified on 30.1), **vhs**, **ffmpeg**, DejaVu fonts.
- A built Lathe (`mvn install`), so `~/.cache/lathe/current/lathe-launcher.sh` exists.
- One-time package install for the recording config:
  ```sh
  ./dev/demo-emacs/prepare.sh        # populates dev/demo-emacs/emacsd/elpa (gitignored)
  ```

## Fixtures

Two are used, on purpose:

- **The Lathe checkout itself** (`hero.tape`, `fold.tape`) — a real multi-module
  Maven project whose dependency (gson/guava/lsp4j) and JDK sources are already
  captured, so go-to-definition lands in recognizable library code. Uses the
  default `~/.cache/lathe`. Note: internal cross-module jumps can land in
  `.claude/worktrees/*` duplicate modules, so cross-module is demoed elsewhere.
- **The synthetic multi-module invoker fixture** (`demo.tape`) — clean
  `com.example` paths, ideal for cross-module and generated-source beats. Build it
  first via the Neovim demo's `dev/demo/prepare.sh`, then it is used with
  `LATHE_CACHE=lathe-maven-plugin/target/it-home/.cache/lathe`.

## Recording steps

Run from the repo root:

```sh
# 1. Hero on the Lathe checkout: completion, dep source, JDK source, imports fold.
vhs dev/demo-emacs/hero.tape          # -> docs/videos/emacs-hero-raw.mp4
./dev/demo-emacs/caption.sh           # -> docs/videos/emacs-hero.mp4  (lower-third captions)
./dev/demo-emacs/title-cards.sh       # -> docs/videos/emacs-hero-titled.mp4  (intro/outro)

# 2. Synthetic fixture: cross-module + generated-source beats (clean paths).
vhs dev/demo-emacs/demo.tape          # -> docs/videos/emacs-demo.mp4
./dev/demo-emacs/caption-demo.sh      # captions + slices crossmodule/generated clips

# 3. Per-feature clips from the captioned hero, and GIFs for embedding.
./dev/demo-emacs/clips.sh
./dev/demo-emacs/gif.sh

# Standalone: the imports-fold beat.
vhs dev/demo-emacs/fold.tape          # -> docs/videos/emacs-fold.mp4
```

## Files

| File | Role |
|---|---|
| `demo-init.el` | Recording config: loads `examples/emacs/init.el`, isolated package dir, modus-vivendi theme. Derives the repo root from its own path, so the shell may `cd` into a fixture first. |
| `hero.tape` | Hero beats on the Lathe checkout (auto-fold off during nav). |
| `demo.tape` | Cross-module + generated + completion on the synthetic fixture. |
| `fold.tape` | Imports fold, standalone. |
| `completion.tape` | Completion beat, standalone (early scratch tape). |
| `caption.sh` / `caption-demo.sh` | Burn beat-timed lower-third captions (ffmpeg `drawtext`). |
| `title-cards.sh` | Prepend/append intro & outro cards. |
| `clips.sh` | Slice the captioned hero into per-feature clips. |
| `gif.sh` | Convert mp4s to optimized GIFs. |
| `prepare.sh` | Install the demo's Emacs packages into an isolated dir. |

## Notes / lessons (so we don't re-derive them)

- **Slow load under VHS:** the config load + server connect takes ~15–20s
  interactively (much slower than batch). Do it under `Hide` (VHS drops hidden
  time) so the clip opens on the ready file.
- **Driving terminal Emacs:** send Meta as `Escape` + key (`M-.` = `Escape` then
  `.`), `goto-line` = `Escape "gg"`, `M-<` = `Escape "<"`.
- **Captions:** ffmpeg `drawtext:textfile=…` (no in-arg escaping) with
  `enable='between(t,a,b)'`, font DejaVuSans-Bold.
- **Verify** each render by extracting frames with `ffmpeg -ss … -frames:v 1`.
- `corfu-terminal` does paint the completion popup in the VHS TTY.
- End nav beats with a ~2.8s sleep so transient diagnostics clear before the last
  frame.

## Still to do before publishing

- The clips are rough; tighten pacing and captions.
- Consider a GUI-Emacs capture (childframe hovers, icons) for a more polished look.
- Wire the Emacs client into the docs index / README only once it is ready.
