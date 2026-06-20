# Lathe — Find References Current Gaps

Working design draft.
This document records the current correctness, policy, and test-coverage gaps in
`textDocument/references`.

The original feature design remains in [lathe-find-references.md](lathe-find-references.md).
This document is the current gap tracker when the original design and implemented behavior differ.

---

## 1. Current Behavior

Find References uses javac attribution for exact symbol matching and a textual candidate index to
avoid compiling every workspace file.

The implemented search supports:

- same-file references;
- open and closed files in the declaring module;
- transitive downstream reactor modules;
- private and local symbol restriction to the declaring file;
- package-private restriction to the declaring package;
- explicit imports, wildcard imports, static imports, and implicit `java.lang` type candidates.

Live probes confirm that JDK symbols work when the request originates in project source:

- `java.lang.String` returns project references;
- `java.time.Duration` returns project references;
- the Helidon `Duration` incident produced two server-side locations in 12 ms.

The remaining gaps concern external-source request routing, external-symbol scope policy, failure
reporting, and end-to-end verification.

---

## 2. Gap FR-001 — References From External Source Have No Workspace Search Root

Status: verified correctness defect.

When Find References is invoked from a cached JDK or dependency source file,
`WorkspaceSession.referencesFuture()` resolves the symbol through the external compiler worker but
derives the search scope from:

```java
workspace.moduleSourceFor(toPath(cursorUri))
```

Cached external source files do not belong to a reactor `ModuleSourceConfig`.
The resulting `cursorConfig` is empty, the configuration list becomes empty, and no project file is
searched.

This affects symbols such as:

- `java.lang.String` when invoked from the JDK `String.java` declaration;
- `java.time.Duration` when invoked from the JDK `Duration.java` declaration;
- public types and members in dependency source caches.

### Required behavior

An external declaration must still be able to search the active workspace.
The request needs a workspace search root independent of the external cursor path.

For a single-workspace server, the simplest correct behavior is to search all reactor module
configurations and rely on `ReferenceCandidatePlanner` plus javac matching to remove irrelevant
files.

If multiple workspace folders are supported later, the server must associate the external source
buffer with the client workspace that opened it rather than search unrelated workspaces.

### Required tests

- Request references from a cached JDK type declaration and find project usages.
- Request references from a cached dependency type declaration and find project usages.
- Verify that similarly named project types are rejected by javac identity matching.
- Verify behavior when the workspace has no candidate project files.

---

## 3. Gap FR-002 — External-Symbol Search Scope Policy Is Unresolved

Status: roadmap gap requiring a product decision.

The original design says JDK and third-party symbols should search open files only.
The implementation instead searches reactor files selected from the cursor module's downstream
graph.

The current implementation is more useful for users asking for project-wide references to common
types, but it can be expensive:

- `String` may select a large part of the workspace;
- common dependency methods may require attribution of many candidate files;
- the result depends on which project module contains the cursor usage.

Restricting external symbols to open files would satisfy the original performance policy but would
make Find References incomplete in a way that is surprising for a workspace operation.

### Recommended direction

Preserve project-wide correctness and improve execution rather than silently restricting results to
open files.

The preferred progression is:

1. retain candidate-index filtering;
2. search all relevant workspace modules when the target is external;
3. add cancellation propagation;
4. support LSP partial results for large result sets;
5. consider a user-visible warning only when candidate counts exceed a measured threshold.

The roadmap's open-file-only statement should not be implemented until this policy is explicitly
confirmed.

### Required measurement

Record candidate count, attributed-file count, elapsed time, and result count for representative
symbols:

- `java.lang.String`;
- `java.time.Duration`;
- one frequently used dependency type;
- one static dependency method;
- one external method reference.

---

## 4. Gap FR-003 — Failures Are Converted Into Empty Results

Status: verified error-handling gap.

The references pipeline currently has two silent-recovery boundaries:

- `SourceAnalysisSession.searchReferences()` catches `IOException`, logs it, and returns an empty
  list;
- `WorkspaceSession.referencesFuture()` catches any exceptional completion, logs it, and returns an
  empty list.

Consequently, the client cannot distinguish:

- a symbol with no references;
- a source-read failure;
- a compiler or attribution failure;
- a worker failure;
- a bug in result aggregation.

This conflicts with the fail-fast policy in
[lathe-maintainability-refactoring.md](lathe-maintainability-refactoring.md).

### Required behavior

- Lower layers preserve and propagate failures with useful URI or path context.
- Lower layers do not log and then return an empty result.
- The nearest upstream references-operation boundary logs the failure once with the `Throwable`.
- The LSP request completes exceptionally rather than reporting a successful empty result.
- Legitimate absence, including an unresolved cursor element, remains an empty result.

### Required tests

- A source-read failure completes the references request exceptionally.
- A module-worker failure reaches the upstream request boundary.
- The failure is logged once rather than at both analysis and workspace layers.
- A valid symbol with no references still returns an empty list.

---

## 5. Gap FR-004 — No End-to-End Invoker Coverage

Status: verified test gap.

The Maven invoker `LspSmokeTest` checks that `referencesProvider` is advertised but never sends a
`textDocument/references` request.

Existing server tests cover important pieces independently:

- `ReferenceCandidateIndexTest` covers token and import indexing;
- `ReferenceCandidatePlannerTest` covers explicit imports, wildcard imports, static members, and
  implicit `java.lang.String` candidates;
- `ReferenceLocatorTest` covers attributed identity matching, roles, scope classification, and
  cross-compilation matching.

No test covers the complete path:

```text
LSP request
  -> open-document lookup
  -> cursor target resolution
  -> workspace scope planning
  -> candidate selection
  -> module-worker attribution
  -> Location aggregation
  -> JSON-RPC response
  -> client receipt
```

### Required invoker cases

Extend the existing multi-module LSP smoke test rather than creating a second server launcher.

At minimum:

1. Open a project source file through `didOpen`.
2. Request references for a reactor symbol and assert same-module or cross-module locations.
3. Request references for `java.lang.String` and assert at least one project location.
4. Request references for `java.time.Duration` and assert project locations.
5. Open the cached JDK `Duration.java` returned by definition navigation.
6. Request references from its declaration and assert project locations.

The test must inspect returned URIs and ranges, not only result count.

---

## 6. Gap FR-005 — Client Response Incident Is Not Reproducibly Covered

Status: observed incident; server defect not established.

In the Helidon incident, the server log ended immediately after:

```text
[references] MongoDbClient.java element=Duration hits=2
[references] MongoDbExecute.java element=Duration hits=0
[references] MongoDbClient.java 12ms target=Duration hits=2
```

There was no server exception, fatal JVM message, shutdown record, or RPC exit record.
Lathe therefore completed reference computation successfully, but Neovim did not display the two
locations before the editor/session connection ended.

This does not establish a Lathe crash.
It establishes that the current tests stop before JSON-RPC client receipt and cannot distinguish a
server response failure from an editor-side display or process failure.

### Required investigation support

- The invoker client must await and assert the actual references response.
- A focused service test should verify that the `CompletableFuture` completes with serializable LSP
  `Location` values.
- Operation logging should retain the request URI, target, elapsed time, and final hit count.
- If another incident occurs, capture the Neovim process exit status and RPC client-exit event in
  addition to the server log.

No production fix should be attributed to this incident until it is reproduced outside the editor or
an RPC/client error is captured.

---

## 7. Implementation Order

1. Add invoker coverage for the currently working reactor and project-origin JDK cases.
2. Add a failing external-declaration invoker case.
3. Fix external-source workspace search-root selection.
4. Replace empty-result exception recovery with fail-fast propagation and single-boundary logging.
5. Measure external-symbol project-wide search cost.
6. Decide and document the final external-symbol scope policy.
7. Add cancellation and partial-result support if measurements justify it.

The external-declaration correctness fix and failure-propagation change should be independently
reviewable.
Do not combine them with candidate-index optimization.

---

## 8. Verification

Focused server verification:

```bash
mvn spotless:apply
mvn test -pl lathe-server
```

Invoker verification:

```bash
mvn verify -pl lathe-maven-plugin -Dinvoker.test=multi-module
```

Final verification must demonstrate all four request origins:

| Target origin | Symbol origin | Expected search result |
|---|---|---|
| Project source | Reactor | Exact project references |
| Project source | JDK/dependency | Exact project references |
| Cached external source | JDK | Exact project references |
| Cached external source | Dependency | Exact project references |
