# Lathe — Code Review Findings (June 2026)

Actionable findings from a full-source evaluation conducted June 2026.
Grouped by category, ordered roughly cheapest-to-most-expensive within each group.
All items should be addressed before new features to keep the codebase clean.

---

## 1. DRY Violations

### 1a. `declaringType(Element)` duplicated in two classes

**Files:** `CandidateFactory.java:148`, `CompletionEngine.java:554`

Both contain an identical private method:
```java
private static String declaringType(final Element element) {
    return element.getEnclosingElement() instanceof final TypeElement typeElement
        ? typeElement.getQualifiedName().toString()
        : null;
}
```

**Fix:** Delete the copy in `CompletionEngine`.
`CompletionEngine` already constructs `CandidateFactory` instances; the static helper can be
accessed there, or `annotationElementCandidate` and `annotationEnumConstantCandidate` can
delegate to `CandidateFactory.typeElementCandidate` / `CandidateFactory.memberCandidate`
instead of building `CompletionCandidate` directly, which would remove the need for the copy
entirely.

---

### 1b. Analysis-resolution pattern repeated 3× in `CompletionEngine`

**File:** `CompletionEngine.java:136`, `CompletionEngine.java:417`, `CompletionEngine.java:446`

`completeImport`, `completeAnnotationArgument`, and `completeAnnotationArgumentValue` all open
with:
```java
final var analysis =
    req.cached() != null
        ? req.cached().analysis()
        : (compiler != null ? compiler.reattribute(req.uri(), req.content()) : null);
if (analysis == null) {
    return CompletionOutcome.of(List.of());
}
```

**Fix:** Extract to a private helper:
```java
private AttributedFileAnalysis resolveAnalysis(final CompletionRequest req) { ... }
```
Return `null` when unavailable; callers guard on null and early-return.

---

### 1c. Merge-into-`LinkedHashMap` dedup pattern repeated 4+ times in `CompletionEngine`

**File:** `CompletionEngine.java` — in `mergeLangTypes`, `mergeInFileTypes`,
`completeConstructorTypeReference`, `completeSimpleName` (static-fit merge), and
`mergeSimpleNameAndTypeIndexItems`.

All share the same shape:
```java
final var merged = new LinkedHashMap<String, CompletionItem>();
firstList.forEach(i -> merged.put(completionIdentity(i), i));
secondList.forEach(i -> merged.putIfAbsent(completionIdentity(i), i));
return new CompletionOutcome(List.copyOf(merged.values()), freshAnalysis, incomplete);
```

`mergeSimpleNameAndTypeIndexItems` already exists for one variant but is not used by the others.

**Fix:** Replace all variants with a single private helper:
```java
private static CompletionOutcome mergeOutcomes(
    List<CompletionItem> primary,
    CompletionOutcome secondary) { ... }
```
`primary` items win; `secondary` items fill gaps; `incomplete` flag is OR'd;
`freshAnalysis` comes from the caller as needed.
Replace all four ad-hoc merge blocks and retire `mergeSimpleNameAndTypeIndexItems`.

---

### 1d. `ReactorProjects.gav()` — three identical method bodies

**File:** `ReactorProjects.java:82–95`

Three public overloads for `MavenProject`, `org.apache.maven.artifact.Artifact`, and
`org.eclipse.aether.artifact.Artifact` all format `"%s:%s:%s"` from groupId/artifactId/version.

**Fix:** Add a private overload:
```java
private static String gav(final String g, final String a, final String v) {
    return "%s:%s:%s".formatted(g, a, v);
}
```
Delegate all three public overloads to it, eliminating the repeated format string.

---

### 1e. `"lsp-params-"` prefix is an inline string rather than a `LatheLayout` constant

**Files:** `ParamsWriter.java:34`, `LatheLayout.java:46`

`ParamsWriter` writes the file using a bare string:
```java
latheModuleDir.resolve("lsp-params-" + sourceTree + ".json")
```

`LatheLayout.isParamsFile()` checks the same prefix/suffix inline.
AGENTS.md is explicit: *"Shared directory and file names … must reside in `LatheLayout.java`
or `LatheFlags.java` as constants to prevent typos and centralize layout paths."*

**Fix:** Add to `LatheLayout`:
```java
public static final String PARAMS_FILE_PREFIX = "lsp-params-";
```
And a factory helper:
```java
public static String paramsFileName(final String sourceTree) {
    return PARAMS_FILE_PREFIX + sourceTree + ".json";
}
```
Update `ParamsWriter` and `isParamsFile()` to use them.

---

## 2. Dead Code

### 2a. `LatheContext.latheDir` is assigned but never read

**File:** `LatheCompiler.java:127`

```java
private record LatheContext(Path latheDir, Path moduleDir, Path moduleRel) {}
```

`performCompile()` unpacks only `moduleDir` and `moduleRel`; `latheDir` is never accessed.

**Fix:** Remove the `latheDir` component from the record and its assignment in
`resolveLatheContext()`.

---

### 2b. Null-guard in `WorkspaceManifest.load()` is unreachable

**File:** `WorkspaceManifest.java:79`

```java
final var entries =
    (rawEntries != null ? rawEntries : List.<DependencyData>of())  // dead
        .stream()...
```

`WorkspaceManifestData`'s compact constructor already guarantees non-null:
```java
dependencySources = Objects.requireNonNullElse(dependencySources, List.of());
```

**Fix:** Remove the ternary; replace with `data.dependencySources().stream()...`.

---

### 2c. `JdkSourceData` carries factory/predicate methods that belong only on `JdkSource`

**File:** `JdkSourceData.java:26–43`

`JdkSourceData.present()`, `JdkSourceData.missing()`, and `JdkSourceData.isPresent()` mirror the
lifecycle API of `JdkSource` (the live plugin record), but `JdkSourceData` is a plain JSON
schema DTO.
The only production caller is `JdkSource.toData()`, which passes all fields directly and does
not use these factories.
They are used in one test (`ExternalCompilerTest`), which can construct the record directly.

**Fix:** Remove `present()`, `missing()`, and `isPresent()` from `JdkSourceData`.
Update `ExternalCompilerTest` to construct `JdkSourceData` directly with `new JdkSourceData(...)`.

---

## 3. Naming

### 3a. `td` in `ModuleSourceCompiler` should be `tempDir`

**File:** `ModuleSourceCompiler.java`

```java
private final Path td;
```

Every other temporary-directory variable in the codebase uses the full word (`tempDir`, `tempFile`).
`td` is an unexplained abbreviation.

**Fix:** Rename `td` → `tempDir` throughout `ModuleSourceCompiler`.

---

### 3b. `CursorContext.ctx()` returns `AttributedFileAnalysis` — name should be `analysis`

**File:** `SourceAnalysisSession.java:216`

```java
private record CursorContext(AttributedFileAnalysis ctx, TreePath path) {}
```

The field is typed as `AttributedFileAnalysis`; calling it `ctx` is vague and inconsistent with
the rest of the file, where `analysis` is the standard name for this type.
All call sites read `cur.ctx().trees()`, `cur.ctx().elements()`, etc.

**Fix:** Rename the record component `ctx` → `analysis`; update all call sites within
`SourceAnalysisSession`.

---

### 3c. `DependencySource.present(List<…>)` overloads the factory name with a filter operation

**File:** `DependencySource.java:44`

```java
public static List<DependencySource> present(final List<DependencySource> sources)
```

This shares the name `present` with the factory:
```java
public static DependencySource present(String gav, Path jar, ...)
```

The filter reads as a factory at call sites (`DependencySource.present(dependencySources)`), but
it actually filters to those with `status == PRESENT`.

**Fix:** Rename the filter method to `withSources(List<DependencySource>)` or
`presentOnly(List<DependencySource>)`.
Update the three call sites in `SyncMojo`, `DependencySourceResolver`, and their tests.

---

## 4. Style / AGENTS.md Compliance

### 4a. `Stopwatch` variable declarations inconsistent across the codebase

**Files (using `var` — incorrect per AGENTS.md):**
- `LatheCompiler.java:95` — `var sw = Stopwatch.start()`
- `DependencySourceSync.java:27` — `var t = Stopwatch.start()`
- `SourceAnalysisSession.java:51/71/101/175` — `var t = Stopwatch.start()`
- `ReferenceCandidateIndex.java:33` — `var t = Stopwatch.start()`

**Files (using explicit type — correct):**
- `DependencyTypeIndexSync.java:29/63` — `final Stopwatch t = Stopwatch.start()`
- `JdkTypeIndexSync.java:38` — `final Stopwatch t = Stopwatch.start()`

AGENTS.md: use `var` only when the variable name makes the type self-evident.
Neither `t` nor `sw` conveys `Stopwatch`.

**Fix:** Change all `var t` / `var sw` assignments of `Stopwatch.start()` to
`final Stopwatch t` / `final Stopwatch sw`.

---

### 4b. `shouldOfferBareTypeReference()` is a pure passthrough — KISS violation

**File:** `CompletionEngine.java:993`

```java
private static boolean shouldOfferBareTypeReference(final SentinelInjectionResult injected) {
    return hasUppercasePrefix(injected);
}
```

The one call site (`CompletionEngine.java:222`) is already inside a guarded block; the wrapper
name adds a layer of indirection with no additional clarity.

**Fix:** Inline `shouldOfferBareTypeReference(injected)` → `hasUppercasePrefix(injected)` at the
call site; delete the method.

---

## 5. Larger Structural Refactors

These are lower-priority but worth tracking. Tackle only after the items above are done.

### 5a. `ParsedSentinel` and `CompletionSite` share 9 fields with no encapsulation

**Files:** `ParsedSentinel.java`, `CompletionSite.java`

`ParsedSentinel` (20 fields) and `CompletionSite` (15 fields) duplicate:
`prefix`, `receiverText`, `receiverEndOffset`, `enclosingClass`, `enclosingMethod`, `argIndex`,
`enclosingReceiver`, `enclosingMethodName`, `declaredTypeText`.

`CompletionSite.from()` copies all nine across.

Additionally, `ParsedSentinel.invalid()` fills 11 fields with dummy sentinels
(`null`, `-1`, `false`), which is error-prone and indicates the type is trying to be two things.

**Proposed fix:** Extract a nested `EnclosingContext` record holding the enclosing-context fields:
```java
record EnclosingContext(
    String enclosingClass,
    String enclosingMethod,
    int argIndex,
    String enclosingReceiver,
    String enclosingMethodName) {}
```
Make `ParsedSentinel.invalid()` store `null` for the `EnclosingContext` instead of 5 separate
null/–1 values.
`CompletionSite.from()` can then hold a reference to the same `EnclosingContext` without copying.

This is a larger change touching `SentinelParser` and most of the completion pipeline; ensure
tests pass before merging.

---

### 5b. `WorkspaceManifest` mixes data holding with javac-API service logic

**File:** `WorkspaceManifest.java`

The class is 345 lines combining:
- Manifest loading and data storage (maps, paths, `load()`, `empty()`)
- Rich resolution logic using `StandardJavaFileManager`
  (`originLabel`, `externalSourceRoot`, `classpathLabel`, `jarForModulePath`,
  `extractReactorModuleName`, `extractJarPath`, `classify`)

The service methods are only called from `SourceAnalysisSession` when producing hover/definition
results.

**Proposed fix:** Move the javac-API service methods to a new package-private class
`WorkspaceManifestResolver` (in the same `workspace` package) that takes a `WorkspaceManifest`
and is instantiated by `SourceAnalysisSession` (or held by `JavaSourceCompiler`).
`WorkspaceManifest` becomes a pure data holder.

---

## Change Checklist

| # | File(s) | Action | Size |
|---|---------|--------|------|
| 1a | `CandidateFactory`, `CompletionEngine` | Delete duplicated `declaringType()` from `CompletionEngine` | XS |
| 1b | `CompletionEngine` | Extract `resolveAnalysis(req)` helper | S |
| 1c | `CompletionEngine` | Unify 4+ merge blocks into `mergeOutcomes()` | M |
| 1d | `ReactorProjects` | Add private `gav(String,String,String)`, delegate overloads | XS |
| 1e | `LatheLayout`, `ParamsWriter` | Add `PARAMS_FILE_PREFIX` constant + `paramsFileName()` helper | XS |
| 2a | `LatheCompiler` | Remove `latheDir` from `LatheContext` record | XS |
| 2b | `WorkspaceManifest` | Remove dead null-guard | XS |
| 2c | `JdkSourceData`, `ExternalCompilerTest` | Remove `present/missing/isPresent` from DTO | XS |
| 3a | `ModuleSourceCompiler` | Rename `td` → `tempDir` | XS |
| 3b | `SourceAnalysisSession` | Rename `CursorContext.ctx` → `analysis` | XS |
| 3c | `DependencySource` + 3 callers | Rename filter `present` → `withSources` | XS |
| 4a | 7 files | Change `var t/sw = Stopwatch.start()` to explicit type | XS |
| 4b | `CompletionEngine` | Inline `shouldOfferBareTypeReference` | XS |
| 5a | `ParsedSentinel`, `CompletionSite`, `SentinelParser` | Extract `EnclosingContext` record | L |
| 5b | `WorkspaceManifest`, `SourceAnalysisSession` | Split into data holder + resolver | L |
