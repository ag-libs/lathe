# Beat 3 — JPMS module graph. Narration captions (burned in post-render), finalized separately:
#   (1) "Use a JDK module you haven't required — Lathe flags it"
#   (2) "Add requires java.logging — module-aware completion"
#   (3) "Save — the module resolves"
# Part of the Lathe demo; see dev/demo/demo-script.md. Run from the repo root after ./dev/demo/prepare.sh.
#   vhs dev/demo/beats/beat3-jpms.tape
#
# Story: a deliberate context switch to the modular `jpms` module. Use a JDK type the module doesn't
# require (java.util.logging.Logger) -> module-access diagnostic; open module-info.java, add
# `requires java.logging` via module-aware completion, save -> the diagnostic resolves. This end state
# (logger + requires) is what beat 4 runs. All line/motion navigation (never /search). Conventions from
# earlier beats: blink.cmp <Esc> only cancels the menu (Escape twice); re-anchor with `_` after NG.
Output "docs/videos/beat3-jpms.mp4"
Require nvim

Set Shell "bash"
Set FontFamily "Monaco, Cascadia Mono NF"
Set FontSize 24
Set Width 1800
Set Height 1080
Set Padding 30
Set TypingSpeed 55ms

# --- hidden setup: isolated config + Lathe from checkout + invoker cache; reset the jpms sources,
# capture fresh, open HelloMain and warm the index. ---
Hide
Type 'R="$PWD"' Enter
Type 'export XDG_CONFIG_HOME="$R/dev/demo/.nvim/config" XDG_DATA_HOME="$R/dev/demo/.nvim/data" XDG_STATE_HOME="$R/dev/demo/.nvim/state" XDG_CACHE_HOME="$R/dev/demo/.nvim/cache"' Enter
Type 'export LATHE_NVIM_DIR="$R/lathe-maven-plugin/src/main/neovim" LATHE_CACHE="$R/lathe-maven-plugin/target/it-home/.cache/lathe"' Enter
Type 'export MAVEN_ARGS="-Dmaven.build.cache.enabled=false"' Enter
Type 'cd "$R/lathe-maven-plugin/target/it/multi-module" || exit 1' Enter
Type 'cp "$R/lathe-maven-plugin/src/it/multi-module/jpms/src/main/java/com/example/jpms/HelloMain.java" jpms/src/main/java/com/example/jpms/HelloMain.java' Enter
Type 'cp "$R/lathe-maven-plugin/src/it/multi-module/jpms/src/main/java/module-info.java" jpms/src/main/java/module-info.java' Enter
Type 'mvn -q clean test -Dlathe.capture.only=true >/dev/null 2>&1' Enter
Sleep 12s
Type 'clear' Enter
Type 'nvim -n jpms/src/main/java/com/example/jpms/HelloMain.java' Enter
Sleep 9s
Show

# --- 1) use java.util.logging in a module that doesn't require it -> module-access diagnostic ---
Type '1G'
Type 'o'
Type 'import java.util.logging.Logger;'
Escape
Sleep 150ms
Escape
Sleep 500ms
Type '4G'                 # `public final class HelloMain {`
Type 'o'
Type 'private static final Logger logger = Logger.getLogger("hello");'
Escape
Sleep 150ms
Escape
Sleep 500ms
Type '10G'                # the println line in main
Type 'O'                  # open a line above it
Type 'logger.info("starting");'
Escape
Sleep 150ms
Escape
Sleep 400ms
Type '2G'                 # onto the import line so its message shows (virtual_text = current_line)
Sleep 3200ms             # dwell: "package java.util.logging is not visible"

# --- 2) open module-info and add the requires via module-aware completion ---
Type ':e jpms/src/main/java/module-info.java'
Enter
Sleep 1600ms
Type '2G'                 # the existing `requires io.github.aglibs.validcheck;`
Type 'o'
Type 'requires java.log'
Sleep 1400ms             # blink menu: java.logging (module completion)
Enter                     # accept -> requires java.logging
Type ';'
Escape
Sleep 150ms
Escape
Sleep 900ms
Type ':w'
Enter
Sleep 2000ms             # save module-info -> module descriptor refreshed

# --- 3) back in HelloMain: save -> the module-access diagnostic resolves ---
Type ':b HelloMain'
Enter
Sleep 1000ms
Type '2G'                 # the import line — still flagged until we recompile
Sleep 1400ms
Type ':w'
Enter
Sleep 3200ms             # recompile against the new module graph: the diagnostic clears

Sleep 800ms
