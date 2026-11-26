# Lathe Demo Script

The plan behind the Lathe demo: what each beat shows, why, and how its tape is written.
How to generate it is in [README.md](README.md).

## Goal and format

- **One story:** *open a project that has never heard of Lathe, run `:LatheInit`, and get a full IDE — edit, run, debug, test — with the project's build files unchanged.*
- **Length:** about 60 s, in five beats between a title card and an end card.
- **Production:** each beat is its own VHS tape, rendered on its own, then concatenated with `ffmpeg`.
  A beat can be re-rendered in isolation, and no beat depends on the one before it.
- **Captions:** each beat's `.ass` subtitle file, burned in by `record.sh`, in a 110 px caption bar below the terminal, so a caption never covers the code or the output it describes.
  Outlined text with a soft fade (`\fad`); libass renders it crisply where `drawtext` cannot.
- **Size:** 1800×1080 terminal at FontSize 24, plus the caption bar.
- **Output:** GIF-first.
  The per-beat clips (`docs/videos/*.mp4`) and the stitched `docs/demo.mp4` are gitignored intermediates; only the content-hashed `docs/demo-<hash>.gif` is committed.

The cut, by the original tour's beat numbers (beats 2 and 3, annotation processing and the JPMS module graph, were dropped):

```
title-card → 0 :LatheInit → 1 everyday editing → 4 run → 5 debug → 6 test → end-card
```

## The project

[spring-petclinic](https://github.com/spring-projects/spring-petclinic) at the commit `env.sh` pins: public (repo policy: never a private workspace), widely known, a real Spring Boot application, and no Lathe anywhere in its build.
That last point is the demo's subject, so beat 0 shows `:LatheInit` setting it up from nothing.
petclinic has both a Maven and a Gradle build at its root, where `:LatheInit` would pick Gradle; the demo passes `maven`, and beat 0's caption says why.

`prepare.sh` sets up a warmed clone for beats 1–6; each beat's hidden setup resets its sources (`git checkout -- src`).
No petclinic code is copied into this repo: beat 6 makes its failing assertion with a `sed` in its hidden setup.

## The beats

### 0 — `:LatheInit`
Open `owner/Owner.java` in a fresh clone: no server, and a toast says the project is not set up and to run `:LatheInit`.
Run `:LatheInit maven`, accept the root the prompt offers, and one toast follows the download, the build (`mvnw clean test`, capture only) and the server set up.
`gd` on `Person` (`Owner extends Person`) proves the server is attached.
Captions: *spring-petclinic — no Lathe in its build* · *:LatheInit maven — petclinic has both builds* · *One build, from Neovim — the project's files are not changed* · *Lathe attached — go to definition*.
petclinic's build checks every file for `http://` URLs (nohttp), so the setup ends with Lathe's warning to exclude `.lathe/` from that check: real advice for this project, kept in the cut.

### 1 — Everyday editing
In `Owner.getPet`, retype `pet.getName()`: completion offers `getName()`, which `Pet` inherits from `NamedEntity`, through the classpath the build recorded.
Make the local an `int`: the type mismatch is flagged as you type; put `String` back and save clean.
Captions: *Code completion — from the build's classpath* · *Type mismatch — flagged as you type* · *Saved — no diagnostics*.

### 4 — Run
`<leader>rr` on `PetClinicApplication.main` runs it from `.lathe/` bytecode, with no Maven: the Spring banner and the start-up log stream into Lathe's output split until *Started PetClinicApplication*, and `:LatheRunStop` stops it.
The output split is wrapped (off camera) so the long log lines can be read.
Captions: *Run the Spring Boot app — no Maven, from the captured classpath* · *Live output, until Started PetClinicApplication* · *:LatheRunStop — stopped*.

### 5 — Debug
A breakpoint on the assertion in `OwnerTests.addPetAddsPersistedPet`, then `<leader>dd` (`:LatheDebug`).
At the paused frame, dap-view watches `owner.getPets()` (`size=1`) and evaluates `pet.getName().toUpperCase()` (`"BUDDY"`) in the debuggee; continuing runs the test to green.
Captions: *Set a breakpoint and debug the test* · *Inspect the paused frame* · *Evaluate a call: pet.getName().toUpperCase() → BUDDY* · *Continue — the test passes*.

### 6 — Test
`OwnerTests` expects two pets where there is one (set off camera).
`<leader>tf` runs the file red, `<leader>to` docks the assertion and its stack trace, the fix is one character, and saving recompiles `.lathe/`; `<leader>tf` again runs green.
Captions: *Run the tests — one fails* · *The assertion, with its stack trace* · *Fix it and save* · *Run again — green*.

### Cards
Title: **Lathe** · *A zero-config Java language server* · *Open any Maven or Gradle project — no build changes, no project import*.
End: **Lathe** · *github.com/ag-libs/lathe* · *Neovim · VS Code · Emacs · Zed*.
Wording is deliberate: Lathe does not run Maven to serve the editor (it records the build's settings once), so no card says it "runs from your build".

## Writing a tape

- **Wait on the screen, not the clock.** Each step waits for what it needs (`Wait+Screen /…/`: the completion item, the diagnostic, `Started PetClinicApplication`, `"BUDDY"`, `2 passed`), then a short `Sleep` gives the viewer time to read.
  A fixed sleep breaks as soon as the machine is busy, and other work on the machine is the normal case.
- **Match text that stays on one line.** A toast or a diagnostic that wraps or runs off the edge splits its words: `1 passed, 1⏎failed` matches `/1 passed, 1/`, not `/1 failed/`.
- **Never `Hide` while something streams.** VHS records no frames while hidden but the process keeps running, so the output appears already written when the clip shows again.
  Set up anything a beat needs (the output split's wrap, a `:Watch` command) before `Show`.
- **Warm the slow first times off camera:** the workspace (wait for *workspace ready*), the first debug attach (beat 5), the save that puts `.lathe/` in the failing state (beat 6).
  Dismiss leftover toasts before `Show` with `Snacks.notifier.hide()`.
- **`renice` the shell in the hidden setup.** nvim, Maven and the server inherit it, which leaves VHS's headless browser more CPU; a busy machine still drops frames, which plays the heavy stretches fast.
- **Navigate by line numbers and motions, never `/search`:** `/` leaves highlights that bleed across the concatenated clips.
  After `NG`, re-anchor with `_`: the cursor keeps its column.
- **Press Escape twice after any insert that may show completion:** blink.cmp's first `<Esc>` only closes the menu.

## Publishing

Record on a quiet machine, with the Lathe release that ships `:LatheInit`, so the prompt shows a released version.
Then re-time each `.ass` to its clip (the README shows how) and run `record.sh`, which writes `docs/demo-<hash>.gif` and updates the README's and the lathe.nvim mirror's links to it.
