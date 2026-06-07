# Lathe — Design Opinion & Code Evaluation

This document provides a professional, honest critique of Lathe's architectural design followed by an evaluation of the current state of its codebase under the lens of **KISS** (Keep It Simple, Stupid) and **DRY** (Don't Repeat Yourself) principles.

---

## 1. Honest Opinion on the Design

### 🚀 Core Strengths & Architectural Advantages

1. **Source of Truth Delegation (Build-System Agnostic Shim)**
   Traditional Java language servers (e.g., Eclipse JDT LS) must reimplement Maven's core lifecycle, dependency resolution, annotation processing configs, and JPMS layouts. This makes them fragile and prone to drift.
   > [!NOTE]
   > Lathe's design of using a **Plexus compiler shim** (`lathe-compiler`) to capture compilation parameters and delegates straight to `javac` is a masterstroke. It makes Lathe *correct by construction* for any project configuration that Maven can successfully compile.

2. **KISS Memory & State Model**
   By choosing to hold no long-lived cross-module `javac` symbol cache and rebuilding `JavacTask` on each pass, Lathe avoids a massive class of memory leak and cache staleness bugs. The target performance budget (500ms p95 for `< 500` LOC files) is realistic for everyday development.

3. **Confined Threading Model**
   All mutable server state is restricted to a single event thread (`lathe-worker`). Workers (`ModuleSourceWorker`) manage their own single-threaded `SourceAnalysisSession` thread. This prevents race conditions, lock contention, and eliminates the need for synchronization blocks.

---

### ⚠️ Risks & Potential Limitations

1. **Single-File Compilation Boundary Gap**
   Compiling only the open file and relying on `.class` files in `.lathe` for closed sibling files means that if a user updates an interface method signature in an open file, closed callers in the same module will show compile diagnostics only *after* those files are opened or a Maven sync/save compile runs. This is a design trade-off for speed, but could puzzle developers accustomed to IDEs with global incremental builders.

2. **Workspace Watcher Overhead**
   The `WorkspaceWatcher` walks `.lathe/` every 2 seconds. In multi-module reactors with thousands of class files or generated sources, this walk could introduce CPU or I/O spikes, especially on spinning disks or heavily loaded environments.
   > [!TIP]
   > Moving to JDK's `WatchService` or native file system event notification libraries would be a future-proof improvement.

3. **No Lombok Support**
   The omission of Lombok is documented, but will act as a barrier to adoption for traditional enterprise Java applications. However, supporting Lombok's AST transformations would severely compromise Lathe's clean, standard-conforming javac architecture.

---

## 2. Codebase Evaluation (KISS & DRY)

Overall, the codebase is remarkably clean, conforming strictly to the rules defined in `AGENTS.md` (e.g., proper use of `final` modifiers, functional streams, clean separation of JPMS modules). However, a few areas stand out for potential improvement:

### 🔄 DRY (Don't Repeat Yourself) Opportunities

#### 1. File Copy & Temp Writing in Compilers
Both [ModuleSourceCompiler.java](file:///home/ag-libs/git/lathe/lathe-server/src/main/java/io/github/aglibs/lathe/server/module/ModuleSourceCompiler.java) and [ExternalCompiler.java](file:///home/ag-libs/git/lathe/lathe-server/src/main/java/io/github/aglibs/lathe/server/module/ExternalCompiler.java) replicate the logic of writing the document's current content to a temporary workspace directory structure so it can be compiled by standard `javac`.

* **Duplicate Pattern in `ModuleSourceCompiler`:**
  ```java
  final var tempFile = td.resolve(sourceRoot.relativize(filePath));
  try {
    Files.createDirectories(tempFile.getParent());
    Files.writeString(tempFile, content);
    ...
  ```
* **Duplicate Pattern in `ExternalCompiler`:**
  ```java
  final var rel = sourceRoot.get().relativize(filePath);
  final var tempFile = td.resolve(rel);
  try {
    Files.createDirectories(tempFile.getParent());
    Files.writeString(tempFile, content);
    ...
  ```
* **Recommendation:** Extract a common helper method (e.g., `writeTempSourceFile(Path tempDir, Path sourceRoot, Path filePath, String content)`) in [JavaSourceCompiler.java](file:///home/ag-libs/git/lathe/lathe-server/src/main/java/io/github/aglibs/lathe/server/analysis/JavaSourceCompiler.java) or a shared core utility class.

#### 2. Redundant Classpath/Modulepath Path-to-File Mappings
Across the compiler modules, lists of `Path` objects are frequently transformed to `File` objects before passing to `StandardJavaFileManager.setLocation()`.
```java
fm.setLocation(StandardLocation.CLASS_PATH, classpath.stream().map(Path::toFile).toList());
fm.setLocation(StandardLocation.MODULE_PATH, modulepath.stream().map(Path::toFile).toList());
```
* **Recommendation:** Define a helper method `fm.setLocation(Location, Collection<Path>)` or a utility helper `toFileList(List<Path>)` to reduce boilerplate.

#### 3. Exact Duplicate Exception Handler Helper
Both [ModuleSourceWorker.java](file:///home/ag-libs/git/lathe/lathe-server/src/main/java/io/github/aglibs/lathe/server/module/ModuleSourceWorker.java) and [ServerWorker.java](file:///home/ag-libs/git/lathe/lathe-server/src/main/java/io/github/aglibs/lathe/server/ServerWorker.java) declare an identical helper:
```java
private static void rethrowError(final Throwable t) {
  if (t instanceof final Error error) {
    throw error;
  }
}
```
* **Recommendation:** Move this to a central shared utility class like [IOUtil.java](file:///home/ag-libs/git/lathe/lathe-core/src/main/java/io/github/aglibs/lathe/core/IOUtil.java) or [FileUtil.java](file:///home/ag-libs/git/lathe/lathe-core/src/main/java/io/github/aglibs/lathe/core/FileUtil.java).

---

### 💡 KISS (Keep It Simple, Stupid) Improvements

#### 1. Sentinel Insertion and AST Processing
The [SentinelInjector.java](file:///home/ag-libs/git/lathe/lathe-server/src/main/java/io/github/aglibs/lathe/server/analysis/completion/SentinelInjector.java) parses characters backward and forward to insert the sentinel symbol (`__LATHE_SENTINEL__`).
While it is fast, the complexity of stateful manual scanning (`inLineComment`, `inBlockComment`, `inString`, etc.) is high. It handles quotes, escape sequences, parentheses depth, and comments manually.
* **Critique:** While this manually crafted scanner is very fast, it introduces minor maintenance overhead as Java syntax additions arrive (e.g., raw string literals, newer switch formats). However, keeping it lightweight is preferable to parsing the entire file using a full parser. It strikes a good balance for KISS given the scope.

#### 2. Synchronization-Free Event Loop
The `ServerWorker` wraps a single-threaded executor:
```java
private final ScheduledExecutorService executor =
    Executors.newSingleThreadScheduledExecutor(...);
```
This is a textbook example of KISS thread confinement. It completely avoids lock management, complex concurrency primitives, and thread safety bugs.

---

## 3. Conclusion

The Lathe project's design is **highly opinionated, clean, and pragmatic**. It leverages the standard `javac` toolchain to avoid reproducing complex build-tool internals. The codebase feels compact, has great test coverage, and respects structural clarity.

The few DRY violations and potential boilerplate code listed above do not significantly compromise the system, but addressing them would further streamline future development.
