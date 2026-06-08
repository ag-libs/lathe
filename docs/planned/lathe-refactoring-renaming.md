# Lathe — Refactoring & Renaming Plan

This document outlines planned improvements to the Lathe codebase and test suites, focusing on DRY (Don't Repeat Yourself) and KISS (Keep It Simple, Stupid) principles, ease of reading and maintaining code, conceptual naming, and style alignment.

## 1. Codebase Refactorings (DRY & KISS)

### Temporary Source File Writing
Both `ModuleSourceCompiler.java` and `ExternalCompiler.java` replicate the logic of writing the document's content to a temporary workspace directory structure.
Extract a common static or default utility method in `JavaSourceCompiler.java` or `FileUtil.java`:
```java
public static Path writeTempSourceFile(final Path tempDir, final Path sourceRoot, final Path filePath, final String content) throws IOException {
  final Path tempFile = tempDir.resolve(sourceRoot.relativize(filePath));
  Files.createDirectories(tempFile.getParent());
  Files.writeString(tempFile, content);
  return tempFile;
}
```

### Redundant Path-to-File Mappings for FileManager Locations
In both compiler implementations, lists of `Path` are mapped to `File` objects before passing to `StandardJavaFileManager.setLocation()`.
Since Java 9, `StandardJavaFileManager` provides direct `Path` binding via `setLocationFromPaths`.
All such maps can be simplified to:
```java
fm.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
fm.setLocationFromPaths(StandardLocation.MODULE_PATH, modulepath);
```

### Duplicate Exception Handler
`ServerWorker.java` and `ModuleSourceWorker.java` implement the identical `rethrowError` helper.
Move this to `IOUtil.java` in `lathe-core` as a public helper `rethrowIfError`.

### Duplicate Parameter File Predicate
`WorkspaceWatcher.java` and `WorkspaceModules.java` both implement identical `isParamsFile` matching.
Move this to `LatheLayout.java` in `lathe-core` as `public static boolean isParamsFile(final Path path)`.

### Restricting Directory Walks
`WorkspaceModules.scan()` scans `.lathe/` recursively using `Files.walk(latheDir)`.
For large codebases with thousands of compiled classes and generated sources, this walk is highly inefficient.
Since parameters files are always located exactly two levels deep from the workspace root (e.g. `.lathe/module-a/lsp-params-classes.json`), limit the depth to `2`:
```java
try (final var stream = Files.walk(latheDir, 2)) { ... }
```

### Overload Match Integrity in Declaration Locating
`SourceLocator.declarationPath` matches class/method/variable declarations based solely on parameter count.
Verify parameter types or type signatures where overloaded methods exist in the same file to prevent definition matching collisions.

---

## 2. Test Suite Refactorings (DRY & KISS)

### Shared `ModuleSourceConfig` Fixture Boilerplate
Test classes such as `ReferenceCandidateIndexTest`, `ReferenceCandidatePlannerTest`, `WorkspaceSessionTest`, `ModuleSourceCompilerTest`, and `WorkspaceModuleGraphTest` duplicate the instantiation of `ModuleSourceConfig` with placeholder default arguments.
Extract a default factory method (e.g., `defaultModuleConfig(Path root, Path sourceRoot)`) into `TestCompiler` or a dedicated test fixture utility class to consolidate config creation.

### Test Package & Module Co-Location Inconsistencies
* **`SyncMojoTest` Location:**
  `SyncMojoTest` in `lathe-maven-plugin` actually tests `LatheFlags` (which is located in `lathe-core`).
  Move this test to `lathe-core` and rename it to `LatheFlagsTest` to align test co-location with target modules.
* **Package Structure Mismatch:**
  Test classes like `DependencySourceTest`, `DependencySourceSyncTest`, and `JdkSourceResolverTest` are located directly in the root package `io.github.aglibs.lathe.maven` instead of matching their source packages (`io.github.aglibs.lathe.maven.dependency` and `io.github.aglibs.lathe.maven.jdk`).
  Relocate these tests to their corresponding subpackages.

---

## 3. Coding Style & Rule Adherence

### Strict `var` Usage Compliance
Ensure all uses of `var` comply with the project rules.
Specifically:
* `var` is allowed only when the type is obvious from the right-hand side (e.g. constructors like `new HashMap<>()` or clear factory methods like `Path.of()` or `List.of()`).
* Explicit types **must always be used** when assigning the results of chained API methods (such as stream pipelines, fluent builder chains, or multi-method calls), as tracking the final type through a chain is difficult to read in raw diffs.
* Explicit types should also be used when the type is not explicitly named on the RHS or when variable names are generic.

### Test Method Naming Style (Underscores)
The coding guidelines require test methods to follow the `methodName_condition_result` pattern with underscores.
Rename all test methods in the following classes that violate this rule:
* **`FileUtilTest.java`**: e.g., `unzipExtractsNestedFiles` -> `unzip_nestedFiles_extracts`
* **`IOUtilTest.java`**: e.g., `uncheckedSupplierReturnsValue` -> `unchecked_supplier_returnsValue`
* **`FormattingTest.java`**: e.g., `alreadyFormattedProducesNoEdit` -> `format_alreadyFormattedSource_returnsNoEdits`
* **`JavadocLocatorTest.java`**: e.g., `crossFileJavadoc` -> `hover_crossFileJavadoc_returnsDoc`
* **`DefinitionLocatorTest.java`**: e.g., `typeReference` -> `definition_typeReference_locatesName`
* **`MultiModuleTest.java`** (Integration tests): e.g., `latheDirectoryCreated` -> `sync_run_createsLatheDirectory`

---

## 4. Renaming & Conceptual Refactorings

The following renamings are planned for the `v1.0.0` release:

* **`WorkspaceModules` → `WorkspaceModuleRegistry`**:
  More accurately describes its role as a registry managing reactor module configurations and their associated workers.
* **`ModuleSourceWorker` → `CompilationWorker`**:
  Avoids confusion as the worker also compiles external dependency/JDK files via `ModuleSourceWorker.external()`.
* **`ServerWorker` → `ServerEventLoop`**:
  Better matches its behavior as the single-threaded event loop worker for all workspace edits and compilation triggers.
* **`SentinelResult` → `SentinelInjectionResult`**:
  Disambiguates the plain-text/offset output of `SentinelInjector` from the compiler-level `ParsedSentinel` AST context.
