package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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
    List<String> compilerArgs) {

  public Path latheClassesDir() {
    return moduleDir.resolve(sourceTree);
  }

  public Path generatedSourcesDir() {
    return moduleDir.resolve(LatheLayout.GENERATED_SOURCES);
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
    final var latheDir = latheDir();
    final var workspaceRoot = latheDir.getParent();
    return paths.stream().map(p -> remapPath(p, workspaceRoot, latheDir)).toList();
  }

  private Path latheDir() {
    var p = moduleDir;
    while (p != null && !LatheLayout.LATHE_DIR.equals(p.getFileName().toString())) {
      p = p.getParent();
    }
    return p;
  }

  public static Path remapPath(final Path p, final Path workspaceRoot, final Path latheDir) {
    if (!p.startsWith(workspaceRoot)) {
      return p;
    }

    final var rel = workspaceRoot.relativize(p);
    if (rel.getParent() == null) {
      return p;
    }

    final var sourceTree = rel.getFileName().toString();
    final var moduleRel = rel.getParent().getParent();
    // Reactor module JARs (e.g. target/foo-1.0.jar) appear when Maven reuses a previously
    // packaged artifact instead of target/classes. Inter-module dependencies are always main
    // artifacts — test JARs never appear on another module's path. Remap to the .lathe/classes
    // mirror, which the shim keeps current, so the server always sees the latest compiled state.
    final var latheSourceTree = sourceTree.endsWith(".jar") ? "classes" : sourceTree;
    return moduleRel != null
        ? latheDir.resolve(moduleRel).resolve(latheSourceTree)
        : latheDir.resolve(latheSourceTree);
  }

  public static ModuleSourceConfig load(final Path paramsFile, final Path moduleDir)
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
        config.compilerArgs());
  }

  private static List<Path> toPaths(final List<String> strings) {
    return strings != null ? strings.stream().map(Path::of).toList() : List.of();
  }
}
