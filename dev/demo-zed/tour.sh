# The Zed tour, as ONE wtype argument list (sourced by record.sh).
# It must be a single wtype process: each wtype exit destroys its virtual keyboard, which Zed treats
# as focus loss and dismisses any open popup (hover, completion, code actions).
# Positions are line:column in app/src/main/java/com/example/app/Main.java of the multi-module fixture.
# Keys F3/F6-F10 are bound in zed-config/keymap.json; F12 and ctrl-shift-k are Zed defaults.
# Every key is an explicit press, hold, release: zero-length taps (wtype -k, or plain text) are
# intermittently lost while Zed is busy handling Lathe responses.

T=()
wait_ms() { T+=(-s "$1"); }
key() { T+=(-P "$1" -s 40 -p "$1" -s 150); }
chord() { T+=(-M "$1" -s 80 -P "$2" -s 60 -p "$2" -s 80 -m "$1" -s 150); }
delete_line() { key Escape; wait_ms 400; T+=(-M ctrl -M shift -s 80 -P k -s 60 -p k -s 80 -m shift -m ctrl -s 150); }
type_text() {
  local text="$1" ch sym i
  for ((i = 0; i < ${#text}; i++)); do
    ch="${text:i:1}"
    case "$ch" in
      " ") sym=space ;; ":") sym=colon ;; "=") sym=equal ;; ";") sym=semicolon ;; ".") sym=period ;;
      *) sym="$ch" ;;
    esac
    T+=(-P "$sym" -s 40 -p "$sym" -s 50)
  done
}
palette() { T+=(-M ctrl -M shift -s 80 -P p -s 60 -p p -s 80 -m shift -m ctrl -s 600); type_text "$1"; wait_ms 700; key Return; }
goto() { chord ctrl g; wait_ms 400; type_text "$1"; wait_ms 300; key Return; }

wait_ms 2500                       # calm opening shot of Main.java

# 1) Hover: JDK javadoc for println
goto 21:18; wait_ms 700
key F6; wait_ms 4500
key Escape; wait_ms 600

# 2) Go to definition across modules: StringUtils (app) -> core
goto 21:28; wait_ms 800
key F12; wait_ms 3200
key F10; wait_ms 1200

# 3) Go to implementation: Greeter.greet -> lambda, method ref, and the named classes
goto 16:21; wait_ms 800
key F7; wait_ms 4500
key F3; wait_ms 1200

# 4) Extract variable (Lathe code action) on StringUtils.upper(user.name())
goto 21:28; wait_ms 500
key F8; key F8; wait_ms 1500
key F9; wait_ms 2200
key Return; wait_ms 2500

# 5) Rename the extracted local
goto 22:24; wait_ms 600
# Via the palette: after the navigation steps, Zed intermittently ignores the F2 binding.
palette "editor: rename"; wait_ms 1500
type_text "greeting"; wait_ms 500
key Return; wait_ms 2500

# Completion and diagnostics come last: after a completion popup has been shown and dismissed,
# Zed intermittently ignores the F-key bindings above, while typing and ctrl chords keep working.

# 6) Completion from the build: User record accessors
goto 22:1; key End; key Return
type_text "user."; wait_ms 3500
delete_line; wait_ms 1500

# 7) Live diagnostics: a real javac type error, then remove it
goto 22:1; key End; key Return
type_text "int n = greeting;"; key Escape; wait_ms 4500
delete_line; wait_ms 2500

# Hold on the clean, refactored file
wait_ms 2500
