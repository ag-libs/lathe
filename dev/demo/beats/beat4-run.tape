# Beat 4 — Run. Narration captions (burned in post-render), see beat4-run.ass:
#   (1) "Run the Spring Boot app — from the captured classpath"
#   (2) "Live output — Tomcat up on :8080"
# Part of the Lathe demo; see dev/demo/demo-script.md. Run from the repo root after ./dev/demo/prepare.sh.
#   vhs dev/demo/beats/beat4-run.tape
#
# Story: <leader>rr (:LatheRun) on PetClinicApplication.main runs it from .lathe/ bytecode and the
# classpath :LatheInit captured — no Maven, no recompile. The Spring banner and the startup log stream
# into Lathe's docked output split until "Started PetClinicApplication"; :LatheRunStop ends it.
# Each step waits on the screen, not a fixed time: the machine may be busy. leader = Space.
Output "docs/videos/beat4-run.mp4"
Require nvim

Set Shell "bash"
Set FontFamily "Monaco, Cascadia Mono NF"
Set FontSize 24
Set Width 1800
Set Height 1080
Set Padding 30
Set TypingSpeed 45ms

# --- hidden setup: the clone prepare.sh set up with :LatheInit, sources reset; open the application
# class and wait for the workspace. `|| exit 1` guards the cd. ---
Hide
Type 'R="$PWD"' Enter
Type 'source "$R/dev/demo/env.sh"' Enter
Type 'renice -n 15 $$ >/dev/null' Enter
Type 'cd "$PETCLINIC" || exit 1' Enter
Type 'git checkout -q -- src' Enter
Type 'clear' Enter
Type 'nvim -n src/main/java/org/springframework/samples/petclinic/PetClinicApplication.java +33' Enter
Wait+Screen@60s /workspace ready/
Type ':lua Snacks.notifier.hide()' Enter
Type ':echo ""' Enter
Sleep 500ms
Show

# --- run the main under the cursor; the Spring Boot startup streams into the docked output ---
Sleep 1500ms
Type ' rr'                # <leader>rr = :LatheRun
# Spring's log lines run past the right edge: wrap Lathe's output split, off camera, without
# focusing it (a focused split stops following the output).
Wait+Screen@30s /Running org/
Hide
# Wrapped lines push the tail below the window, and the split's follow does not scroll for them:
# a timer keeps it on the last line.
Type ':lua local t = vim.uv.new_timer(); t:start(0, 200, vim.schedule_wrap(function() for _, w in ipairs(vim.api.nvim_list_wins()) do if vim.bo[vim.api.nvim_win_get_buf(w)].filetype == "lathe-test-output" then vim.wo[w].wrap = true; vim.api.nvim_win_call(w, function() vim.cmd("normal! Gzb") end) end end end))' Enter
Type ':echo ""' Enter
Show
Wait+Screen@60s /Started PetClinicApplication in/
Sleep 5000ms              # dwell on "Tomcat started on port 8080" + "Started PetClinicApplication in"

# --- stop it ---
Type ':LatheRunStop'
Sleep 300ms
Enter
Sleep 2500ms
