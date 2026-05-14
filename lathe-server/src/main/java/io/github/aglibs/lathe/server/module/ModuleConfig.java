package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public record ModuleConfig(
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
    final var sourceTree = rel.getFileName();
    final var moduleRel = rel.getParent().getParent();
    return moduleRel != null
        ? latheDir.resolve(moduleRel).resolve(sourceTree)
        : latheDir.resolve(sourceTree);
  }

  public static ModuleConfig load(final Path paramsFile, final Path moduleDir) throws IOException {
    final var config = Json.read(paramsFile, ModuleConfigData.class);
    return new ModuleConfig(
        moduleDir,
        config.sourceTree(),
        Path.of(config.outputDir()),
        config.generatedSourcesDir() != null ? Path.of(config.generatedSourcesDir()) : null,
        toPaths(config.sourceRoots()),
        toPaths(config.classpath()),
        toPaths(config.modulepath()),
        toPaths(config.processorPath()),
        config.release(),
        config.encoding() != null ? config.encoding() : "UTF-8",
        config.parameters(),
        config.enablePreview(),
        config.proc(),
        config.compilerArgs() != null ? config.compilerArgs() : List.of());
  }

  private static List<Path> toPaths(final List<String> strings) {
    return strings != null ? strings.stream().map(Path::of).toList() : List.of();
  }
}
