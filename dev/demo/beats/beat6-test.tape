# Beat 6 — Test (fail -> fix -> green). Narration captions (burned in post-render), finalized separately:
#   (1) "Run the test — replayed from your capture"
#   (2) "Red — the assertion fails, with the stack trace"
#   (3) "Fix it, run again — green"
# Part of the Lathe demo; see dev/demo/demo-script.md. Run from the repo root after ./dev/demo/prepare.sh.
#   vhs dev/demo/beats/beat6-test.tape
#
# Story: a JUnit test replayed from captured .lathe bytecode. \tf (Run File Tests, via neotest +
# Lathe's adapter) runs GreetTest red — the assertion failure and stack trace stream into the docked
# console; delete the extra `!`, \tf again -> green. No Maven, no recompile. The failing test lives in
# beat6-fixture/ (the committed fixture stays green so `mvn verify` passes); the tape copies it in.
# leader = '\'. Line/motion navigation only.
Output "docs/videos/beat6-test.mp4"
Require nvim

Set Shell "bash"
Set FontFamily "Monaco, Cascadia Mono NF"
Set FontSize 24
Set Width 1800
Set Height 1080
Set Padding 30
Set TypingSpeed 45ms

# --- hidden setup: reset jpms to baseline + drop in the failing test, capture, open it, warm index +
# neotest discovery (the gutter glyphs appear once the server confirms the file open). ---
Hide
Type 'R="$PWD"' Enter
Type 'source "$R/dev/demo/env.sh"' Enter
Type 'cd "$R/lathe-maven-plugin/target/it/multi-module" || exit 1' Enter
Type 'cp "$R/lathe-maven-plugin/src/it/multi-module/jpms/src/main/java/com/example/jpms/HelloMain.java" jpms/src/main/java/com/example/jpms/HelloMain.java' Enter
Type 'cp "$R/lathe-maven-plugin/src/it/multi-module/jpms/src/main/java/module-info.java" jpms/src/main/java/module-info.java' Enter
Type 'cp "$R/dev/demo/beats/beat6-fixture/GreetTest.java" jpms/src/test/java/com/example/jpms/GreetTest.java' Enter
Type 'mvn -q clean test -Dlathe.capture.only=true >/dev/null 2>&1' Enter
Sleep 12s
Type 'clear' Enter
Type 'nvim -n jpms/src/test/java/com/example/jpms/GreetTest.java' Enter
Sleep 9s          # server start + index + neotest discovery (gutter glyph on the test)
Show

# --- 1) run the file's tests -> red, then show the docked output (exception + stack trace) ---
Type '\tf'                # <leader>tf = Run File Tests (neotest + Lathe adapter, replayed from .lathe)
Sleep 2800ms             # replay; the test fails -> red gutter + "1 failed"
Type '\to'                # <leader>to = Test Output (docked, navigable): the AssertionFailedError + trace
Sleep 3400ms             # dwell on the failure and its stack trace

# --- 2) focus the editor, fix the assertion (delete the extra `!`), save so Lathe recompiles .lathe ---
Ctrl+W
Type 't'                  # back to the editor window
Type '11G'                # the failing assertion (its inline failure shows here too)
Type '_'                  # re-anchor to first non-blank (the cursor keeps its column after NG)
Type 'f!'                 # first `!` of the expected string's `!!`
Type 'x'                  # delete one -> "Hello, World!"
Type ':w'                 # save -> on-save recompile updates .lathe (the replay reads .lathe)
Enter
Sleep 1200ms

# --- 3) run again -> green ---
Type '\tf'
Sleep 3600ms             # passes -> green gutter; the docked output updates to the passing run

