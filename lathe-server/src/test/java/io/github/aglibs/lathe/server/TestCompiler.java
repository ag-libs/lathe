package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.analysis.testing.TestJavac;
import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.launch.ReactorRewrite;
import io.github.aglibs.lathe.core.schema.AnalysisMode;
import io.github.aglibs.lathe.core.schema.BuildFileData;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.core.schema.SourceStamp;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class TestCompiler {

  // Fixtures compile at the test JDK's own language level, so the suite runs on every supported
  // JDK.
  public static final String RELEASE = Integer.toString(Runtime.version().feature());

  private TestCompiler() {}

  public static ModuleSourceConfig moduleConfig(final Path workspaceRoot, final Path sourceRoot) {
    return moduleConfig(
        workspaceRoot.resolve(".lathe/module"),
        workspaceRoot.resolve("target/classes"),
        sourceRoot);
  }

  // A module's main tree under the workspace root: <root>/<module>/src/main/java, compiled to
  // target/classes.
  public static ModuleSourceConfig mainTreeConfig(final Path workspaceRoot, final String module) {
    return moduleConfig(
        workspaceRoot.resolve(".lathe/%s".formatted(module)),
        workspaceRoot.resolve("%s/target/classes".formatted(module)),
        workspaceRoot.resolve("%s/src/main/java".formatted(module)));
  }

  // A module's test tree under the workspace root: <root>/<module>/<relRoot>, compiled to
  // test-classes.
  public static ModuleSourceConfig testTreeConfig(
      final Path workspaceRoot, final String module, final String relRoot) {
    return moduleConfig(
        workspaceRoot.resolve(".lathe/%s".formatted(module)),
        workspaceRoot.resolve("%s/target/test-classes".formatted(module)),
        workspaceRoot.resolve("%s/%s".formatted(module, relRoot)),
        null,
        LatheLayout.TEST_CLASSES_DIR);
  }

  public static ModuleSourceConfig moduleConfig(
      final Path moduleDir, final Path outputDir, final Path sourceRoot) {
    return moduleConfig(moduleDir, outputDir, sourceRoot, null);
  }

  public static ModuleSourceConfig moduleConfig(
      final Path moduleDir,
      final Path outputDir,
      final Path sourceRoot,
      final Path generatedSourcesDir) {
    return moduleConfig(
        moduleDir, outputDir, sourceRoot, generatedSourcesDir, LatheLayout.CLASSES_DIR);
  }

  public static ModuleSourceConfig moduleConfig(
      final Path moduleDir,
      final Path outputDir,
      final Path sourceRoot,
      final Path generatedSourcesDir,
      final String sourceTree) {
    return new ModuleSourceConfig(
        moduleDir,
        sourceTree,
        outputDir,
        generatedSourcesDir,
        List.of(sourceRoot),
        List.of(),
        List.of(),
        List.of(),
        RELEASE,
        "UTF-8",
        false,
        false,
        null,
        List.of(),
        null,
        false,
        ReactorRewrite.none());
  }

  // MODULE_SYSTEM analysis: blank release (so patching a system module is not rejected), no
  // class/module path (deps come from the host JDK), and the --patch-module overlay the server
  // repoints at its temp dir.
  public static ModuleSourceConfig moduleSystemConfig(
      final Path moduleDir, final Path sourceRoot, final String patchedModule) {
    return new ModuleSourceConfig(
        moduleDir,
        LatheLayout.CLASSES_DIR,
        moduleDir.resolve(LatheLayout.CLASSES_DIR),
        null,
        List.of(sourceRoot),
        List.of(),
        List.of(),
        List.of(),
        "",
        "UTF-8",
        false,
        false,
        null,
        List.of("--patch-module", "%s=.".formatted(patchedModule)),
        AnalysisMode.MODULE_SYSTEM,
        true,
        ReactorRewrite.none());
  }

  public static Path writeAt(final Path path, final String content, final long mtimeMillis)
      throws IOException {
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
    Files.setLastModifiedTime(path, FileTime.fromMillis(mtimeMillis));
    return path;
  }

  // A source's stamp as a build writes it: its mtime and the content compiled.
  public static SourceStamp buildStamp(final Path source) throws IOException {
    return new SourceStamp(
        Files.getLastModifiedTime(source).toMillis(), FileUtil.fingerprint(source));
  }

  // The tree's stamped mtimes, to check what a write stamped.
  public static Map<Path, Long> stampedMtimes(final Path moduleDir, final String sourceTree) {
    return CompiledStamps.load(moduleDir, sourceTree).entrySet().stream()
        .collect(
            Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().mtime()));
  }

  /**
   * Writes a module whose sources (root-relative path to content) are already compiled into its
   * mirror and stamped at {@code mtimeMillis}, so the workspace starts fresh. {@code dependencies}
   * name upstream modules written the same way; they go on the classpath as Maven would list them.
   * Returns the source paths in the given order.
   */
  public static List<Path> writeMirroredModule(
      final Path workspaceRoot,
      final String moduleName,
      final Map<String, String> sources,
      final List<String> dependencies,
      final long mtimeMillis)
      throws IOException {
    final Path sourceRoot = workspaceRoot.resolve(moduleName).resolve("src/main/java");
    final var paths = new ArrayList<Path>();
    for (final var source : sources.entrySet()) {
      paths.add(writeAt(sourceRoot.resolve(source.getKey()), source.getValue(), mtimeMillis));
    }

    final Path latheModuleDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(moduleName);
    final List<Path> mirrors =
        dependencies.stream()
            .map(
                dep -> workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(dep).resolve("classes"))
            .toList();
    TestJavac.compileToDir(latheModuleDir.resolve("classes"), mirrors, paths.toArray(Path[]::new));
    final Map<Path, SourceStamp> stamps =
        sources.keySet().stream()
            .map(Path::of)
            .collect(
                Collectors.toUnmodifiableMap(
                    rel -> rel,
                    rel -> IOUtil.unchecked(() -> buildStamp(sourceRoot.resolve(rel)))));
    CompiledStamps.writeAll(
        latheModuleDir,
        "classes",
        stamps,
        stamps.entrySet().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> entry.getValue().content())));
    writeModuleParams(
        workspaceRoot,
        moduleName,
        sourceRoot,
        null,
        dependencies.stream()
            .map(dep -> workspaceRoot.resolve(dep).resolve("target/classes"))
            .toList());
    recordOutput(workspaceRoot, moduleName);
    return List.copyOf(paths);
  }

  // The output map a Maven front-end writes for modules with the standard target/ trees.
  public static List<OutputData> targetOutputs(final String... modules) {
    return Arrays.stream(modules)
        .flatMap(
            module ->
                Stream.of(LatheLayout.CLASSES_DIR, LatheLayout.TEST_CLASSES_DIR)
                    .map(
                        tree ->
                            new OutputData("%s/target/%s".formatted(module, tree), module, tree)))
        .toList();
  }

  public static ReactorRewrite targetRewrite(final Path workspaceRoot, final String... modules) {
    return ReactorRewrite.of(workspaceRoot, targetOutputs(modules));
  }

  // Adds the module's outputs to workspace.json's output map, as a front-end's sync would, so a
  // dependent's classpath entry for it resolves to the mirror.
  private static void recordOutput(final Path workspaceRoot, final String moduleName)
      throws IOException {
    rewriteManifest(
        workspaceRoot,
        existing ->
            Stream.concat(existing.outputs().stream(), targetOutputs(moduleName).stream()).toList(),
        WorkspaceManifestData::buildFiles);
  }

  // Records the build files in workspace.json as a sync that just read them does, so a later edit
  // to one reads as a build-file change.
  public static void recordBuildFiles(final Path workspaceRoot, final List<Path> files)
      throws IOException {
    final List<BuildFileData> synced = new ArrayList<>();
    for (final Path file : files) {
      synced.add(
          new BuildFileData(
              FileUtil.slashed(workspaceRoot.relativize(file)), FileUtil.fingerprint(file)));
    }

    rewriteManifest(workspaceRoot, WorkspaceManifestData::outputs, existing -> synced);
  }

  private static void rewriteManifest(
      final Path workspaceRoot,
      final Function<WorkspaceManifestData, List<OutputData>> outputs,
      final Function<WorkspaceManifestData, List<BuildFileData>> buildFiles)
      throws IOException {
    final Path manifestFile =
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON);
    final WorkspaceManifestData existing =
        Files.exists(manifestFile)
            ? Json.read(manifestFile, WorkspaceManifestData.class)
            : new WorkspaceManifestData(
                LatheLayout.SCHEMA_VERSION,
                workspaceRoot.toString(),
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);
    Files.createDirectories(manifestFile.getParent());
    Json.write(
        new WorkspaceManifestData(
            existing.schemaVersion(),
            existing.workspaceRoot(),
            existing.serverVersion(),
            existing.runnerClasspath(),
            existing.jdk(),
            existing.dependencySources(),
            buildFiles.apply(existing),
            existing.resourceRoots(),
            outputs.apply(existing),
            existing.buildTool()),
        manifestFile);
  }

  public static void writeModuleParams(
      final Path workspaceRoot,
      final String moduleName,
      final Path sourceRoot,
      final Path generatedSourcesDir)
      throws IOException {
    writeModuleParams(workspaceRoot, moduleName, sourceRoot, generatedSourcesDir, List.of());
  }

  // The classpath may name another module's target/classes, which the server maps to its mirror.
  public static void writeModuleParams(
      final Path workspaceRoot,
      final String moduleName,
      final Path sourceRoot,
      final Path generatedSourcesDir,
      final List<Path> classpath)
      throws IOException {
    writeModuleParams(
        workspaceRoot,
        moduleName,
        List.of(sourceRoot),
        generatedSourcesDir,
        classpath,
        List.of(),
        List.of());
  }

  public static void writeModuleParams(
      final Path workspaceRoot,
      final String moduleName,
      final List<Path> sourceRoots,
      final Path generatedSourcesDir,
      final List<Path> classpath,
      final List<Path> processorPath,
      final List<String> compilerArgs)
      throws IOException {
    final var latheModuleDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(moduleName);
    Files.createDirectories(latheModuleDir);
    final var config =
        new ModuleConfigData(
            "classes",
            latheModuleDir.resolve("classes").toString(),
            generatedSourcesDir != null ? generatedSourcesDir.toString() : null,
            sourceRoots.stream().map(Path::toString).toList(),
            classpath.stream().map(Path::toString).toList(),
            List.of(),
            processorPath.stream().map(Path::toString).toList(),
            RELEASE,
            "UTF-8",
            false,
            false,
            null,
            compilerArgs,
            null,
            false);
    final Path paramsFile = latheModuleDir.resolve(LatheLayout.paramsFileName("classes"));
    Json.write(config, paramsFile);
  }

  /**
   * Writes {@code content} to {@code relativePath} under a single {@code module}'s main source root
   * and registers the module. Returns the source path.
   */
  public static Path writeModuleSource(
      final Path workspaceRoot, final String relativePath, final String content)
      throws IOException {
    final Path sourceRoot = workspaceRoot.resolve("module").resolve("src/main/java");
    final Path source = sourceRoot.resolve(relativePath);
    Files.createDirectories(source.getParent());
    Files.writeString(source, content);
    writeModuleParams(workspaceRoot, "module", sourceRoot, null);
    return source;
  }
}
