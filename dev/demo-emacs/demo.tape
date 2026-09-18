# Lathe — Emacs (Eglot), simplified hero demo. Run from the repo root:
#   ./dev/demo-emacs/prepare.sh          # one-time: isolated package dir
#   vhs dev/demo-emacs/demo.tape         # -> docs/videos/emacs-demo.mp4
#
# A shorter Emacs counterpart to the Neovim hero (dev/demo/demo.tape), on the
# SAME multi-module fixture and classes. One thread: Lathe works from your real
# Maven build.
#   Beat 1  cross-module go-to-definition (app Main -> core StringUtils)
#   Beat 2  go-to-definition into an annotation-processor-GENERATED type (UserBuilder)
#
# Server/launcher come from the invoker cache (LATHE_CACHE), same as the Neovim
# tapes. The slow config load + connect runs under Hide (VHS drops hidden time),
# so the clip opens on the ready file. Meta is sent as ESC (terminal convention).

Output docs/videos/emacs-demo.mp4
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
Type 'export LATHE_CACHE="$R/lathe-maven-plugin/target/it-home/.cache/lathe"' Enter
Type 'export LATHE_DEMO_ELPA="$R/dev/demo-emacs/emacsd/elpa/" LATHE_SKIP_PKG_INSTALL=1' Enter
Type 'clear' Enter
Type 'emacs -nw -q -l "$R/dev/demo-emacs/demo-init.el" "$R/lathe-maven-plugin/target/it/multi-module/app/src/main/java/com/example/app/Main.java"' Enter
Sleep 20s
Show

Sleep 1200ms

# --- Beat 1: cross-module go-to-definition (app Main -> core StringUtils) ---
Escape
Type "gg"           # M-g g -> goto-line
Type "9"
Enter                # the StringUtils.upper(...) call line
Sleep 500ms
Ctrl+S
Type "StringUtils"
Enter                # isearch to the call site on this line
Escape
Type "b"             # M-b -> start of the StringUtils symbol
Escape
Type "."             # M-. -> jumps into core/StringUtils.java (another module)
Sleep 2800ms         # dwell on the cross-module source
Escape
Type ","             # M-, -> back to Main.java
Sleep 900ms

# --- Beat 2: go-to-definition into a GENERATED type (UserBuilder) ---
Escape
Type "gg"
Type "8"
Enter                # the UserBuilder.builder()... line
Sleep 500ms
Ctrl+S
Type "UserBuilder"
Enter
Escape
Type "b"
Escape
Type "."             # M-. -> jumps into target/generated-sources/.../UserBuilder.java
Sleep 2800ms         # Lathe resolves annotation-processor-generated sources
Escape
Type ","
Sleep 900ms

# --- Beat 3: as-you-type completion, resolved from the build (User record) ---
Escape
Type "gg"
Type "9"
Enter                # the println line, inside main() where `user` is in scope
Ctrl+E               # end of line
Enter                # fresh line below (auto-indented)
Type "user."
Escape
Tab                  # M-TAB -> completion-at-point; Corfu pops with the record's members
Sleep 2800ms         # dwell: name(), age() -- accessors from the reactor
Ctrl+G               # dismiss the popup
Ctrl+A
Ctrl+K
Ctrl+K               # remove the scratch line, leaving the file as we found it
Sleep 2800ms         # let the server re-analyze so diagnostics clear to a clean end
