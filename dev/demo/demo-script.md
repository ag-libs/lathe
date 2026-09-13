# Lathe Demo Script

The source-of-truth plan for the Lathe demo.
We agree this document first, then build the fixture, then record one beat at a time, then stitch
the beats into a single video.

## Goal and format

- **One story:** *capture your real Maven build once, then get a full reactor-aware IDE — edit,
  navigate, run, debug, and test — all from your build.*
- **Length:** ~60 s total, 75 s hard ceiling. Six beats, chaptered.
- **Production:** each beat is its own short clip (its own VHS tape), recorded independently, then
  concatenated with `ffmpeg` into the full video. Per-beat clips are reliable (own warm-up, no
  cross-beat line shifts) and cheap to re-render in isolation.
- **Narration:** one short caption per beat, burned into the clip (bottom of frame) as an **ASS
  subtitle** (`ffmpeg -vf subtitles=<beat>.ass`), shown for the whole beat. The caption doubles as a
  chapter marker at the seam.
- **Quality:** 1800×1080, FontSize 24 (settled — sharp for terminal text).
- **Output:** MP4 only — per-beat clips render to `docs/videos/<n>-<name>.mp4`, captioned in place, then
  concatenated into the stitched `docs/demo.mp4`. (No GIFs; captions are **outlined text, no box** —
  see the overlay recipe below — so they never cover a rectangle of content.)

## Fixture (crafted so every beat is natural)

> **Spec, not yet applied.** The source below is the target fixture to write *when we record* —
> it is not applied yet (another session owns fixture fixes). Treat this section as the checklist.

The demo runs on the public `multi-module` invoker fixture only (repo policy — never a private
workspace). We craft one class so each beat has an obvious, coherent target.

`app/src/main/java/com/example/app/Main.java`:

```java
package com.example.app;

import com.example.core.StringUtils;

public final class Main {
  public static void main(final String[] args) {
    final var user = UserBuilder.builder().name("Alice").age(30).build();  // beat 1 edits the User record
    final String name = StringUtils.upper(user.name());                    // cross-module call
    final int length = name.length();
    System.out.println(name + " (" + length + ")");                        // beat 4 breakpoint here
  }
}
```

`app/Main` is the home for **annotation processing (beat 1)** and **debug (beat 4)**: `user`/`name`/
`length` are the debug locals. Running/streaming moves to the `jpms` module (below), so `Main` no
longer needs the `Thread.sleep`.

`jpms/HelloMain.java` is the home for the **JPMS/logger beat (2)** and the **run beat (3)**. After
beat 2 adds the logger and `requires java.logging`, its `main` streams JUL output (INFO -> stderr):

```java
// jpms module (com.example.jpms) — the state beat 3 runs, produced by beat 2
private static final Logger logger = Logger.getLogger(HelloMain.class.getName());

public static void main(final String[] args) throws InterruptedException {
  logger.info("starting");
  Thread.sleep(1000);      // second INFO line appears after a beat -> visibly live
  logger.info("done");
}
```

`User` (already in the fixture) is the annotation-processed record beat 1 edits:

```java
@Builder
public record User(String name, int age) {}   // record-companion-builder generates UserBuilder
```

Beat 1 adds a component — **`Instant createdAt`**. Typing `Instant` shows type completion and
auto-imports `java.time.Instant`; on `:w` the processor regenerates `UserBuilder` with
`createdAt(Instant)`, so back in `Main` the builder completes `.createdAt(...)`. Two completion
moments in one beat (component type + regenerated setter).

`app/src/test/java/com/example/app/AppTest.java` — a tiny JUnit 5 test for beat 5:

```java
package com.example.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AppTest {
  @Test
  void upper_lowercaseInput_uppercases() {
    assertEquals("ALICE!", StringUtils.upper("alice"));   // fails on purpose for beat 5: ALICE! vs ALICE
  }
}
```

Already present and reused: `core` (`StringUtils`, `DbClient`, `DbClientBase`), `health`
(`DbClientHealthCheck`), the generated `UserBuilder`, and the `jpms` JPMS module (`module-info.java`
for `com.example.jpms`, which must NOT already `requires java.logging`) — beat 2's target.

## Shared preamble (identical hidden block in every beat tape)

```
Hide
  R="$PWD"
  export XDG_* -> dev/demo/.nvim/*        # isolated copy of the user's nvim config (prepare.sh)
  export LATHE_NVIM_DIR / LATHE_CACHE     # client from checkout, server from invoker cache
  cd "$R/lathe-maven-plugin/target/it/multi-module" || exit 1   # GUARD: never run mvn at repo root
  <beat-specific warm, if any>
Show
```

The `|| exit 1` guard is mandatory: a missing fixture must yield a harmless empty clip, never a
repo-root `mvn clean` that wipes `target/`.

## The beats

Beats 0/1/3/4/5 open `app/Main.java` (imports auto-folded, cursor at top, "workspace ready"). **Beat 2
opens the `jpms` module instead** (`jpms/HelloMain.java` + its `module-info.java`) — a deliberate
context switch that shows the reactor is *mixed* (classpath `app` + modular `jpms`, both working), the
exact scenario Lathe targets. Its chapter caption makes the switch read as intentional. Durations are
targets; captions overlay during the action (no extra time).

| # | Beat | Actions | Caption | ~s |
|---|------|---------|---------|----|
| 0 | Capture | `mvn clean test -Dlathe.capture.only=true` -> BUILD SUCCESS -> open `Main.java` -> workspace ready | `Capture your Maven build — no LSP setup` | 11 |
| 1 | Annotation processing, live | change `var`->`User`, `gd` into `User.java`, add `Instant createdAt` (type `Instant` -> completion + auto-import), `:w` (processor regenerates), back in `Main` `.createdAt(` completes on the builder | `Edit a record — the processor regenerates on save` | 13 |
| 2 | JPMS module graph | in a `jpms` source add a static `Logger` (java.util.logging) + a log line -> module-access error; open `module-info.java`, add `requires java.logging;` (module-name completion), `:w` -> resolves | `Modules just work — edit module-info, resolution follows` | 9 |
| 3 | Run (modular, `jpms`) | in `jpms/HelloMain` `\rr` -> docked split streams `INFO … starting`, pause, `INFO … done` (JUL to stderr) | `Run a modular main — live log output` | 8 |
| 4 | Debug | `17G`, `\db`, `\dd` -> `C-w b`, `S` (Scopes: `user`/`name`/`length`) -> REPL: evaluate `name.toLowerCase()` -> `alice` -> `\dc` | `Debug the replay — variables & live expression eval` | 11 |
| 5 | Test (fail -> fix -> green) | `:e AppTest.java` -> `\tf` (red + inline failure + navigable stack trace) -> delete `!` -> `\tf` (green) | `Fail, fix, green — replayed from the capture` | 12 |

**Total ≈ 64 s** (well within the ≤75 s ceiling).

### Per-beat notes / gotchas (learned the hard way)

- **Beat 0 mvnd warm:** hidden `mvn -q clean test … >/dev/null 2>&1` before the visible capture so
  the recorded build is fast and deterministic (mvnd cold-start otherwise overruns the sleep).
- **Build cache must be off for capture:** the box's `mvnd` carries `maven-build-cache-extension`; a
  cache hit skips compilation so Lathe captures nothing (README setup note). Pass
  `-Dmaven.build.cache.enabled=false` on the capture *and* the warm. (Likely a cause of earlier
  fixture flakiness.)
- **Beat 1 (annotation):** VERIFIED — a FULL compile on `:w` runs the processor and rewrites
  `.lathe/<module>/generated-sources/UserBuilder.java`; completion on `UserBuilder.builder().` then
  offers the new setter (probed: added `String email` -> builder gained `email(String)` and
  `build()` -> `new User(name, age, email)`). `gd` needs the index warm (~7-8 s after open). Navigate
  with line/`f` motions, not `/search`, to avoid `hlsearch`. **This beat mutates the fixture**
  (`User.java` + regenerated `.lathe`) — render it isolated and reset the fixture afterward.
- **Beat 2 (JPMS module graph):** see the dedicated section below. **Mutates the fixture** (a `jpms`
  source + `module-info.java`) — reset after. **Verify on a build first** (couldn't probe now).
- **Beat 3 (run, `jpms`):** runs `jpms/HelloMain` at beat 2's end state (logger + `requires`); the JUL
  INFO lines stream to the docked split. Modular run replayed from `.lathe` (supported per status.md).
  Do not reset the fixture between beats 2 and 3.
- **Beat 4 (debug):** the first debug attach is slow (~18 s). Warm it in a mid-tape `Hide/Show`
  block after the file opens, so the recorded attach is fast. Focus the dap panel with `C-w b` then
  `S` (a cursor move, not `Esc`, dismisses floats). Breakpoint on the final `println`. Then evaluate
  an expression at the paused frame — via `\dr` (dap REPL) or the dap-view REPL section — e.g.
  `name.toLowerCase()` -> `alice` (live method invocation; supported per status.md — confirm the
  exact keys and that eval returns on the first render).
- **Hover floats:** dismiss with a cursor move (`0`), not `Esc` (Esc won't close an unfocused float).
- **`hlsearch`:** avoid `/`-search navigation or `:set nohlsearch` in the hidden preamble.

## Beat 2 detail — JPMS module graph (variant A)

JPMS is Lathe's headline differentiator (README: the module graph, exported-package visibility, and
module-aware completion follow the build). Uses the **existing** `jpms` module (`jpms/HelloMain.java` +
`module-info.java`) — **no fixture restructure**; `app` stays classpath, preserving the invoker
preconditions and the deliberate mixed reactor (a JPMS module surrounded by classpath ones, which the
demo is happy to show off). Target module `java.logging` (`java.util.logging.Logger`) — one the `jpms`
module does not require by default, and a genuinely *usable* type (a static logger + a log line is
natural code, unlike a declared-but-unused `HttpClient`). `java.net.http`/`HttpClient` is the alternative.

Flow (the fixture must still compile, so the usage is added live) — packs two features:

1. **Declaration-name completion.** In `jpms/HelloMain.java` type `private static final Logger ` then
   `<C-Space>` -> Lathe suggests the variable name **`logger`**; accept. Add
   `= Logger.getLogger(HelloMain.class.getName());` and `logger.info("starting")` / `logger.info("done")`
   (with a `Thread.sleep(1000)` between, for a streamed run) -> **module-access diagnostic** ("package
   `java.util.logging` is not visible").
2. **Module-aware completion + the unlock.** Open `module-info.java`, type `requires java.` -> let
   completion offer `logging` -> add `requires java.logging;`.
3. `:w` -> the diagnostic clears and `Logger` resolves. **This end state (logger + `requires`) is what
   beat 3 runs** — no reset between beats 2 and 3.

Notes:
- **`static` / declaration-name caveat:** the logger must be `static` so `main` (beat 3) can use it.
  SCREAMING_SNAKE (`LOG`) is a documented non-goal, so completion won't suggest `LOG`; it may still
  offer the plain `logger` for a `static final` field — verify. If it doesn't fire on `static final`,
  type the name and let the **module-aware `requires` completion** carry the beat's completion moment.
- Module-aware completion likely *hides* `Logger` until `java.logging` is required — which is itself
  the point (unavailable until required, then it appears once the graph allows it).
- `java.util.logging` prints INFO+ to stderr by default, so beat 3's run shows the log lines as real
  output — the reason run lives in `jpms` (running the class we just wired up).

**Verify on a build before recording** (couldn't probe now; another session owns builds): the
module-access diagnostic, module-name completion in `requires`, and the requires->resolution
round-trip. Fallbacks if any piece doesn't hold: variant **B** (just show `requires` completion) or
**C** (`:LatheNew module-info` scaffold).

## Fixture reset between beats

Beats mutate the fixture: beat 1 edits `app` (`User.java`); beats 2-3 edit the `jpms` module
(`HelloMain.java` + `module-info.java`). **Beats 2 and 3 share `jpms` state** — beat 2 adds the logger
and `requires`, beat 3 runs that end state, so do NOT reset between them. Reset to a clean fixture
before beat 1, before beat 2, and before beats 4/5 (re-copy the touched files from `src/it`, or re-run
the invoker). The stitched video is unaffected (each clip is an independent session).

## Narration (captions + title / outro cards)

Burned into each clip as an **ASS subtitle** (`ffmpeg -i clip.mp4 -vf subtitles=<beat>.ass`):
bottom-centred **outlined text, no box** (so it never covers a rectangle of content), with a soft
fade in/out, shown for the whole beat. Two standalone cards bookend the video.

**Why ASS subtitles (not `drawtext`):** `drawtext`'s `box` ignores its alpha and renders opaque, so a
translucent background was impossible. ASS libass renders a crisp outline, supports fades (`\fad`), and
— if we ever want a background — a *genuinely* semi-transparent box (`BorderStyle=3` + `BackColour`
alpha). We chose outline-only (no box); the mechanism is documented below.

**Title card** (~2.5 s, black):

- **Lathe**
- *A Java language server that works from your Maven build*

**Per-beat overlay** — a short chapter title (bold) above the caption line:

| # | Chapter title | Caption |
|---|---------------|---------|
| 0 | Capture | Capture your Maven build — no LSP setup |
| 1 | Live annotation processing | Edit a record; the processor regenerates on save |
| 2 | Go to definition | Into the JDK & library sources |
| 3 | Run | A modular main — live log output |
| 4 | Debug | Breakpoints, variables, live expression eval |
| 5 | Test: fail → fix → green | Replayed from the capture |

**Outro card** (~2.5 s, black):

- **One capture. A full IDE — from your build.**
- *github.com/ag-libs/lathe · Neovim*

Overlay recipe (beat 0, settled) — an ASS style, `PlayResX/Y = 1800/1080`:

```
Style: Cap,DejaVu Sans,40,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,3,2,2,40,40,110,1
Dialogue: 0,0:00:00.50,0:00:06.00,Cap,,0,0,0,,{\fad(400,600)}Capture your Maven build — no LSP setup
```

`BorderStyle=1` (outline + shadow, **no box**), `Outline=3`, `Shadow=2`, `Alignment=2` (bottom-centre),
`MarginV=110` (clears the statusline), white text, black outline, DejaVu Sans Bold (has the em-dash).
The caption shows for a **bounded window** — fade in, hold a few seconds, fade out — not the whole
clip: set `Start`/`End` to the beat's relevant moment (beat 0: during the capture, gone by the editor
reveal) and `{\fad(in,out)}` for the fades. Each beat's clip carries its own caption.

## Assembly & regeneration

Sources are committed (`dev/demo/beats/<beat>.tape` + `<beat>.ass`); the clips are regenerated from
them against the current Lathe build, so they can be retaken any time. **`dev/demo/record.sh`** does it
end to end:

- for each tape: render the raw clip (VHS) -> burn the caption in place (`ffmpeg -vf
  subtitles=<beat>.ass`) -> one titled `docs/videos/<beat>.mp4` (committed);
- then concatenate the captioned clips (`ffmpeg concat`, stream-copy) -> `docs/demo.mp4`.

Seams read as chapter transitions (beats 0/1/4/5 open the same `Main.java`; beats 2/3 the `jpms`
module), and each caption fades in/out within its own beat.

**Retake after a feature change:**

```
./dev/demo/prepare.sh    # rebuild+install Lathe, rebuild the invoker fixture, copy the nvim config
./dev/demo/record.sh     # render + caption + stitch -> docs/videos/*.mp4 + docs/demo.mp4
git add docs/videos docs/demo.mp4 && git commit
```

Each tape's hidden warm re-runs `mvn clean test -Dlathe.capture.only=true` (build cache off), so
`.lathe` is captured fresh with the current Lathe on every render. Changing one beat = re-render just
that clip and re-run the concat.

## README alignment (checked)

- Every beat maps to an advertised feature: capture (`Setup`), completion + auto-import + declaration
  names (`Completion`), JPMS module graph + module-aware completion (README lead), run a `main` with
  live output (`Run, test & debug`), debug with variable inspection + **REPL expression evaluation**
  (same), tests with live output + **a diagnostic on the failing assertion** (same).
- **Update the README `Demo` section** once recorded: it currently holds a placeholder ("_coming
  soon_") and a ~40 s run+debug sketch; our clip is ~64 s and broader — replace the TODO with the
  final MP4 link and refresh the blurb.
- Claims to confirm on a build (beyond the README's guarantees): beat 5's *navigable stack trace* (the
  README only promises the inline diagnostic) and beat 2's module-name completion in `requires`.

## Workflow

1. Agree this script.
2. Update the fixture (`Main.java` with the sleep, `AppTest` with the failing assertion), rebuild the
   invoker fixture once.
3. Author + record beats one at a time, source-first: agree the tape, render once, you eyeball the
   clip; reset the fixture between beats.
4. Caption each clip, concatenate into the full video.

## Settled decisions

1. **Live run output:** `main` uses `Thread.sleep(1000)` between two prints so beat 3 visibly streams.
2. **Test fails then is fixed:** beat 5 is red -> fix -> green (the fast inner loop); the red run
   surfaces an inline failure diagnostic plus a navigable stack trace (jump from the trace to source).
3. **Annotation beat replaces the old completion and generated-code beats** — it navigates into
   generated code *and* shows it regenerate on save (VERIFIED). It also carries an in-project `gd`.
4. **Beat 2 is the JPMS module-graph beat (variant A):** reference `HttpClient` -> add
   `requires java.net.http` -> resolves. JPMS is Lathe's headline differentiator (README), so it earns
   the slot over a generic navigation beat. Pending build-time verification.
5. **Dropped:** symbol search (module distinction too subtle on screen), JDK go-to-def (generic —
   superseded by the JPMS beat), standalone cross-module go-to-definition (covered by beat 1's
   in-project `gd`), and rename (cross-module rename risk — verify and re-add later). Cross-module
   reach stays implicit in the capture/reactor premise and the run/debug/test replay.
6. **`app` stays classpath; beat 2 uses the existing `jpms` module** (no fixture restructure). Making
   `app` modular was rejected: it would break the invoker preconditions and destroy the deliberate
   mixed reactor. The context switch to `jpms` for beat 2 is framed as a strength — a mixed
   classpath+JPMS reactor, both working.
7. **Module split:** `app`/`Main` hosts annotation processing (beat 1), debug (beat 4), and test
   (beat 5); the `jpms` module hosts the logger/JPMS beat (2) and the run (beat 3, which runs the
   logger for live output). Beats 2->3 share `jpms` state (no reset between them).
