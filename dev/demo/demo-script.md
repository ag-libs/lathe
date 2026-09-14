# Lathe Demo Script

The source-of-truth plan for the Lathe demo.
We agree this document first, then build the fixture, then record one beat at a time, then stitch
the beats into a single video.

## Goal and format

- **One story:** *capture your real Maven build once, then get a full reactor-aware IDE — edit,
  navigate, run, debug, and test — all from your build.*
- **Length:** ~72 s total, 75 s hard ceiling. Seven beats, chaptered.
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
    final User user = UserBuilder.builder().name("Alice").age(30).build();  // explicit User: beat 2 `gd`s straight in
    System.out.println(StringUtils.upper(user.name()));                     // beat 1 edits above here; beat 5 breakpoint
  }
}
```

`app/Main` is the home for **everyday editing (beat 1)**, **annotation processing (beat 2)**, and
**debug (beat 5)**. At the beat-5 breakpoint `args` and the `user` record are in scope (expand `user`
-> `name`/`age`), so `Main` needs no extra flat locals. Running/streaming lives in the `jpms` module
(below), so `Main` needs no `Thread.sleep`.

**Beat 1 works entirely in `Main`:** it adds one statement with completion, mis-types its declared
type to trigger a live diagnostic, fixes it, and saves — ending on a valid `Main`. It mutates `Main`,
so reset after (like beat 2).

`jpms/HelloMain.java` is the home for the **JPMS/logger beat (3)** and the **run beat (4)**. After
beat 3 adds the logger and `requires java.logging`, its `main` streams JUL output (INFO -> stderr):

```java
// jpms module (com.example.jpms) — the state beat 4 runs, produced by beat 3
private static final Logger logger = Logger.getLogger(HelloMain.class.getName());

public static void main(final String[] args) throws InterruptedException {
  logger.info("starting");
  Thread.sleep(1000);      // second INFO line appears after a beat -> visibly live
  logger.info("done");
}
```

`User` (already in the fixture) is the annotation-processed record beat 2 edits:

```java
/**
 * Domain record. The {@link Builder} annotation is read at compile time by the record-companion
 * annotation processor, which generates a fluent {@code UserBuilder} companion — one setter per
 * component, regenerated on every build.
 */
@Builder
public record User(String name, int age) {}   // beat 2 `gd`s here; the Javadoc explains the processor
```

Beat 2 adds a component — **`Instant createdAt`**. Typing `Instant` shows type completion and
auto-imports `java.time.Instant`; on `:w` the processor regenerates `UserBuilder` with
`createdAt(Instant)`, so back in `Main` the builder completes `.createdAt(...)`. Two completion
moments in one beat (component type + regenerated setter).

`app/src/test/java/com/example/app/AppTest.java` — a tiny JUnit 5 test for beat 6:

```java
package com.example.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AppTest {
  @Test
  void upper_lowercaseInput_uppercases() {
    assertEquals("ALICE!", StringUtils.upper("alice"));   // fails on purpose for beat 6: ALICE! vs ALICE
  }
}
```

Already present and reused: `core` (`StringUtils`, `DbClient`, `DbClientBase`), `health`
(`DbClientHealthCheck`), the generated `UserBuilder`, and the `jpms` JPMS module (`module-info.java`
for `com.example.jpms`, which must NOT already `requires java.logging`) — beat 3's target.

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

Beats 0/1/2/5 open `app/Main.java` (imports auto-folded, cursor at top, "workspace ready"); beat 6
opens `AppTest.java` in the same `app` module. **Beats 3 and 4 open the `jpms` module instead**
(`jpms/HelloMain.java` + its `module-info.java`) — a deliberate context switch that shows the reactor
is *mixed* (classpath `app` + modular `jpms`, both working), the exact scenario Lathe targets. Their
chapter captions make the switch read as intentional. Durations are targets; captions overlay during
the action (no extra time).

| # | Beat | Actions | Caption | ~s |
|---|------|---------|---------|----|
| 0 | Capture | `mvn clean test -Dlathe.capture.only=true` -> BUILD SUCCESS -> open `Main.java` -> workspace ready | (1) `Capture your Maven build` (2) `Open a file — Lathe attaches, no setup` | 11 |
| 1 | Everyday editing | above the `println` add `final var greeting = StringUtils.` (completion: cross-module `upper(String)`) `upper(user.` (completion: record `name()`); change `var`->`int` -> live "incompatible types"; fix `int`->`var` -> clears; use `greeting` in the `println`, `:w` -> clean | `Complete, catch the mistake, fix — as you type` | 8 |
| 2 | Annotation processing, live | `gd` on `User` into `User.java` (baseline already declares `final User user`), add `Instant createdAt` (type `Instant` -> completion + auto-import), `:w` (processor regenerates), back in `Main` `.createdAt(` completes on the builder | `Edit a record — the processor regenerates on save` | 13 |
| 3 | JPMS module graph | in a `jpms` source add a static `Logger` (java.util.logging) + a log line -> module-access error; open `module-info.java`, add `requires java.logging;` (module-name completion), `:w` -> resolves | `Edit module-info — resolution follows` | 9 |
| 4 | Run (modular, `jpms`) | in `jpms/HelloMain` `\rr` -> docked split streams `INFO … starting`, pause, `INFO … done` (JUL to stderr) | `A modular main — live log output` | 8 |
| 5 | Debug | breakpoint on the `println`, `\db`, `\dd` -> `C-w b`, `S` (Scopes: `args`/`user`, expand `user` -> `name`/`age`) -> REPL: evaluate `user.name().toLowerCase()` -> `alice` -> `\dc` | `Breakpoints, variables, live expression eval` | 11 |
| 6 | Test (fail -> fix -> green) | `:e AppTest.java` -> `\tf` (red + inline failure + navigable stack trace) -> delete `!` -> `\tf` (green) | `Fail, fix, green — replayed from the capture` | 12 |

**Total ≈ 72 s** (within the ≤75 s ceiling — keep the new beat tight).

### Recording conventions (apply to every beat)

- **Navigate by line numbers and motions, never `/search`.** Position with `NN`+`gg`/`G`, `f`/`t`, and
  `:e <file>` + a line jump when moving between files; reserve `gd`/`K`/completion for the actual
  feature moments. `/`-search leaves `hlsearch` highlights that bleed across the concatenated seams and
  make cursor positions non-deterministic — **the beats are stitched into one movie**, so every clip
  must render identically run to run. (`:set nohlsearch` in the hidden preamble is a safety net, not a
  substitute.)
- **Hover floats:** dismiss with a cursor move (`0`), not `Esc` (Esc won't close an unfocused float).

### Per-beat notes / gotchas (learned the hard way)

- **Beat 0 mvnd warm:** hidden `mvn -q clean test … >/dev/null 2>&1` before the visible capture so
  the recorded build is fast and deterministic (mvnd cold-start otherwise overruns the sleep).
- **Build cache must be off for capture:** the box's `mvnd` carries `maven-build-cache-extension`; a
  cache hit skips compilation so Lathe captures nothing (README setup note). Pass
  `-Dmaven.build.cache.enabled=false` on the capture *and* the warm. (Likely a cause of earlier
  fixture flakiness.)
- **Beat 1 (everyday editing):** the "normal Java IDE" grounding before the differentiators. Two
  completion moments (cross-module `StringUtils.upper(String)` + record accessor `name()`), then a
  deliberate type mismatch: change the inferred `var` to `int` so the `String` return is flagged
  ("incompatible types: String cannot be converted to int") — a live, semantic diagnostic. Fix
  `int`->`var` to clear it, then reference `greeting` in the `println` so Lathe's own
  unused-declaration diagnostic doesn't fire, and `:w` for a clean compile. **The `var`->`int` flip is
  a mild contrivance** (accepted); a less-hacky variant is to complete the *wrong* JDK method
  (`name.toUpperCase()` into an `int`) and fix by completing `length()`. **Mutates `Main`** — reset
  after, like beat 2.
- **Beat 2 (annotation):** VERIFIED — a FULL compile on `:w` runs the processor and rewrites
  `.lathe/<module>/generated-sources/UserBuilder.java`; completion on `UserBuilder.builder().` then
  offers the new setter (probed: added `String email` -> builder gained `email(String)` and
  `build()` -> `new User(name, age, email)`). `gd` needs the index warm (~7-8 s after open).
  **This beat mutates the fixture** (`User.java` + regenerated `.lathe`) — render it isolated and reset
  the fixture afterward.
- **Beat 3 (JPMS module graph):** see the dedicated section below. **Mutates the fixture** (a `jpms`
  source + `module-info.java`) — reset after. **Verify on a build first** (couldn't probe now).
- **Beat 4 (run, `jpms`):** runs `jpms/HelloMain` at beat 3's end state (logger + `requires`); the JUL
  INFO lines stream to the docked split. Modular run replayed from `.lathe` (supported per status.md).
  Do not reset the fixture between beats 3 and 4.
- **Beat 5 (debug):** the first debug attach is slow (~18 s). Warm it in a mid-tape `Hide/Show`
  block after the file opens, so the recorded attach is fast. Focus the dap panel with `C-w b` then
  `S` (a cursor move, not `Esc`, dismisses floats). Breakpoint on the `println`. Then evaluate
  an expression at the paused frame — via `\dr` (dap REPL) or the dap-view REPL section — e.g.
  `user.name().toLowerCase()` -> `alice` (live method invocation; supported per status.md — confirm the
  exact keys and that eval returns on the first render).

## Beat 3 detail — JPMS module graph (variant A)

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
   beat 4 runs** — no reset between beats 3 and 4.

Notes:
- **`static` / declaration-name caveat:** the logger must be `static` so `main` (beat 4) can use it.
  SCREAMING_SNAKE (`LOG`) is a documented non-goal, so completion won't suggest `LOG`; it may still
  offer the plain `logger` for a `static final` field — verify. If it doesn't fire on `static final`,
  type the name and let the **module-aware `requires` completion** carry the beat's completion moment.
- Module-aware completion likely *hides* `Logger` until `java.logging` is required — which is itself
  the point (unavailable until required, then it appears once the graph allows it).
- `java.util.logging` prints INFO+ to stderr by default, so beat 4's run shows the log lines as real
  output — the reason run lives in `jpms` (running the class we just wired up).

**Verify on a build before recording** (couldn't probe now; another session owns builds): the
module-access diagnostic, module-name completion in `requires`, and the requires->resolution
round-trip. Fallbacks if any piece doesn't hold: variant **B** (just show `requires` completion) or
**C** (`:LatheNew module-info` scaffold).

## Fixture reset between beats

Beats mutate the fixture: beat 1 edits `app` (`Main.java`); beat 2 edits `app` (`User.java` + `Main.java`);
beats 3-4 edit the `jpms` module (`HelloMain.java` + `module-info.java`). **Beats 3 and 4 share `jpms`
state** — beat 3 adds the logger and `requires`, beat 4 runs that end state, so do NOT reset between
them. Reset to a clean fixture before beats 1, 2, and 5/6 (re-copy the touched files from `src/it`, or
re-run the invoker). The stitched video is unaffected (each clip is an independent session).

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
| 0 | Capture | (1) Capture your Maven build (2) Open a file — Lathe attaches, no setup |
| 1 | Everyday editing | Complete, catch the mistake, fix — as you type |
| 2 | Live annotation processing | Edit a record — the processor regenerates on save |
| 3 | Modules just work | Edit module-info — resolution follows |
| 4 | Run | A modular main — live log output |
| 5 | Debug | Breakpoints, variables, live expression eval |
| 6 | Test: fail → fix → green | Replayed from the capture |

**Outro card** (~2.5 s, black):

- **One capture. A full IDE — from your build.**
- *github.com/ag-libs/lathe · Neovim*

Overlay recipe (beat 0, settled) — an ASS style, `PlayResX/Y = 1800/1080`. A beat may carry **more than
one** caption, each on its own bounded window (beat 0 has two: build, then attach):

```
Style: Cap,DejaVu Sans,40,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,3,2,2,40,40,110,1
Dialogue: 0,0:00:00.50,0:00:05.00,Cap,,0,0,0,,{\fad(400,500)}Capture your Maven build
Dialogue: 0,0:00:05.40,0:00:10.40,Cap,,0,0,0,,{\fad(400,500)}Open a file — Lathe attaches, no setup
```

`BorderStyle=1` (outline + shadow, **no box**), `Outline=3`, `Shadow=2`, `Alignment=2` (bottom-centre),
`MarginV=110` (clears the statusline), white text, black outline, DejaVu Sans Bold (has the em-dash).
Each caption shows for a **bounded window** — fade in, hold a few seconds, fade out — not the whole
clip: set `Start`/`End` to the relevant moment (beat 0: caption 1 during the capture, caption 2 from the
`nvim` command through workspace-ready) and `{\fad(in,out)}` for the fades. Each beat's clip carries its
own caption track.

## Assembly & regeneration

Sources are committed (`dev/demo/beats/<beat>.tape` + `<beat>.ass`); the clips are regenerated from
them against the current Lathe build, so they can be retaken any time. **`dev/demo/record.sh`** does it
end to end:

- for each tape: render the raw clip (VHS) -> burn the caption in place (`ffmpeg -vf
  subtitles=<beat>.ass`) -> one titled `docs/videos/<beat>.mp4` (committed);
- then concatenate the captioned clips (`ffmpeg concat`, stream-copy) -> `docs/demo.mp4`.

Seams read as chapter transitions (beats 0/1/2/5 open the same `Main.java`, beat 6 `AppTest.java` in the
same module, beats 3/4 the `jpms` module), and each caption fades in/out within its own beat.

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

- Every beat maps to an advertised feature: capture (`Setup`), completion + **live diagnostics**
  (`Completion` / `Diagnostics`), annotation processing + auto-import (`Completion`), JPMS module graph +
  module-aware completion (README lead), run a `main` with live output (`Run, test & debug`), debug with
  variable inspection + **REPL expression evaluation** (same), tests with live output + **a diagnostic on
  the failing assertion** (same).
- **Update the README `Demo` section** once recorded: it currently holds a placeholder ("_coming
  soon_") and a ~40 s run+debug sketch; our clip is ~72 s and broader — replace the TODO with the
  final MP4 link and refresh the blurb.
- Claims to confirm on a build (beyond the README's guarantees): beat 6's *navigable stack trace* (the
  README only promises the inline diagnostic) and beat 3's module-name completion in `requires`.

## Workflow

1. Agree this script.
2. Update the fixture (`Main.java` baseline, `AppTest` with the failing assertion, `jpms/HelloMain` with
   the `Thread.sleep` for the streamed run), rebuild the invoker fixture once.
3. Author + record beats one at a time, source-first: agree the tape, render once, you eyeball the
   clip; reset the fixture between beats.
4. Caption each clip, concatenate into the full video.

## Settled decisions

1. **Live run output:** `jpms/HelloMain` uses `Thread.sleep(1000)` between two log lines so beat 4
   visibly streams.
2. **Test fails then is fixed:** beat 6 is red -> fix -> green (the fast inner loop); the red run
   surfaces an inline failure diagnostic plus a navigable stack trace (jump from the trace to source).
3. **Annotation beat replaces the old generated-code beat** — it navigates into generated code *and*
   shows it regenerate on save (VERIFIED). It also carries an in-project `gd`.
4. **Beat 3 is the JPMS module-graph beat (variant A):** add a `java.util.logging` `Logger` -> add
   `requires java.logging` -> resolves. JPMS is Lathe's headline differentiator (README), so it earns
   the slot over a generic navigation beat. Pending build-time verification.
5. **Dropped:** symbol search (module distinction too subtle on screen), JDK go-to-def (generic —
   superseded by the JPMS beat), standalone cross-module go-to-definition (covered by beat 2's
   in-project `gd`), and rename (cross-module rename risk — verify and re-add later). Cross-module
   reach stays implicit in the capture/reactor premise and the run/debug/test replay.
6. **`app` stays classpath; beat 3 uses the existing `jpms` module** (no fixture restructure). Making
   `app` modular was rejected: it would break the invoker preconditions and destroy the deliberate
   mixed reactor. The context switch to `jpms` for beat 3 is framed as a strength — a mixed
   classpath+JPMS reactor, both working.
7. **Module split:** `app`/`Main` hosts everyday editing (beat 1), annotation processing (beat 2), and
   debug (beat 5), plus the test (beat 6, in `AppTest`); the `jpms` module hosts the logger/JPMS beat (3)
   and the run (beat 4, which runs the logger for live output). Beats 3->4 share `jpms` state (no reset
   between them).
8. **Everyday-editing beat added between capture and annotation** (beat 1): completion + a live
   diagnostic — the "normal Java IDE" grounding before the differentiators, and a lead-in to the
   save-triggered checks in beat 2. The `var`->`int` type flip is an accepted minor contrivance;
   completing the *wrong* JDK method (fixed by completing the right one) is a less-hacky fallback.
