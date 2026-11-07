package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.launch.ReactorRewrite;
import io.github.aglibs.lathe.core.schema.AnalysisMode;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public record ModuleSourceConfig(
    Path moduleDir,
    String sourceTree,
    Path outputDir,
    Path originalGenSourcesDir,
    List<Path> sourceRoots,
    List<Path> classpath,
    List<Path> modulepath,
    List<Path> processorPath,
    String release,
    String encoding,
    boolean parameters,
    boolean enablePreview,
    String proc,
    List<String> compilerArgs,
    AnalysisMode analysisMode,
    // True when outputDir is the build's own compiled output read directly (OpenJDK), not a .lathe
    // mirror (Maven). Drives raw-classpath resolution and the type-index base.
    boolean externalOutput,
    // Whether the tree is test code, as its params say; absent (a test's hand-built config), the
    // test-classes tree is.
    Boolean testCode,
    // Maps sibling modules' build outputs on the paths above to their .lathe/ mirrors.
    ReactorRewrite reactorRewrite) {

  public ModuleSourceConfig {
    // Older maven-compiler-plugin versions list their processor output among the source locations;
    // the generated root is always the .lathe mirror (generatedSourcesDir), never Maven's copy.
    sourceRoots =
        sourceRoots == null
            ? List.of()
            : sourceRoots.stream()
                .filter(
                    root ->
                        originalGenSourcesDir == null || !root.startsWith(originalGenSourcesDir))
                .toList();
    classpath = classpath != null ? List.copyOf(classpath) : List.of();
    modulepath = modulepath != null ? List.copyOf(modulepath) : List.of();
    processorPath = processorPath != null ? List.copyOf(processorPath) : List.of();
    compilerArgs = compilerArgs != null ? List.copyOf(compilerArgs) : List.of();
    analysisMode = analysisMode != null ? analysisMode : AnalysisMode.CLASSPATH;
    reactorRewrite = reactorRewrite != null ? reactorRewrite : ReactorRewrite.none();
    testCode =
        Objects.requireNonNullElse(testCode, LatheLayout.TEST_CLASSES_DIR.equals(sourceTree));
  }

  public Path latheClassesDir() {
    return moduleDir.resolve(sourceTree);
  }

  public Path generatedSourcesDir() {
    return moduleDir.resolve(LatheLayout.generatedSourcesDir(sourceTree));
  }

  // The source root holding the file: the deepest when roots nest, empty for a file under none.
  public Optional<Path> sourceRootOf(final Path file) {
    return sourceRoots.stream()
        .filter(file::startsWith)
        .max(Comparator.comparingInt(Path::getNameCount));
  }

  // The build's annotation-processor output, or Lathe's mirror of it: sources the processors
  // rewrite from the hand-written ones.
  public boolean isGenerated(final Path file) {
    return file.startsWith(generatedSourcesDir())
        || (originalGenSourcesDir != null && file.startsWith(originalGenSourcesDir));
  }

  // Source roots plus the .lathe generated-sources mirror (when the module has generated sources).
  // Definition/reference resolution searches these, so a generated type resolves to the fresh copy
  // Lathe regenerates on save — not the stale Maven target/ copy.
  public List<Path> searchRoots() {
    if (originalGenSourcesDir == null) {
      return sourceRoots;
    }

    return Stream.concat(sourceRoots.stream(), Stream.of(generatedSourcesDir())).toList();
  }

  // Whether this tree compiles against the other: its mirror on this tree's class or module path,
  // as a test tree holds its own main tree, and a Gradle source set the trees its classpath names.
  public boolean sees(final ModuleSourceConfig other) {
    return compiledAgainst().contains(other.latheClassesDir());
  }

  // Every entry of this tree's class and module path, mirrors standing in for build outputs: a set,
  // for a caller asking about many trees at once to compute once.
  public Set<Path> compiledAgainst() {
    return Stream.concat(remappedClasspath().stream(), remappedModulepath().stream())
        .collect(Collectors.toUnmodifiableSet());
  }

  public boolean isTestTree() {
    return testCode;
  }

  public List<Path> remappedClasspath() {
    return remapped(classpath);
  }

  public List<Path> remappedModulepath() {
    return remapped(modulepath);
  }

  public List<Path> remappedProcessorPath() {
    return remapped(processorPath);
  }

  private List<Path> remapped(final List<Path> paths) {
    return paths.stream().map(reactorRewrite::toLathe).toList();
  }

  public static ModuleSourceConfig load(
      final Path paramsFile, final Path moduleDir, final ReactorRewrite reactorRewrite)
      throws IOException {
    final var config = Json.read(paramsFile, ModuleConfigData.class);
    return new ModuleSourceConfig(
        moduleDir,
        config.sourceTree(),
        Path.of(config.outputDir()),
        config.generatedSourcesDir() != null ? Path.of(config.generatedSourcesDir()) : null,
        toPaths(config.sourceRoots()),
        toPaths(config.classpath()),
        toPaths(config.modulepath()),
        toPaths(config.processorPath()),
        config.release(),
        config.encoding(),
        config.parameters(),
        config.enablePreview(),
        config.proc(),
        config.compilerArgs(),
        config.analysisMode(),
        config.externalOutput(),
        config.testCode(),
        reactorRewrite);
  }

  private static List<Path> toPaths(final List<String> strings) {
    return strings != null ? strings.stream().map(Path::of).toList() : List.of();
  }
}
