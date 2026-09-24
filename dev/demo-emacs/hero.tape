# Lathe — Emacs hero (raw, pre-caption). Run from the repo root after prepare.sh:
#   vhs dev/demo-emacs/hero.tape         # -> docs/videos/emacs-hero-raw.mp4
#   then caption it:  ./dev/demo-emacs/caption.sh   # -> docs/videos/emacs-hero.mp4
#
# Story (zero-config vs jdtls): stock eglot reads your real Maven build, and that
# gives you what jdtls makes hard. Fixture is the Lathe checkout (real deps +
# JDK captured). Caption text + timings live in caption.sh, keyed to these beats:
#   ~0.0s  cold open : "Stock eglot. No jdtls, no project import. Reads your Maven build."
#   ~5s    completion: "Completion from your build's classpath"
#   ~12s   dep source: "Go to definition — into dependency sources (real files)"
#   ~18s   jdk source: "…and into the JDK"
#   ~24s   fold      : "Imports folded from the server's own ranges"
#
# Auto-fold is turned OFF here so the import block is visible during the nav beats
# and the fold beat shows the collapse. Meta is sent as ESC (terminal convention).

Output docs/videos/emacs-hero-raw.mp4
Require emacs

Set Shell "bash"
Set FontFamily "Monaco, Cascadia Mono NF"
Set FontSize 24
Set Width 1800
Set Height 1080
Set Padding 30
Set TypingSpeed 55ms

Hide
Type 'R="$PWD"' Enter
Type 'export LATHE_DEMO_ELPA="$R/dev/demo-emacs/emacsd/elpa/" LATHE_SKIP_PKG_INSTALL=1' Enter
Type 'clear' Enter
Type 'emacs -nw -q -l "$R/dev/demo-emacs/demo-init.el" --eval "(setq lathe-fold-imports-on-open nil)" lathe-server/src/main/java/io/github/aglibs/lathe/server/LatheLanguageServer.java' Enter
Sleep 20s
Show

# --- Cold open: the file is open, server connected, "workspace ready" ---
Sleep 3500ms

# --- Beat 1: completion from the build classpath ---
Escape
Type "<"                     # M-< : top of buffer (deterministic start)
Ctrl+S
Type "setReferencesProvider(true)"
Enter
Ctrl+E
Enter                        # fresh line inside createCapabilities (capabilities in scope)
Type "capabilities.set"
Escape
Tab                          # M-TAB -> completion-at-point; Corfu pops with lsp4j setters
Sleep 3000ms
Ctrl+G
Ctrl+A
Ctrl+K
Ctrl+K                       # remove scratch line
Sleep 1200ms

# --- Beat 2: go to definition into a dependency (gson) ---
Escape
Type "<"
Ctrl+S
Type "instanceof JsonObject"
Enter
Escape
Type "b"                     # M-b -> start of JsonObject
Escape
Type "."                     # M-. -> ~/.cache/lathe/deps/.../gson/JsonObject.java (read-only)
Sleep 3200ms
Escape
Type ","                     # back
Sleep 900ms

# --- Beat 3: go to definition into the JDK ---
Escape
Type "<"
Ctrl+S
Type "new ArrayList"
Enter
Escape
Type "b"
Escape
Type "."                     # M-. -> JDK source (java.base/.../ArrayList.java)
Sleep 3200ms
Escape
Type ","
Sleep 900ms

# --- Beat 4: fold the import block from the server's folding ranges ---
Escape
Type "<"                     # back to top so the fold is on screen
Sleep 400ms
Ctrl+C
Type "l"
Tab                          # C-c l TAB -> collapse imports to one summary line
Sleep 3000ms
