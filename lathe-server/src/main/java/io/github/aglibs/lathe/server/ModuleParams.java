package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.ParamStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

record ModuleParams(
    Path latheModuleDir,
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

  Path latheClassesDir() {
    return latheModuleDir.resolve(sourceTree);
  }

  Path generatedSourcesDir() {
    return latheModuleDir.resolve(LatheLayout.GENERATED_SOURCES);
  }

  List<Path> remappedClasspath() {
    return remapped(classpath);
  }

  List<Path> remappedModulepath() {
    return remapped(modulepath);
  }

  List<Path> remappedProcessorPath() {
    return remapped(processorPath);
  }

  private List<Path> remapped(final List<Path> paths) {
    final var latheDir = latheDir();
    final var workspaceRoot = latheDir.getParent();
    return paths.stream().map(p -> remapPath(p, workspaceRoot, latheDir)).toList();
  }

  private Path latheDir() {
    var p = latheModuleDir;
    while (p != null && !LatheLayout.LATHE_DIR.equals(p.getFileName().toString())) {
      p = p.getParent();
    }
    return p;
  }

  static Path remapPath(final Path p, final Path workspaceRoot, final Path latheDir) {
    if (!p.startsWith(workspaceRoot)) {
      return p;
    }
    final var rel = workspaceRoot.relativize(p);
    // Must be at least <buildDir>/<sourceTree>
    if (rel.getParent() == null) {
      return p;
    }
    final var sourceTree = rel.getFileName();
    final var moduleRel = rel.getParent().getParent(); // null when module is workspace root
    return moduleRel != null
        ? latheDir.resolve(moduleRel).resolve(sourceTree)
        : latheDir.resolve(sourceTree);
  }

  static ModuleParams load(final Path paramsFile, final Path latheModuleDir) throws IOException {
    final var store = ParamStore.load(paramsFile);
    final var genSourcesDir = store.get("generatedSourcesDir");
    return new ModuleParams(
        latheModuleDir,
        store.get("sourceTree"),
        Path.of(store.get("outputDir")),
        genSourcesDir != null ? Path.of(genSourcesDir) : null,
        store.readList("sourceRoots").stream().map(Path::of).toList(),
        store.readList("classpath").stream().map(Path::of).toList(),
        store.readList("modulepath").stream().map(Path::of).toList(),
        store.readList("processorPath").stream().map(Path::of).toList(),
        store.get("release"),
        store.get("encoding") != null ? store.get("encoding") : "UTF-8",
        Boolean.parseBoolean(store.get("parameters")),
        Boolean.parseBoolean(store.get("enablePreview")),
        store.get("proc"),
        store.readList("compilerArgs"));
  }
}
