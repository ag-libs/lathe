# Lathe demo GIF

A reproducible, scripted terminal demo built with [vhs](https://github.com/charmbracelet/vhs).
The tapes under `beats/` are the source of truth: the GIF is regenerated from them, never hand-recorded.
`record.sh` publishes it as `docs/demo-<hash>.gif` and points the project README (and the lathe.nvim mirror's) at it.
The plan behind each beat, and how the tapes are written, is in [demo-script.md](demo-script.md).

## What it shows (≈60 s)

Every beat runs on [spring-petclinic](https://github.com/spring-projects/spring-petclinic), a public project with no Lathe in its build, at a commit pinned in `env.sh`.

1. **`:LatheInit`**: open `Owner.java` with nothing set up, run `:LatheInit maven`, and go to definition once the server attaches.
2. **Editing**: completion from the build's classpath, a type mismatch flagged as you type, a clean save.
3. **Run**: `PetClinicApplication` runs from `.lathe/`, its Spring Boot start-up streaming until it has started, then stops.
4. **Debug**: a breakpoint in `OwnerTests`, two watches on the paused frame, then the test passes.
5. **Test**: `OwnerTests` runs red, shows the failure and its stack trace, and runs green once fixed.

## Prerequisites (Linux laptop)

`vhs` renders fully headless: it drives a `ttyd` terminal and captures frames with an off-screen Chromium (via `go-rod`), then encodes with `ffmpeg`.
It does not use your Wayland or X session and needs no `$DISPLAY`, so it runs the same under Sway or over SSH.
The headless Chromium still links the usual X client libraries; nothing is shown, but they must be present.

`nvim` 0.12 or later is assumed.
The first run needs the network: `prepare.sh` clones petclinic and installs the sample config's plugins, and vhs downloads its headless Chromium.

**Debian/Ubuntu** (installs `vhs` from Charm's apt repo, so no Go toolchain is needed):
```bash
sudo mkdir -p /etc/apt/keyrings
curl -fsSL https://repo.charm.sh/apt/gpg.key | sudo gpg --dearmor -o /etc/apt/keyrings/charm.gpg
echo "deb [signed-by=/etc/apt/keyrings/charm.gpg] https://repo.charm.sh/apt/ * *" \
  | sudo tee /etc/apt/sources.list.d/charm.list
sudo apt update
# vhs + terminal + encoder + font, and the headless-Chromium X libs (apt skips any you already have)
sudo apt install -y vhs ttyd ffmpeg fonts-jetbrains-mono \
  libxcomposite1 libxdamage1 libxrandr2 libxfixes3 libxkbcommon0 libgbm1 libnss3 libasound2t64
```

**Arch:**
```bash
sudo pacman -S --needed vhs ttyd ffmpeg chromium ttf-jetbrains-mono
```

Verify the toolchain before recording:
```bash
vhs --version && ttyd --version && ffmpeg -version | head -1
```

## Generate

```bash
./dev/demo/prepare.sh        # build + install Lathe, clone petclinic, set it up with :LatheInit
./dev/demo/record.sh         # render, caption and stitch the beats -> docs/demo-<hash>.gif
```

`prepare.sh` clones petclinic into `~/lathe-demo/warm/spring-petclinic` (`LATHE_DEMO_WORK` moves it) and runs `:LatheInit` there once, headless.
That warms everything the on-camera `:LatheInit` would otherwise wait for (the Maven wrapper, petclinic's dependencies, Lathe's bootstrap jars) and leaves the clone set up for beats 1–6.
Beat 0 clones it again to `~/lathe-demo/spring-petclinic`, so its `:LatheInit` starts from nothing.
The clones live outside this repo on purpose: the repo has its own `.lathe/`, which would claim a clone inside it.

The demo runs an isolated copy of the sample config [examples/nvim](../../examples/nvim/init.lua) under `dev/demo/.nvim`, with the Lathe client from this checkout.
Nothing touches your `~/.config`, `~/.local/share` or `~/.cache/lathe`.
Re-run `prepare.sh` after changing the server, the client or the sample config.
`LATHE_DEMO_NVIM_CONFIG=/path/to/nvim` records with another config, and `LATHE_DEMO_CLIENT=/path/to/lathe-neovim/runtime` with another checkout's client.

## Recording well

- **Record on a quiet machine.** VHS captures fewer frames while the machine is busy, so a busy machine speeds up the heavy stretches (the setup build, the Spring Boot start-up) by an amount you do not control.
- **Re-time the captions after the final render.** The `.ass` captions use fixed timestamps, and the clips' lengths move a little from render to render.
  `ffmpeg -i docs/videos/<beat>.mp4 -vf "fps=2,drawtext=text='%{pts\:hms}':…,tile=6x4"` makes a timestamped contact sheet to read them from.
- **Render one beat** with `vhs dev/demo/beats/<beat>.tape`: it writes `docs/videos/<beat>.mp4`, uncaptioned.

## Files

- `beats/*.tape`: one VHS tape per beat; `beats/*.ass`: its captions, and the title and end cards.
- `env.sh`: the environment every tape and `prepare.sh` share (the pinned petclinic commit, the clones, the isolated config).
- `prepare.sh` and `init-headless.lua`: the one-off setup, including the headless `:LatheInit`.
- `record.sh`: renders, captions, stitches and publishes the GIF.

## Privacy

Record only public projects (spring-petclinic, the repo's own fixtures), never a private validation workspace.
The recording shows the clone's absolute path in `:LatheInit`'s prompt and the run's command line.
