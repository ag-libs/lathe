#!/usr/bin/env bash
# Record the Zed tour headlessly: a private headless sway session, a sandboxed Zed profile (your own
# Zed config/state is never touched), grim frame capture, and one wtype process driving tour.sh.
#
#   ./dev/demo-zed/prepare.sh     # once
#   ./dev/demo-zed/record.sh      # -> docs/videos/zed-tour.mp4
#
# Needs: zed, sway, grim, wtype, ffmpeg, and Zed's Java extension installed in your normal profile
# (it is copied into the sandbox; it provides the Java grammar and the jdtls slot Lathe runs in).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
fixture="$repo/lathe-maven-plugin/target/it/multi-module"
out="$repo/docs/videos/zed-tour.mp4"
fps=15
zed_data="${XDG_DATA_HOME:-$HOME/.local/share}/zed"

for tool in zed sway grim wtype ffmpeg swaymsg; do
  command -v "$tool" >/dev/null || { echo "[demo] missing tool: $tool" >&2; exit 1; }
done
[ -d "$zed_data/extensions/installed/java" ] \
  || { echo "[demo] install Zed's Java extension first (zed: extensions -> Java)" >&2; exit 1; }
[ -f "$fixture/.zed/settings.json" ] || { echo "[demo] run ./dev/demo-zed/prepare.sh first" >&2; exit 1; }

sandbox="$(mktemp -d /tmp/lathe-zed-demo.XXXXXX)"
mkdir -p "$sandbox/data/config" "$(dirname "$out")"
cp -r "$zed_data/extensions" "$sandbox/data/"
cp "$here/zed-config/settings.json" "$here/zed-config/keymap.json" "$sandbox/data/config/"
cat >"$sandbox/sway.conf" <<'EOF'
output HEADLESS-1 resolution 1600x900 bg #16181d solid_color
default_border none
seat * hide_cursor 1
EOF

pids=()
cleanup() {
  touch "$sandbox/stop"
  sleep 0.5
  for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
  rm -rf "$sandbox"
}
trap cleanup EXIT

echo "[demo] starting headless sway…"
render_node="$(ls /dev/dri/renderD* 2>/dev/null | head -1 || true)"
sockets_before="$(ls "$XDG_RUNTIME_DIR" | grep -E '^wayland-[0-9]+$' || true)"
env -u WAYLAND_DISPLAY -u DISPLAY -u SWAYSOCK WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 \
  ${render_node:+WLR_RENDER_DRM_DEVICE=$render_node} \
  sway -c "$sandbox/sway.conf" >"$sandbox/sway.log" 2>&1 &
sway_pid=$!
pids+=("$sway_pid")
for _ in $(seq 50); do
  sock="$(ls "$XDG_RUNTIME_DIR"/sway-ipc.*."$sway_pid".sock 2>/dev/null || true)"
  [ -n "$sock" ] && break
  sleep 0.2
done
[ -n "$sock" ] || { echo "[demo] sway did not start; see $sandbox/sway.log" >&2; exit 1; }
export SWAYSOCK="$sock"
sockets_after="$(ls "$XDG_RUNTIME_DIR" | grep -E '^wayland-[0-9]+$')"
WAYLAND_DISPLAY="$(printf '%s\n%s\n' "$sockets_before" "$sockets_after" | grep . | sort | uniq -u | head -1)"
[ -n "$WAYLAND_DISPLAY" ] || { echo "[demo] could not find the headless sway's wayland socket" >&2; exit 1; }
export WAYLAND_DISPLAY
unset DISPLAY

echo "[demo] starting sandboxed Zed on $WAYLAND_DISPLAY…"
(cd "$fixture" && zed --foreground --user-data-dir "$sandbox/data" . app/src/main/java/com/example/app/Main.java \
  >"$sandbox/zed.log" 2>&1) &
pids+=("$!")
for _ in $(seq 60); do
  swaymsg -t get_tree | grep -q '"app_id": "dev.zed.Zed"' && break
  sleep 0.5
done
sleep 3

echo "[demo] trusting the project and waiting for Lathe…"
wtype -s 300 -k Return
for _ in $(seq 60); do
  grep -q "starting language server jdtls" "$sandbox/data/logs/Zed.log" 2>/dev/null && break
  sleep 0.5
done
sleep 12

echo "[demo] recording…"
# shellcheck source=tour.sh
source "$here/tour.sh"
(
  while [ ! -e "$sandbox/stop" ]; do grim -t ppm -; sleep 0.04; done \
    | ffmpeg -loglevel error -y -use_wallclock_as_timestamps 1 -f image2pipe -c:v ppm -i - \
        -vf "fps=$fps" -c:v libx264 -preset veryfast -crf 18 -pix_fmt yuv420p "$out"
) &
rec_pid=$!
sleep 0.5
wtype "${T[@]}"
touch "$sandbox/stop"
wait "$rec_pid"

echo "[demo] wrote $out"
