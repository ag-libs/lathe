# Lathe × Emacs demo (zero-config Eglot)

A reproducible, scripted terminal demo built with [vhs](https://github.com/charmbracelet/vhs),
showing the **truly zero-config** path a newcomer would take: stock `emacs -Q` (no init, no packages)
plus the **built-in Eglot**, pointed at Lathe's launcher — exactly the flow the Eglot maintainer
suggests (`M-x eglot RET path/to/your-java-ls RET`).

The `.tape` is the source of truth; the video is regenerated from it, not hand-recorded. Same VHS
rendering approach and fixture as the Neovim demo (`dev/demo/`) and the IDE-config Emacs demo on the
`emacs-eglot-client` branch — this is just the **vanilla, zero-config** counterpart.

Output: `docs/videos/emacs-invoker.mp4`.

## What it shows (~35s, one take)

Recorded against the **public** `multi-module` invoker fixture (`com.example`, no private identifiers):

1. **Zero-config connect** — open `app/.../Main.java`, `M-x eglot RET /tmp/lathe-launcher.sh RET` →
   *"now managing (java-mode) … workspace ready"*. No jdtls, no `.dir-locals`, no server config.
2. **Cross-module go-to-definition** — `M-.` on `StringUtils` jumps from the `app` module into
   `core/.../StringUtils.java`, straight from the Maven model.
3. **Completion from the build** — complete after `user.` → the `User` record's accessors
   (`name`, `age`) resolved from the reactor, via the built-in `completion-at-point`.

## Why it works with no config

Eglot's default project detection uses the version-control backend, and Lathe takes the workspace
root **verbatim**. When the git root == the reactor root (where `.lathe/` lives), the two line up and
the stock command just works — which is exactly the "try it on a git-backed project first" advice.
`prepare.sh` therefore `git init`s the invoker fixture copy; nothing else is configured.

The underlying LSP flow (connect → completion → cross-module definition) is also covered
non-interactively — see the headless probe used during development, which asserts
`completion@user.` returns the record accessors and `goto-def StringUtils` lands in the `core` module.

## Prerequisites

Same toolchain as the Neovim demo (`dev/demo/README.md`): `vhs`, `ttyd`, `ffmpeg`, a Nerd/mono font,
and the headless-Chromium X libraries. `vhs` renders fully headless (off-screen Chromium via go-rod)
but **does need to download or find a Chromium** on first run — a sandbox with no network and no
pre-fetched `~/.cache/rod` browser will exit 0 yet produce no file. Render in a normal session.

`emacs` (29+, for the bundled Eglot; verified on 30.1) must be on `PATH`.

## Generate

```bash
./dev/demo-emacs/prepare.sh     # builds Lathe + the invoker fixture, git-inits the fixture copy
vhs dev/demo-emacs/demo.tape    # writes docs/videos/emacs-invoker.mp4
```

`prepare.sh` builds the invoker fixture (a synced copy at
`lathe-maven-plugin/target/it/multi-module` with `.lathe/`, plus an isolated server cache at
`target/it-home/.cache/lathe`). The tape's hidden setup symlinks that cache's launcher to
`/tmp/lathe-launcher.sh` — the stable, machine-independent path typed at the `M-x eglot` prompt — and
opens `emacs -nw -Q` on the fixture. Nothing touches your `~/.config`, `~/.local/share`, or
`~/.cache/lathe`.

## Tuning (the part that needs a human)

Lathe is async — server start + reactor indexing is ~15–18s (the tape's big `Sleep` after the connect
command), and completion/definition have latency. After the first render, watch the video and adjust:

- If *"workspace ready"* hasn't landed before Beat 2 starts, **increase the 18s connect `Sleep`**.
- If the `*Completions*` window or the cross-module jump hasn't rendered before the next keystroke,
  bump the preceding `Sleep`.
- Terminal Emacs `Meta` is driven as `Escape` then the key (`M-x` = `Escape` + `x`, `M-.` =
  `Escape` + `.`, `M-b` = `Escape` + `b`); `Ctrl` chords use VHS `Ctrl+S` / `Ctrl+G`.

## Privacy

Record only against the `com.example` fixture — never the private validation workspaces (repo policy).
