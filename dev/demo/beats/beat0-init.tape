# Beat 0 — Set up from the editor. Narration captions (burned in post-render), see beat0-init.ass:
#   (1) "spring-petclinic — no Lathe in its build"  (2) ":LatheInit — one build, from Neovim"
#   (3) "Lathe attached — go to definition"
# Part of the Lathe demo; see dev/demo/demo-script.md. Run from the repo root after ./dev/demo/prepare.sh.
#   vhs dev/demo/beats/beat0-init.tape
#
# Story: a fresh clone of a project that has never heard of Lathe. Open a file — no server. Run
# :LatheInit, accept the root it offers, and the one build it runs (Lathe brought in from outside,
# -Dmaven.ext.class.path, so the project's files are not changed) sets the project up and attaches.
# The clone is a local clone of prepare.sh's warmed one, so the dependencies are already in ~/.m2.
Output "docs/videos/beat0-init.mp4"
Require nvim

Set Shell "bash"
Set FontFamily "Monaco, Cascadia Mono NF"
Set FontSize 24
Set Width 1800
Set Height 1080
Set Padding 30
Set TypingSpeed 35ms

# --- hidden setup: isolated config + Lathe client from checkout; a fresh clone with no .lathe/.
# The `|| exit 1` guards mean a missing clone yields an empty clip, never a stray rm or build. ---
Hide
Type 'R="$PWD"' Enter
Type 'source "$R/dev/demo/env.sh"' Enter
# The setup build takes every core; niced (nvim, Maven and the server inherit it), it leaves VHS's
# headless browser more CPU. Timing still depends on the machine's load: every step waits on the
# screen, not a fixed time, and the published cut is recorded on a quiet machine.
Type 'renice -n 15 $$ >/dev/null' Enter
Type 'test -d "$PETCLINIC/.git" || exit 1' Enter
Type 'rm -rf "$LATHE_DEMO_WORK/fresh" && git clone -q "$PETCLINIC" "$LATHE_DEMO_WORK/fresh/spring-petclinic"' Enter
Type 'cd "$LATHE_DEMO_WORK/fresh/spring-petclinic" || exit 1' Enter
Type 'clear' Enter
Show

# --- in the petclinic folder, open a class: no language server yet, Lathe says to run :LatheInit ---
Type@10ms 'nvim -n src/main/java/org/springframework/samples/petclinic/owner/Owner.java'
Sleep 400ms
Enter
Wait+Screen@30s /isn't set up yet/
Sleep 2000ms              # read the toast: run :LatheInit, it doesn't change your project

# --- :LatheInit: confirm the root it offers, then the setup build runs and the server attaches ---
Type ':LatheInit maven'
Sleep 500ms
Enter
Wait+Screen@10s /for the Maven build in/
Sleep 1500ms              # the prompt: Maven, the root, Lathe's version
Enter
# Download + `mvnw clean test (capture only)` + attach: waits for the server's toast, not a fixed
# time. VHS captures fewer frames while the build loads the machine, so this stretch plays ~3x fast.
Wait+Screen@90s /workspace ready/
Type ':echo ""'           # clear the prompt's text from the command line
Enter
Sleep 1500ms              # dwell on "server set up" + "workspace ready"

# --- prove it: go to definition on `Person` (`public class Owner extends Person`) ---
Type '50G'
Type '_'
Type '$'
Type 'b'
Sleep 500ms
Type 'gd'
Wait+Screen@20s /class Person extends BaseEntity/
Sleep 2000ms              # dwell on Person.java
