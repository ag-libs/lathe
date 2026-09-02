# Lathe demo GIF

> **Status: work in progress (first draft).** The tooling and pipeline work end-to-end, but the
> `demo.tape` scenario is a first draft that does **not** yet showcase Lathe's features well — it
> needs a substantial rework before it becomes the published demo. `docs/demo.gif` is not committed
> and not yet embedded in the project README.

A reproducible, scripted terminal demo built with [vhs](https://github.com/charmbracelet/vhs).
The `.tape` is the source of truth; the GIF is regenerated from it, not hand-recorded.

Output: `docs/demo.gif` (once the scenario is finalized, it will be embedded at the top of the
project README).

## What it shows (short hero, ~15–20s, looping)

Recorded against the **public** `multi-module` invoker fixture (`com.example`, no private identifiers):

1. **Completion** — open `app/.../FormalGreeter.java`, type `name.` → member completion on `String`.
2. **Cross-module go-to-definition** — `gd` on `Greeter` (in `implements Greeter`) jumps from the `app`
   module into `core/.../Greeter.java`, straight from the Maven model.
3. **Run a test** — open `jpms/.../HelloTest.java`, run the file (`<leader>tf`) → the neotest gutters
   turn green, the docked console streams output (`greet_prints_writesToBothStreams` prints to both
   streams), and the completion toast fires.

## Prerequisites (Linux laptop)

`vhs` renders **fully headless**: it drives a `ttyd` terminal and captures frames with an *off-screen*
Chromium (via `go-rod`), then encodes with `ffmpeg`. It does **not** use your Wayland/Sway session and
needs no `$DISPLAY` — it runs the same under Sway or over SSH. The one Sway/Wayland gotcha is that the
headless Chromium still links the usual X client shared libraries (nothing is shown, but they must be
present); the easy way to get them all is to install the `chromium` package.

`nvim` (0.12+, matching the demo config) is assumed. First run needs network: `prepare.sh` fetches your
plugins into the config copy, and vhs downloads its headless Chromium.

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
vhs downloads its own headless Chromium on first run, so these X libraries (not a browser package)
are what it actually needs.

**Arch (Sway is common here):**
```bash
sudo pacman -S --needed vhs ttyd ffmpeg chromium ttf-jetbrains-mono
# vhs and ttyd are in the extra repo; chromium provides the headless-browser libraries.
```

Verify the toolchain before recording:
```bash
vhs --version && ttyd --version && ffmpeg -version | head -1
```

## Generate

```bash
./dev/demo/prepare.sh        # installs Lathe, runs the multi-module invoker fixture, pre-installs plugins
vhs dev/demo/demo.tape       # writes docs/demo.gif
```

`prepare.sh` builds the invoker fixture (a synced copy at `lathe-maven-plugin/target/it/multi-module`
with `.lathe/`, plus an isolated server cache at `target/it-home/.cache/lathe`) and takes an **isolated
copy of your real Neovim config** under `dev/demo/.nvim`, so the demo looks like your editor
(colorscheme, treesitter, cmp, neotest, the Lathe integration) without touching your live `~/.config`
or `~/.local/share`. The tape runs that copy with two env vars:

- `LATHE_NVIM_DIR` → the Lathe **client** from this checkout;
- `LATHE_CACHE` → the Lathe **server** from the invoker cache.

Both are knobs your config already honors (`lua/lathe_paths.lua`). Nothing touches `~/.cache/lathe`.
Re-run `prepare.sh` after changing the server or client.

> Point it at a different config with `LATHE_DEMO_NVIM_CONFIG=/path/to/nvim ./dev/demo/prepare.sh`.
> Kanagawa is transparent in your config, so the terminal background shows through; set
> `transparent = false` in the copy's `colorscheme.lua` if you want a solid editor background.

## Tuning (the part that needs a human)

Lathe is async — server start + workspace indexing is ~3s, and completion/diagnostics have latency.
The tape uses `Sleep`s to wait for each step to land on screen. After the first render, watch the GIF
and adjust:

- If completion/diagnostics/gutter haven't appeared before the next keystroke, **increase the
  preceding `Sleep`**.
- Keep the total under ~20s so the loop stays punchy; trim dead air rather than beats.
- `Set Width/Height/FontSize` control the frame size; `Set PlaybackSpeed` and the output file size
  trade off duration vs. weight (vhs encodes the GIF with ffmpeg).

## Files

- `demo.tape` — the vhs script (beats + waits + output).
- `prepare.sh` — builds Lathe + the invoker fixture and stages an isolated copy of your Neovim config.
- The demo runs **your** config (copied to `dev/demo/.nvim`), so the keys are your own —
  `gd` (definition), `<leader>tf` (run file tests), `<leader>to` (docked output), `<leader>ts`
  (summary). There is no bundled `init.lua`.

## Privacy

Record only against the `com.example` fixture — never the private validation workspaces (repo policy).
