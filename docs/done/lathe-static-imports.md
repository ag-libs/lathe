# Lathe — Static Imports

Status: implemented.
Static imports learned from the ones the project already declares, each source scope from its own files:
completion offers them, the unresolved-call quick fix adds them, and "add missing imports" adds them.
Builds on the completion engine and the reference candidate index in `lathe-design.md`.

## Motivation

Test code is written in static calls: `assertThat(…)`, `mock(…)`, `when(…)`, `verify(…)`, `any()`,
`given()`, `stubFor(…)`.
Production code that uses static imports leans on a few too: `toList()`, `requireNonNull(…)`, a
project's own constants.
Today none of them can be completed until the file already has the `import static`, and an unresolved
`assertThat(x)` offers no import fix: completion and the import quick fix only know types.
Writing a new test means typing every static import by hand.

## Scope

- **Each scope learns from its own files.**
  A test file is offered the static imports that test sources declare; a main file is offered the ones
  main sources declare.
  Teams differ on static imports in production code, and the project's own main sources say which way
  it goes: a codebase whose main code has none is never offered one there.
- **Three surfaces, one lookup:**
  1. **Completion.** In a test, `assertTh` offers `assertThat` from the class the project imports it
     from; accepting it inserts the call and the `import static` line.
  2. **Quick fix.** An unresolved call `assertThat(x)` offers "Import static
     org.assertj.core.api.Assertions.assertThat", one action per class that declares the name.
  3. **Add missing imports** (`lathe.missingImports`, `:LatheMissingImports` in Neovim, the MCP
     `fix_imports` tool). Unresolved calls are fixed alongside unresolved types: one candidate is
     added, several are offered for a choice, as types are today.

Non-goals:
- Suggestions from libraries or classes a scope has never statically imported (see [Limits](#limits)).
- Static members beyond what the learned imports name: a learned `import static Owner.CONSTANT` is
  offered like a method, but nothing proposes `CONSTANT` before some file imports it.

## Options considered

The surfaces are the same in every option; they differ in where the candidates come from.

| Option | Candidates | Gap | Cost |
|---|---|---|---|
| A. Built-in list | a fixed set of well-known classes (AssertJ, JUnit, Mockito, Hamcrest) | every library or project helper not on the list; the list needs upkeep; says nothing about a team's style in main code | ~1 day |
| B. A, plus a configurable list | A, plus classes named in project configuration | anything nobody configured | A + ~½ day |
| C. Learned from the project | the static imports the project already declares, per scope | a scope with none declared yet | ~2½–3 days |
| D. Index every static member | all public static members on the classpath | none, but floods completion with `of`, `valueOf`, `builder`, … | ~1–2 weeks |

**Decision: C.**
It needs no list to maintain, it adapts to each codebase (custom fixture helpers, WireMock,
RestAssured, Awaitility, …), it ranks by what the team actually uses, and it follows the team's style
in main code without a setting.
D is rejected for cost and noise.

### Why source imports and not class files

The reactor's class files are already scanned (their constant pools give the type usage counts), and
their method references name every static method the code calls.
But a class file cannot tell `assertThat(x)`, written through a static import, from `List.of(x)` or
`Objects.requireNonNull(x)`, written qualified.
Learning from class files would propose `of`, `requireNonNull` and `format` as static imports in code
that never imports them statically.
The `import static` lines are the signal: they record what the team chose to import statically.

### Why each scope learns from its own files

Pooling main and test imports would leak test habits into production code.
Test libraries would not leak (every candidate is validated against the file's own classpath, and the
main classpath has no AssertJ or Mockito), but owners on both classpaths would: a test suite that
statically imports `Collectors.toList` or one of the project's own constants would start offering them
in main code whose style avoids static imports.

## Design

### Learning: the reference candidate index

`ReferenceCandidateIndex` already reads every source file at workspace load, re-reads files changed
on disk, and is updated from every open buffer on each change.
It already extracts each file's import lines, but strips the `static` marker, so a static import and an
ordinary import of a nested type are the same token.

Change: keep the marker.
For each file the index records the static imports it declares, either a member
(`org.mockito.Mockito.when`) or a whole class (`org.mockito.Mockito.*`), and whether the file is under
a test source root.
It keeps two counts per static import, one over test files and one over main files: the number of
files of that scope that declare it.

The learning is live: a static import added to one file (typed, or by an agent on disk) is offered in
every other file of the same scope as soon as the index sees it.

**Text scan.** The index reads import lines with a text scan, not a javac parse; this feature now relies
on it.
That is acceptable here because the scan only proposes names: every candidate is validated through javac
before it is offered (below), so a mis-scan costs at most a missed suggestion.
Parsing every source file with javac at load would give the same answer for seconds of work on a large
project.

### The lookup

One shared lookup serves the three surfaces.
Input: the counts snapshot for the file's scope, the file's attributed analysis, and a name (exact, for
the quick fix and missing imports) or a prefix (for completion).
Output: candidates `(owner, member)`, ranked by count, each validated through javac against the file's
own compile context:

- the owner resolves on this module's classpath for the file's scope (a class only another module
  uses is skipped);
- the member is a public static method or field of the owner; for a learned `Owner.*`, inherited
  static members count, as they do for the import itself;
- the member is not already in scope in this file (an existing `import static` of it or of its class):
  ordinary completion already offers it.

A name declared by several learned owners (AssertJ's and Hamcrest's `assertThat`) yields one
candidate per owner, never one per overload.

### Scope and threading

The session knows each file's source tree.
On the event loop, at request time, it takes an immutable snapshot of the counts for the file's scope
(test counts for a test file, main counts for a main file) and hands it to the compile worker with the
request, the way the type index snapshot travels today.
An empty snapshot (a scope with no static imports) turns every surface off, so no scope check sits
deeper in the code and no index state is shared across threads.

### Completion

In an expression or statement position with a simple-name prefix, the learned candidates join the
existing ones:

- **Ranking:** below locals, parameters and members of the class; among themselves by count.
- **Item:** label the member name with a call snippet as for other methods; detail the owner's
  qualified name; one `import static owner.member;` additional edit (the completion engine can already
  attach a static import edit).
- **Incomplete:** the result stays incomplete, so a longer prefix asks again.

### Quick fix

javac reports an unresolved call as `compiler.err.cant.resolve.location.args`; it is classified today as
a variable reference, whose only fix is "Declare local variable".
The quick fix adds "Import static `owner.member`" for each candidate of the exact name.

### Add missing imports

`lathe.missingImports` today lists each unresolved **type** name with its import candidates; the client
adds unambiguous ones and asks about the rest.
It also lists each unresolved **call** name with its candidates from the lookup.

Wire format: a candidate is the text that follows `import` in the line to insert, so a static
candidate is `static org.assertj.core.api.Assertions.assertThat`.
The Neovim client (`'import ' .. fqn .. ';'`) and the MCP tool's edit already build the line that way,
so both work unchanged, and the picker shows the `static` it will add.

## Limits

- **Cold start, per scope.** A scope whose files declare no static imports yet gets no suggestions
  until one file adds one by hand; from then on it is learned.
  In main code that is the intended behaviour for teams that avoid static imports there.
- **Libraries new to the project.** The first static import from a library, in each scope, is typed by
  hand.

## Testing

Unit tests on the existing fixtures (`CompletionFixture`, `CodeActionTest`), with JUnit's `Assertions`
and `Assumptions` as the learned owners, since the in-memory compiler resolves them:

- the index records static imports with their marker, counts test and main files separately, and
  updates when a buffer adds or removes one;
- completion in a test offers a member learned from test files with its static import edit;
- a main file is offered only what main files import: nothing when main has no static imports, and not
  a member only test files import;
- a member already imported is not offered twice; an owner missing from the classpath is skipped;
- the quick fix offers the import for an unresolved call, by the same scope rule;
- missing imports lists the unresolved call with its `static …` candidate.

The `multi-module` fixture's `LspSmokeTest` gains one end-to-end check: a test-module file using a
statically imported member elsewhere in the module gets the completion and the import.

## Slices

| Slice | Content | Effort |
|---|---|---|
| 1 | Index: keep the static marker, per-file static imports, test and main counts, snapshot per scope | ½ day |
| 2 | Lookup and completion, with the snapshot passed through the completion request | 1 day |
| 3 | Quick fix and add missing imports (LSP command, Neovim, MCP `fix_imports`) | ½–1 day |
| 4 | Fixture check, docs (README, Neovim guide, status) | ½ day |

No new modules, no dependencies, no type index format change, no work at sync time.
