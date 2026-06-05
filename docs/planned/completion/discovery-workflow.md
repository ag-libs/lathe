# Lathe — Completion Discovery Workflow

This document describes how completion discrepancies should move from observation to regression coverage.

The short version:
use real-project probes to discover issues,
reduce accepted issues to focused tests,
and record anything unresolved in the gap log.

## Tools

### `dev/explore.py`

`dev/explore.py` is the interactive probe shell.
It opens one Java source file in the Lathe language server and lets the user request completions,
hover,
definitions,
diagnostics,
temporary injections,
and server logs.

The input file must live under a Lathe-enabled workspace.
The script finds the workspace by walking upward until it finds `.lathe/`.

Typical setup for a target project:

```bash
mvn process-test-classes
```

Typical probe:

```bash
python3 dev/explore.py /path/to/project/src/main/java/example/Foo.java \
  complete after "builder." expect build min 1
```

Temporary injection:

```bash
python3 dev/explore.py /path/to/project/src/main/java/example/Foo.java \
  inject "Collections." expect emptyList min 1
```

`inject` modifies only the server's open-document view.
The file on disk is not changed.

### `dev/lsp.py`

`dev/lsp.py` provides the Python `LatheClient` used by `explore.py`.
Use it for future automation and corpus replay.

`explore.py` is the human workflow.
`LatheClient` is the automation API.

## Current Exploration Targets

Use only these external projects for the current manual discovery phase:

| Project | Path | Why |
|---|---|---|
| Dropwizard | `~/git/dropwizard` | Large real-world Maven codebase with broad Java API usage. |
| Helidon | `~/git/helidon` | JPMS-heavy/server-side codebase with richer module and fluent-call patterns. |

Before probing a target,
make sure the workspace has current Lathe metadata.

For Dropwizard,
use Java 25 as required by that project:

```bash
export JAVA_HOME=/opt/amazon-corretto-25.0.0.36.2-linux-x64
cd ~/git/dropwizard
mvn process-test-classes
```

For Helidon,
run the project's normal Lathe refresh command from its workspace root:

```bash
cd ~/git/helidon
mvn process-test-classes
```

Do not add Lathe itself to this manual discovery phase yet.
The goal is to exercise completion in large real-world workspaces before reducing accepted gaps into Lathe unit tests.

## Explorer Completion Recipes

Use `show` and `grep` first to orient yourself in the target file:

```text
show 120
grep getServletContext
```

Then probe concrete completion positions.

Member access:

```text
complete after "receiver." expect expectedMethod min 1
complete after "receiver.pre" expect preferredMethod min 1
```

Chained receiver:

```text
complete after "request.getHeaders()." expect containsKey min 1
complete after "builder.config()." expect setValue min 1
```

Static receiver:

```text
complete after "Collections." expect emptyList min 1
complete after "TimeUnit." expect SECONDS min 1
```

Import declaration:

```text
complete after "import java.util." expect List Map min 2
complete after "import static java.util.Collections." expect emptyList min 1
```

Temporary member-access injection:

```text
inject "Collections." expect emptyList min 1
inject "System.out." expect println min 1
```

Temporary expression-slot injection:

```text
inject "String value = " expect null min 1
inject "return " expect null min 1
```

Temporary static-member-fit probe:

```text
inject "Duration timeout = Dur" expect Duration min 1
```

Diagnostics and logs:

```text
diagnostics
log 30
```

Use assertions when the expected behavior is already clear.
Omit assertions when exploring an unknown area and inspecting the returned items manually.

## Capturing A Gap With Explorer

Use this sequence when investigating a suspected completion problem:

1. Start `dev/explore.py` on the real Java file.
2. Run `diagnostics`.
   If the file already has unrelated errors,
record that in the gap entry.
3. Use `grep` or `show` to find the local context.
4. Run `complete after ...` or `complete line:col`.
5. If the existing code is hard to probe,
use `inject` to create a temporary completion site.
6. If the result is surprising,
run `log 30`.
7. Record the exact command,
the expected behavior,
the actual top items,
and any relevant log line in `gap-log.md`.
8. Classify the tier,
failure mode,
and likely owner component.

Do not edit the target project source while using explorer for discovery.
Use `inject` for temporary source changes.

## Gap Recording Location

Record active completion discrepancies in [`gap-log.md`](gap-log.md).

Use one heading per entry:

```text
## CQ-0001 — Missing method after chained receiver

ID: CQ-0001
Status: new
Tier: basic
Failure mode: missing-candidate
Owner component: TypeResolver

Project/file:
Probe command:
Cursor context:

IntelliJ or JDT behavior:
Lathe behavior:
Expected Lathe behavior:
Accepted edit, if relevant:

Regression target:
Notes:
```

Use sequential `CQ-` IDs for completion-quality gaps.
Keep entries short enough that scanning the log remains useful.

When an entry is fixed,
update `Status` and `Regression target`.
Only move details to `docs/done/` if the fix teaches a durable design lesson.

`IntelliJ or JDT behavior` is useful evidence,
not the final specification.
`Expected Lathe behavior` should cite the expectation rule that Lathe chooses to follow.

## Tiers

Use the tiers from [`expectations.md`](expectations.md):

- `basic`
- `typed`
- `assistive`
- `presentation`

The tier says what kind of feature the expected behavior belongs to.

## Failure Modes

Use one primary failure mode per discrepancy:

| Failure mode | Meaning |
|---|---|
| `missing-candidate` | A candidate that should be present is absent. |
| `invalid-candidate` | A candidate appears where Java cannot use it. |
| `bad-ranking` | The candidate exists but useful candidates are buried below poor matches. |
| `bad-insertion` | Accepting the item inserts syntactically wrong text. |
| `bad-import-edit` | The item has a missing, duplicate, misplaced, or unnecessary import edit. |
| `bad-replacement-range` | Completion replaces too little or too much text. |
| `bad-presentation` | The LSP item display shape is confusing or loses important type/package information. |
| `stale-snapshot` | Completion uses old semantic state when the live buffer requires refresh or fallback. |
| `latency` | Completion is too slow for an interactive request. |
| `protocol-shape` | The LSP response shape limits client behavior. |

## Owner Components

Map the discrepancy to the narrowest likely owner:

| Component | Use when |
|---|---|
| `SentinelInjector` | Prefix, suffix, receiver text, statement/expression context, or injected parse text is wrong. |
| `SentinelParser` | The Java syntax site is classified incorrectly. |
| `CompletionSite` | Mode or replacement range is wrong. |
| `SemanticCompletionContext` | Static context, value context, or expected value is wrong. |
| `TypeResolver` | Receiver type, scope, expected type, or overload context is wrong. |
| `CandidateGenerator` | Member candidates or nested type candidates are missing or invalid. |
| `SimpleNameProvider` | Locals, fields, methods, parameters, or static imports are missing or invalid. |
| `ImportCompletionProvider` | Package or import declaration candidates are wrong. |
| `TypeIndexValidator` | Importable type candidates are too broad or too narrow. |
| `CompletionCandidateRanker` | Filtering or sort order is wrong. |
| `CompletionItemPresenter` | LSP item labels, snippets, edits, or imports are wrong. |

If the owner is unclear,
record the best guess and include `log 30` output from `dev/explore.py`.

## Probe Workflow

1. Reproduce the discrepancy with `dev/explore.py`.
2. Record the exact command.
3. Capture the top returned labels,
details,
and relevant server log lines.
4. If the discrepancy concerns accepting an item,
capture the returned `textEdit`,
`insertText`,
`insertTextFormat`,
and additional text edits.
5. Classify tier,
failure mode,
and likely owner.
6. Decide whether the discrepancy is accepted,
deferred,
or a non-goal.
7. For accepted issues,
reduce to the smallest fixture that still fails.
8. Add or update a regression test.
9. Keep the real-project probe only when the issue depends on Maven,
JPMS,
type-index,
generated sources,
or real-project classpath shape.

## Accepted-Completion Edits

Completion quality includes the edit applied after a user accepts an item.

For method completions in ordinary code:

- methods with parameters should complete to `name($1)` or equivalent,
with the caret inside the parentheses;
- methods without parameters should complete to `name()`,
with the caret after the closing parenthesis;
- the replacement range should remove only the typed prefix or intended in-token text;
- accepting the item should not duplicate already-typed suffix text.

For static import declarations,
method candidates intentionally insert the bare member name plus a semicolon when needed.
That is import syntax,
not method-call syntax.

When a discrepancy is about accepted text,
record both the menu item and the edit payload.
The menu item can be correct while the accepted source text is wrong.

## Regression Targets

Prefer focused unit tests when possible.

Use a completion unit test when the issue can be represented as a small Java source string with `§`.
Use a server or module-level test when routing,
workspace metadata,
JPMS,
or type-index behavior is essential.
Keep an explorer probe when the behavior depends on a large real project and cannot be reduced honestly.

Suggested unit-test grouping:

```text
CompletionMemberAccessTest
CompletionSimpleNameTest
CompletionArgumentPositionTest
CompletionTypeReferenceTest
CompletionImportTest
CompletionAnnotationTest
CompletionInsertionTest
CompletionRankingTest
CompletionStaleSnapshotTest
CompletionTypeIndexTest
```

The current implementation still uses a large `CompletionEngineTest`.
Splitting it is an organizational follow-up,
not a prerequisite for adding coverage.

## Corpus Replay Direction

Manual explorer probes are not enough to find the long tail.

The future scalable path is corpus replay:

1. Open real compiling Java files.
2. Mine existing source constructs.
3. Temporarily remove or truncate the existing symbol.
4. Request completion at that point.
5. Expect the original symbol to appear.
6. Report reviewable failures.

The source program becomes the oracle:
if a compiled file already uses `receiver.method()`,
then completion after `receiver.` should usually include `method`.

Corpus replay should produce candidates for review,
not automatically declare every failure a Lathe bug.
