# Gradle support — pending public-doc changes

Internal notes. `README.md` and the public guides it links to are being reworked on main; these changes
wait until that rework is merged, then go into the files named here (and this file is deleted).

## Test JVMs older than JDK 17 (from #57 and its Gradle follow-up)

- **`docs/guide/gradle.md`, "Known limits":** add one bullet —
  "Tests your build runs on a JDK older than 17 (a test toolchain, or `java.toolchain` below 17) still
  run, but are not captured: the build prints `[lathe] test launch not captured: …`, and those tests
  can't be run or debugged from the editor."
- **`README.md`, Requirements:** #57 added the sentence for Maven ("through `<jvm>` or a toolchain");
  name Gradle's case in it too: "(Surefire's `<jvm>`, a Maven toolchain, or a Gradle test toolchain)".

## The server's JDK on a Gradle build that compiles below 17

Once the fallback lands (if every module compiles on a JDK older than 17, the server and the JDK sources
use the JDK Gradle runs on):

- **`docs/guide/gradle.md`, "Requirements":** "Projects may compile and test on any JDK their toolchain
  names; Lathe's server needs JDK 17+ and uses the JDK Gradle runs on when every module compiles on an
  older one (JDK navigation then shows that JDK's sources)."

## At the 0.2.0 release

- Remove the "Coming in Lathe 0.2.0" note at the top of `docs/guide/gradle.md`, and "arrives in 0.2.0" /
  "(from 0.2.0)" in `README.md`.
- Move `docs/planned/lathe-gradle-support.md` to `docs/done/` and update its links
  (`docs/design-index.md`, `docs/status.md`, the guide).
