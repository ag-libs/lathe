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
import io.github.aglibs.lathe.core.schema.DependencyData;
import io.github.aglibs.lathe.core.schema.JdkSourceData;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.core.schema.SourceStamp;
import io.github.aglibs.lathe.core.schema.SourceStatus;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import io.github.aglibs.lathe.core.typeindex.DependencyTypeIndexOrigin;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeIndexFile;
import io.github.aglibs.lathe.core.typeindex.TypeIndexOrigin;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
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

  // A Gradle source set's tree beyond main and test: <root>/<module>/src/<name>/java, compiled to
  // build/classes/java/<name>, test code or not as its params say.
  public static ModuleSourceConfig sourceSetTreeConfig(
      final Path workspaceRoot,
      final String module,
      final String sourceSet,
      final boolean testCode) {
    final ModuleSourceConfig config =
        moduleConfig(
            workspaceRoot.resolve(".lathe/%s".formatted(module)),
            workspaceRoot.resolve("%s/build/classes/java/%s".formatted(module, sourceSet)),
            workspaceRoot.resolve("%s/src/%s/java".formatted(module, sourceSet)),
            null,
            LatheLayout.sourceSetTree(sourceSet));
    return new ModuleSourceConfig(
        config.moduleDir(),
        config.sourceTree(),
        config.outputDir(),
        config.originalGenSourcesDir(),
        config.sourceRoots(),
        config.classpath(),
        config.modulepath(),
        config.processorPath(),
        config.release(),
        config.encoding(),
        config.parameters(),
        config.enablePreview(),
        config.proc(),
        config.compilerArgs(),
        config.analysisMode(),
        config.externalOutput(),
        testCode,
        config.reactorRewrite(),
        config.buildDir());
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
        null,
        ReactorRewrite.none(),
        null);
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
        null,
        ReactorRewrite.none(),
        null);
  }

  // Compiles, under root, a processor that warns "noted by processor" on every root element but one
  // named Quiet*. Analysis runs without annotation processors, so only a write sees it.
  public static Path noteProcessor(final Path root) throws IOException {
    final Path processorSource =
        TestCompiler.writeAt(
            root.resolve("processor-src/proc/NoteProcessor.java"),
            """
            package proc;
            import java.util.Set;
            import javax.annotation.processing.*;
            import javax.lang.model.SourceVersion;
            import javax.lang.model.element.TypeElement;
            import javax.tools.Diagnostic;
            @SupportedAnnotationTypes("*")
            public class NoteProcessor extends AbstractProcessor {
              @Override public SourceVersion getSupportedSourceVersion() {
                return SourceVersion.latestSupported();
              }
              @Override public boolean process(Set<? extends TypeElement> types, RoundEnvironment round) {
                round.getRootElements().stream()
                    .filter(element -> !element.getSimpleName().toString().startsWith("Quiet"))
                    .forEach(element -> processingEnv.getMessager()
                        .printMessage(Diagnostic.Kind.WARNING, "noted by processor", element));
                return false;
              }
            }
            """,
            1_000L);
    final Path processors = root.resolve("processors");
    TestJavac.compileToDir(processors, processorSource);
    return processors;
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
        WorkspaceManifestData::buildFiles,
        WorkspaceManifestData::jdk,
        WorkspaceManifestData::dependencySources);
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

    rewriteManifest(
        workspaceRoot,
        WorkspaceManifestData::outputs,
        existing -> synced,
        WorkspaceManifestData::jdk,
        WorkspaceManifestData::dependencySources);
  }

  // Records the JDK a front-end's sync found, its sources present or missing.
  public static void recordJdk(final Path workspaceRoot, final JdkSourceData jdk)
      throws IOException {
    rewriteManifest(
        workspaceRoot,
        WorkspaceManifestData::outputs,
        WorkspaceManifestData::buildFiles,
        existing -> jdk,
        WorkspaceManifestData::dependencySources);
  }

  // A dependency jar declaring the given sources' public top-level classes, with its type index,
  // recorded as a sync records one without sources (none published, or an unreadable sources jar),
  // on the classpath of the single module that
  // writeModuleSource writes: called after it, which writes the module's params without one.
  // Returns the jar.
  public static Path recordSourcelessDependency(
      final Path workspaceRoot, final String gav, final Map<String, String> sources)
      throws IOException {
    final Path sourceRoot = workspaceRoot.resolve("lib-src");
    final var paths = new ArrayList<Path>();
    final var types = new ArrayList<TypeIndexEntry>();
    for (final var source : sources.entrySet()) {
      final Path path = sourceRoot.resolve(source.getKey());
      Files.createDirectories(path.getParent());
      Files.writeString(path, source.getValue());
      paths.add(path);
      types.add(typeIndexEntry(Path.of(source.getKey())));
    }

    final Path jar = workspaceRoot.resolve("repo/lib.jar");
    Files.createDirectories(jar.getParent());
    TestJavac.compileToJar(
        jar, workspaceRoot.resolve("lib-classes"), List.of(), paths.toArray(Path[]::new));
    final Path typeIndex = workspaceRoot.resolve("cache/type-index/lib.json");
    Files.createDirectories(typeIndex.getParent());
    Json.write(
        new TypeIndexFile(
            LatheLayout.CACHE_SCHEMA_VERSION,
            TypeIndexOrigin.dependency(
                new DependencyTypeIndexOrigin(gav, jar.toString(), Files.size(jar), 0L)),
            types),
        typeIndex);
    final var dependency =
        new DependencyData(
            gav, jar.toString(), SourceStatus.MISSING, null, List.of(), typeIndex.toString());
    rewriteManifest(
        workspaceRoot,
        WorkspaceManifestData::outputs,
        WorkspaceManifestData::buildFiles,
        WorkspaceManifestData::jdk,
        existing ->
            Stream.concat(existing.dependencySources().stream(), Stream.of(dependency)).toList());
    writeModuleParams(
        workspaceRoot,
        "module",
        workspaceRoot.resolve("module").resolve("src/main/java"),
        null,
        List.of(jar));
    return jar;
  }

  private static void rewriteManifest(
      final Path workspaceRoot,
      final Function<WorkspaceManifestData, List<OutputData>> outputs,
      final Function<WorkspaceManifestData, List<BuildFileData>> buildFiles,
      final Function<WorkspaceManifestData, JdkSourceData> jdk,
      final Function<WorkspaceManifestData, List<DependencyData>> dependencySources)
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
                null,
                Map.of(),
                List.of(),
                Map.of());
    Files.createDirectories(manifestFile.getParent());
    Json.write(
        new WorkspaceManifestData(
            existing.schemaVersion(),
            existing.workspaceRoot(),
            existing.serverVersion(),
            existing.runnerClasspath(),
            jdk.apply(existing),
            dependencySources.apply(existing),
            buildFiles.apply(existing),
            existing.resourceRoots(),
            outputs.apply(existing),
            existing.buildTool(),
            Map.of(),
            List.of(),
            existing.otherLanguages()),
        manifestFile);
  }

  // The languages besides Java the build reported each module's sources hold, by module and key.
  public static void recordOtherLanguages(
      final Path workspaceRoot, final Map<String, List<String>> otherLanguages) throws IOException {
    final Path manifestFile =
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON);
    final WorkspaceManifestData existing = Json.read(manifestFile, WorkspaceManifestData.class);
    Json.write(
        new WorkspaceManifestData(
            existing.schemaVersion(),
            existing.workspaceRoot(),
            existing.serverVersion(),
            existing.runnerClasspath(),
            existing.jdk(),
            existing.dependencySources(),
            existing.buildFiles(),
            existing.resourceRoots(),
            existing.outputs(),
            existing.buildTool(),
            existing.runnerClasspaths(),
            existing.buildDirs(),
            otherLanguages),
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
    writeTreeParams(
        workspaceRoot,
        moduleName,
        LatheLayout.CLASSES_DIR,
        sourceRoots,
        generatedSourcesDir,
        classpath,
        processorPath,
        compilerArgs,
        false,
        null,
        false,
        null);
  }

  // A module's test tree, with its annotation-processor output, as a sync writes its params.
  public static void writeTestTreeParams(
      final Path workspaceRoot,
      final String moduleName,
      final Path sourceRoot,
      final Path generatedSourcesDir)
      throws IOException {
    writeTreeParams(
        workspaceRoot,
        moduleName,
        LatheLayout.TEST_CLASSES_DIR,
        List.of(sourceRoot),
        generatedSourcesDir,
        List.of(),
        List.of(),
        List.of(),
        true,
        null,
        false,
        null);
  }

  // A Gradle source set's tree beyond main and test, as a sync writes its params.
  public static void writeSourceSetParams(
      final Path workspaceRoot,
      final String moduleName,
      final String sourceSet,
      final Path sourceRoot,
      final List<Path> classpath,
      final boolean testCode)
      throws IOException {
    writeTreeParams(
        workspaceRoot,
        moduleName,
        LatheLayout.sourceSetTree(sourceSet),
        List.of(sourceRoot),
        null,
        classpath,
        List.of(),
        List.of(),
        testCode,
        null,
        false,
        null);
  }

  // A main tree with a second source root a build task writes under the build directory, as a
  // Gradle code-generation task's output (build/generated/sources/<task>).
  public static void writeBuildOutputParams(
      final Path workspaceRoot,
      final String moduleName,
      final Path sourceRoot,
      final Path buildOutputRoot,
      final Path buildDir)
      throws IOException {
    writeTreeParams(
        workspaceRoot,
        moduleName,
        LatheLayout.CLASSES_DIR,
        List.of(sourceRoot, buildOutputRoot),
        null,
        List.of(),
        List.of(),
        List.of(),
        false,
        null,
        false,
        buildDir);
  }

  // A main tree whose classes the server reads in the build's own output, as OpenJDK's modules.
  public static void writeExternalOutputParams(
      final Path workspaceRoot,
      final String moduleName,
      final Path sourceRoot,
      final Path outputDir)
      throws IOException {
    writeTreeParams(
        workspaceRoot,
        moduleName,
        LatheLayout.CLASSES_DIR,
        List.of(sourceRoot),
        null,
        List.of(),
        List.of(),
        List.of(),
        false,
        outputDir,
        true,
        null);
  }

  private static void writeTreeParams(
      final Path workspaceRoot,
      final String moduleName,
      final String sourceTree,
      final List<Path> sourceRoots,
      final Path generatedSourcesDir,
      final List<Path> classpath,
      final List<Path> processorPath,
      final List<String> compilerArgs,
      final boolean testCode,
      final Path outputDir,
      final boolean externalOutput,
      final Path buildDir)
      throws IOException {
    final var latheModuleDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(moduleName);
    Files.createDirectories(latheModuleDir);
    final var config =
        new ModuleConfigData(
            sourceTree,
            (outputDir != null ? outputDir : latheModuleDir.resolve(sourceTree)).toString(),
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
            externalOutput,
            testCode,
            buildDir != null ? buildDir.toString() : null);
    final Path paramsFile = latheModuleDir.resolve(LatheLayout.paramsFileName(sourceTree));
    Json.write(config, paramsFile);
    // A sync writes the manifest beside the params; without it the server has no module graph.
    if (Files.notExists(
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON))) {
      recordBuildFiles(workspaceRoot, List.of());
    }
  }

  // The index entry of the public top-level class a source file declares, named after the file.
  private static TypeIndexEntry typeIndexEntry(final Path relativePath) {
    final String binaryName = FileUtil.slashed(relativePath).replace(".java", "").replace('/', '.');
    final int lastDot = binaryName.lastIndexOf('.');
    return new TypeIndexEntry(
        binaryName.substring(lastDot + 1),
        binaryName,
        lastDot < 0 ? "" : binaryName.substring(0, lastDot),
        TypeKind.CLASS,
        true,
        List.of(),
        null);
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
