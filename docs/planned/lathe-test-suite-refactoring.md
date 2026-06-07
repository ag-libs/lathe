# Lathe — Test Suite Refactoring

## Problem

The Lathe test suite has minor DRY (Don't Repeat Yourself) and KISS (Keep It Simple, Stupid) violations that introduce boilerplate and potential flakiness:
* Test classes that override `lathe.cache` system properties manually duplicate identical `@BeforeEach` and `@AfterEach` backup-and-restore blocks.
* Test-only compilers duplicate heavy `javac` compile and parse logic that is already implemented in production classes.
* Asynchronous service tests rely on hardcoded `Thread.sleep` calls, which can cause flaky builds under loaded CI environments.

## Goal

Refactor the test infrastructure to simplify test logic, eliminate code duplication, and increase test execution reliability:
* Extract system property management into a reusable JUnit 5 extension.
* Consolidate duplicate `javac` compilation pipeline execution between production and test compilers.
* Standardize on non-blocking Mockito timeouts, removing flaky hardcoded sleeps.

## Proposed Refactorings

### 1. JUnit 5 System Property Extension

Create a reusable JUnit 5 extension to automatically back up and restore system properties overridden during test execution.

* **Implementation:**
  - Define `SystemPropertyExtension` implementing JUnit 5's `BeforeEachCallback` and `AfterEachCallback`.
  - The extension detects a custom `@SystemProperty` annotation (or manages a registry of properties to clean up).
  - Register the extension in [DependencyTypeIndexSyncTest.java](file:///home/ag-libs/git/lathe/lathe-maven-plugin/src/test/java/io/github/aglibs/lathe/maven/typeindex/DependencyTypeIndexSyncTest.java) and [JdkTypeIndexSyncTest.java](file:///home/ag-libs/git/lathe/lathe-maven-plugin/src/test/java/io/github/aglibs/lathe/maven/typeindex/JdkTypeIndexSyncTest.java).
* **Benefit:** Removes ~15 lines of boilerplate setup/teardown logic from each sync test class.

### 2. Consolidated Java Compilation Execution

Standardize compiler task invocation across production and test code.

* **Implementation:**
  - Refactor [TempSourceCompiler.java](file:///home/ag-libs/git/lathe/lathe-server/src/test/java/io/github/aglibs/lathe/server/analysis/TempSourceCompiler.java) to reuse [JavacRunner.java](file:///home/ag-libs/git/lathe/lathe-server/src/main/java/io/github/aglibs/lathe/server/module/JavacRunner.java).
  - Either make `JavacRunner` package-private and place both compilers in matching packages, or introduce a shared compiler dispatcher utility under the `analysis` package.
* **Benefit:** Guarantees that production code updates to AST scanning, diagnostic collection, or token processing are automatically reflected in tests without duplicating the compilation pipeline code.

### 3. Eliminate Asynchronous Test Sleeps

Remove timing-dependent sleeps from test flows.

* **Implementation:**
  - In [LatheTextDocumentServiceTest.java](file:///home/ag-libs/git/lathe/lathe-server/src/test/java/io/github/aglibs/lathe/server/LatheTextDocumentServiceTest.java), replace `Thread.sleep(DEBOUNCE_MS * 3)` with Mockito's `verify(client, timeout(DEBOUNCE_MS * 3))` style checks.
* **Benefit:** Reduces test run times on fast hardware and prevents false-negatives/flakiness on slow or resource-constrained CI machines.

### 4. Shared Zip Helpers

Move zip fixture generation logic to a place accessible by both `lathe-core` tests and other modules.

* **Implementation:**
  - Relocate the zip-writing utility from [ZipFixture.java](file:///home/ag-libs/git/lathe/lathe-maven-plugin/src/test/java/io/github/aglibs/lathe/maven/ZipFixture.java) to the test sources of `lathe-core` if compile boundaries permit, or create a common test-utility module.
* **Benefit:** Avoids duplicate Zip stream writing implementation inside [FileUtilTest.java](file:///home/ag-libs/git/lathe/lathe-core/src/test/java/io/github/aglibs/lathe/core/FileUtilTest.java).
