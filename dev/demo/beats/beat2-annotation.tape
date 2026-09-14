# Beat 2 — Live annotation processing. Narration captions (burned in post-render):
#   (1) "Go to definition — the User record"  (its Javadoc explains the @Builder processor)
#   (2) "Add a field, save — your build's annotation processor regenerates the builder"
#   (3) "The regenerated createdAt setter completes in your code"
# Note: we do NOT navigate into the generated UserBuilder — gd on a generated type resolves to the
# STALE app/target/generated-sources/annotations copy (name/age + a "not abstract" error), not the
# freshly regenerated .lathe/.../generated-sources copy. Regeneration is proven by the completion in
# Main (Lathe's in-memory symbols) and described in the captions. (Potential Lathe gd bug — see notes.)
# Part of the Lathe demo; see dev/demo/demo-script.md. Run from the repo root after ./dev/demo/prepare.sh.
#   vhs dev/demo/beats/beat2-annotation.tape
#
# Story: edit a @Builder record; on :w the annotation processor regenerates UserBuilder, and Lathe
# completes against the freshly generated code. All line/motion navigation (never /search).
# Conventions learned in beat 1: blink.cmp <Esc> only cancels the menu (press Escape twice), and the
# cursor keeps its column after NG (re-anchor with `_`).
Output "docs/videos/beat2-annotation.mp4"
Require nvim

Set Shell "bash"
Set FontFamily "Monaco, Cascadia Mono NF"
Set FontSize 24
Set Width 1800
Set Height 1080
Set Padding 30
Set TypingSpeed 55ms

# --- hidden setup: isolated config + Lathe from checkout + invoker cache; reset the app sources,
# capture fresh, open Main and warm the index (gd + completion need it). ---
Hide
Type 'R="$PWD"' Enter
Type 'export XDG_CONFIG_HOME="$R/dev/demo/.nvim/config" XDG_DATA_HOME="$R/dev/demo/.nvim/data" XDG_STATE_HOME="$R/dev/demo/.nvim/state" XDG_CACHE_HOME="$R/dev/demo/.nvim/cache"' Enter
Type 'export LATHE_NVIM_DIR="$R/lathe-maven-plugin/src/main/neovim" LATHE_CACHE="$R/lathe-maven-plugin/target/it-home/.cache/lathe"' Enter
Type 'export MAVEN_ARGS="-Dmaven.build.cache.enabled=false"' Enter
Type 'cd "$R/lathe-maven-plugin/target/it/multi-module" || exit 1' Enter
Type 'cp "$R/lathe-maven-plugin/src/it/multi-module/app/src/main/java/com/example/app/Main.java" app/src/main/java/com/example/app/Main.java' Enter
Type 'cp "$R/lathe-maven-plugin/src/it/multi-module/app/src/main/java/com/example/app/User.java" app/src/main/java/com/example/app/User.java' Enter
Type 'mvn -q clean test -Dlathe.capture.only=true >/dev/null 2>&1' Enter
Sleep 12s
Type 'clear' Enter
Type 'nvim -n app/src/main/java/com/example/app/Main.java' Enter
Sleep 1500ms
# Recording-only override: the config's `gd` is telescope lsp_definitions, whose single-result
# auto-jump renders at a shrunk UI grid under VHS (a headless artifact — it's fine interactively).
# Native vim.lsp.buf.definition gives the identical visible outcome (jump to User.java) with no glitch.
# The config binds `gd` buffer-local on LspAttach, so re-apply native gd on every attach via an autocmd
# registered AFTER the config (so it wins whatever the timing), plus cover the current buffer directly.
# `:echo ""` then clears the command line so the :lua text doesn't bleed into the visible clip.
Type ':lua vim.api.nvim_create_autocmd("LspAttach", {callback=function(a) vim.keymap.set("n","gd",vim.lsp.buf.definition,{buffer=a.buf}) end})' Enter
Sleep 200ms
Type ':lua vim.keymap.set("n","gd",vim.lsp.buf.definition,{buffer=true})' Enter
Sleep 200ms
Type ':echo ""' Enter
Sleep 8s
Show

# --- 1) go to definition: User -> User.java ---
Type '8G'                 # the `final User user = ...` line
Type '_'
Type 'w'                  # -> `User`
Sleep 700ms
Type 'gd'                 # -> User.java (native definition, per the hidden override above)
Sleep 2600ms             # dwell on the @Builder record

# --- 2) add an Instant createdAt component (type completion + auto-import) ---
Type 'f)'                 # the params' closing ')'
Type 'i'                  # insert before it
Type ', Instant'
Sleep 1500ms             # blink menu: Instant (java.time) with an auto-import edit
Enter                     # accept -> Instant, import java.time.Instant added
Type ' createdAt'
Sleep 200ms
Escape
Sleep 150ms
Escape
Sleep 1600ms

# --- 3) save -> the processor regenerates UserBuilder (described in the caption, not shown) ---
Type ':w'
Enter
Sleep 4000ms             # full compile on save runs the annotation processor -> UserBuilder regenerated

# --- 4) back in Main: the regenerated builder setter completes ---
Ctrl+O                    # jump back to Main.java
Sleep 1600ms
Type '8G'
Type '$'                  # end of line (`;`)
Type 'F.'                 # the '.' of `.build()`
Type 'i'                  # insert before it
Type '.crea'
Sleep 1500ms             # blink menu: createdAt(Instant) — the freshly generated setter
Enter                     # accept -> .createdAt(...)
Type 'Instant'
Sleep 1400ms             # Instant completion + auto-import into Main
Enter
Type '.now()'
Sleep 200ms
Escape
Sleep 150ms
Escape
Sleep 1000ms
Type ':w'
Enter
Sleep 2800ms             # clean compile with the new component wired through

Sleep 800ms
