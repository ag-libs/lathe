# Completion Gaps

Gaps discovered through `dev/probe.py` against dropwizard and helidon workspaces.
Probe run: 61 probes, 38 pass, 21 fail across both workspaces.
Full log at `/tmp/lathe_probe.log`.

---

## Stability

### 1. SEVERE crash when a file is referenced before it is opened

`IllegalStateException: no source root for <uri>` propagates as SEVERE when a file
is touched as a background dependency before being explicitly opened by the client.

Observed during the probe session: `DropwizardResourceConfig.java` was reached
as a side-effect of opening `DropwizardTestSupport.java` in the same server session,
before the probe had sent `textDocument/didOpen` for it.
Opening the file directly produces 0 diagnostics and no error.

The exception fires twice — once in `compile:open` and once in `completion`:

```
SEVERE  [compile:open] failed for …/DropwizardResourceConfig.java
java.lang.IllegalStateException: no source root for …/DropwizardResourceConfig.java
SEVERE  [completion] failed for …/DropwizardResourceConfig.java
java.lang.IllegalStateException: no source root for …/DropwizardResourceConfig.java
```

**Fix:** catch `IllegalStateException` in the compile and completion paths and return
an empty result with a FINE log rather than propagating SEVERE.
A file that has not yet been opened should be silently skipped.

---

## Presentation — affects every result

### 2. `textEdit` absent on all items

Every completion item returned by the server — member access, simple name,
type-index — has no `textEdit`.
Insertion relies on the client to infer the prefix replacement from `insertText`.
Some clients append rather than replace, producing duplicates like `toStrtoString()`.

`textEdit` must carry an explicit `{range, newText}` where `range` covers
`tokenStart..cursor` on the current line.
For completion before or inside an existing token, `InsertReplaceEdit` should be used.

See [lathe-completion-presentation.md § 6](lathe-completion-presentation.md) for the
full replacement-range contract.

### 3. Object utility methods lead every member-access list

`equals`, `toString`, `getClass`, `hashCode` and sometimes `wait`, `finalize`, `clone`
appear in positions 1–5 of every member-access result.

Observed on every member-access probe across both workspaces:

```
proposals: [equals(Object), toString(), getClass(), hashCode(),
            applyConnectionString(ConnectionString), …]
```

Root cause: `ProposalGenerator.sortKey()` returns `"0_<name>"` for all inherited members.
Only `wait`, `notify`, `notifyAll`, and `finalize` receive `"9_"`.
`equals`, `hashCode`, `toString`, and `getClass` are bucketed the same as domain methods.

**Fix:** extend `sortKey()` to detect all Object-declared methods and return `"7_"` for them,
consistent with the bucket table in
[lathe-completion-presentation.md § 7](lathe-completion-presentation.md).

### 4. `kind` missing on all type-index items

`CompletionEngine.typeIndexItem()` never sets `kind` on the returned `CompletionItem`.
Type completions have no icon in the editor.

`TypeIndexEntry` carries enough information to set the kind:
use `CompletionItemKind.Class`, `Interface`, `Enum`, or `Record` based on the entry type.

**Fix:** one call to `item.setKind(…)` in `typeIndexItem()`.

---

## Receiver resolution

### 5. Cross-line chained call falls back to type-index

`addFilter(…)\n.setInitParameter` returns 50 type-index items instead of the
member-access members of `FilterRegistration.Dynamic`.

The multi-line method-call receiver is not resolved from the cached analysis,
so the engine silently falls back to type-index, producing irrelevant results.

```
[completion] inject prefix=|| receiver=|handler.addFilter(…)| ctx=STATEMENT
[type-index] typeRef items=50
```

**Fix:** improve cross-line method-call receiver resolution in `TypeResolver`.
When the receiver text spans a newline, the expression should still be attributed
against the cached snapshot.

### 6. Cross-line simple-name receiver resolves to wrong type

`bufferedReader\n.lines()` returns only 2 items — both Object boilerplate —
instead of `BufferedReader` members.

`bufferedReader` is a local variable declared on the previous line.
The receiver text is extracted correctly but type resolution produces `Object`
rather than `BufferedReader`.

```
proposals count=2 labels=[equals(Object), toString()]
```

**Fix:** when `TypeResolver` resolves a simple-name receiver and gets `Object`,
it should scan the local-variable declarations visible at the cursor line
to find the declared type, rather than accepting the raw attributed type.

### 7. Field receiver resolves to wrong type

`handler.getServletContext()` returns 2 items, neither is `getServletContext`.
The field `handler` is declared in the class but its type is not resolved correctly
from the cached analysis.

**Fix:** investigate why `TypeResolver.resolveReceiver` returns the wrong declared type
for this field. Likely a mismatch between the field name as it appears in the sentinel
text and the element lookup in `Elements`.

### 8. New-object receiver not resolved

`instrumented.setServer` returns 0 items.
`instrumented` is assigned from a `new InstrumentedEE10Handler(…)` constructor call.
The type of a `new`-expression result is not being resolved.

**Fix:** `TypeResolver` should handle `NewClassTree` as a receiver — the constructed type
is directly available from the tree node.

### 9. AssertJ fluent chain not resolved

`assertThat(response.getStatus()).isEqualTo` returns 168 items but `isEqualTo`
is not among them.

The receiver `assertThat(…)` returns a parameterised `AbstractAssert` subtype.
The type argument is not substituted, so the engine resolves members on the raw
`AbstractAssert` rather than `AbstractIntegerAssert`, and `isEqualTo` is not found.

**Fix:** ensure `TypeResolver` performs generic type argument substitution when resolving
the return type of a method-call receiver.

---

## Type-index coverage

### 10. Helidon-internal types not indexed

Type-index queries for `TypeNam`, `ClassMod`, `Annot`, `Immut`, `Schedul` against
helidon files return 0 items.
`Execut` works (returns `ExecutionTime`), so the indexer reaches some helidon modules.

Affected files: `ServiceDescriptorCodegen.java`, `CronTask.java`.

These types likely live in helidon modules whose JARs are not being indexed,
possibly because the JPMS module graph limits which dependency JARs are resolved
for those source modules.

**Fix:** verify that `lathe:sync` resolves and indexes all compile-scope dependency JARs
for each module, not just those on the `--module-path` visible to javac.

### 11. `LogbackAccessRequestLogFactory` not in dropwizard index

The type-index returns 0 items for this type name from `AbstractServerFactory.java`.
The class is a dropwizard dependency type; its JAR may not have been indexed during sync.

**Fix:** same root cause as gap 10 — verify dependency JAR coverage for the dropwizard
workspace type index.

---

## Empty-prefix behaviour

### 12. Empty prefix bypasses the guard in some positions

The blank-prefix probe in the helidon workspace returns 14 items at a class-body position
and 33 items at a method-body position, despite the empty-prefix guard in
`completeSimpleNameTypeReference`.

The guard fires correctly for the dropwizard workspace.
In the helidon positions the injected line lands in a context where the sentinel detects
a receiver or a different `SentinelContext` value, routing around the guard.

**Fix:** investigate which `SentinelContext` is detected at those positions and ensure
the empty-prefix guard is applied consistently for all code paths, not only for
`completeSimpleNameTypeReference`.

---

## Summary table

| # | Gap | Area | Effort |
|---|---|---|---|
| 3 | Object methods sort first | Presentation | Small |
| 4 | `kind` missing on type-index items | Presentation | Trivial |
| 1 | SEVERE crash — no graceful fallback | Stability | Small |
| 2 | `textEdit` absent on all items | Presentation | Medium |
| 8 | New-object receiver not resolved | Resolution | Small |
| 12 | Empty prefix bypasses guard | Engine | Small |
| 6 | Cross-line local-var receiver → Object | Resolution | Medium |
| 7 | Field receiver → wrong type | Resolution | Medium |
| 5 | Cross-line method-call chain → type-index | Resolution | Medium |
| 9 | Generic return type not substituted | Resolution | Medium |
| 10 | Helidon internal types not indexed | Indexing | Medium |
| 11 | Dropwizard dependency types missing | Indexing | Medium |
