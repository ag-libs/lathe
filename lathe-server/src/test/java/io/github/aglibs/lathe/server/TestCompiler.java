package io.github.aglibs.lathe.server;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.launch.ReactorRewrite;
import io.github.aglibs.lathe.core.schema.AnalysisMode;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import io.github.aglibs.lathe.server.analysis.JavaSourceCompiler;
import io.github.aglibs.lathe.server.analysis.SourceParser;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import org.junit.jupiter.params.ParameterizedTest;

public final class TestCompiler {

  // Fixtures compile at the test JDK's own language level, so the suite runs on every supported
  // JDK.
  public static final String RELEASE = Integer.toString(Runtime.version().feature());

  // JUnit's API jars from the test JVM's own classpath, for fixtures whose @Test must resolve as in
  // a real project: javac only reports annotations it can resolve.
  public static final List<Path> JUNIT_CLASSPATH =
      List.of(jarOf(org.junit.jupiter.api.Test.class), jarOf(ParameterizedTest.class));

  public record ParsedSource(
      JavacTask task,
      Trees trees,
      CompilationUnitTree cu,
      StandardJavaFileManager fm,
      SourceParser parser) {

    public ParsedSource {
      ValidCheck.check()
          .notNull(task, "task")
          .notNull(trees, "trees")
          .notNull(cu, "cu")
          .notNull(fm, "fm")
          .notNull(parser, "parser")
          .validate();
    }
  }

  private TestCompiler() {}

  private static Path jarOf(final Class<?> type) {
    try {
      return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (final URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  public static ModuleSourceConfig moduleConfig(final Path workspaceRoot, final Path sourceRoot) {
    return moduleConfig(
        workspaceRoot.resolve(".lathe/module"),
        workspaceRoot.resolve("target/classes"),
        sourceRoot);
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
    compileToDir(latheModuleDir.resolve("classes"), mirrors, paths.toArray(Path[]::new));
    CompiledStamps.writeAll(
        latheModuleDir,
        "classes",
        sources.keySet().stream().collect(Collectors.toMap(rel -> rel, rel -> mtimeMillis)));
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
                List.of());
    final List<OutputData> outputs =
        Stream.concat(existing.outputs().stream(), targetOutputs(moduleName).stream()).toList();
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
            outputs),
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
        sourceRoot,
        generatedSourcesDir,
        classpath,
        List.of(),
        List.of());
  }

  public static void writeModuleParams(
      final Path workspaceRoot,
      final String moduleName,
      final Path sourceRoot,
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
            List.of(sourceRoot.toString()),
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

  public static void compileToDir(final Path classDir, final Path... sources) throws IOException {
    compileToDir(classDir, List.of(), List.of(), sources);
  }

  public static void compileToDir(
      final Path classDir, final List<Path> classpath, final Path... sources) throws IOException {
    compileToDir(classDir, classpath, List.of(), sources);
  }

  public static void compileToDir(
      final Path classDir,
      final List<Path> classpath,
      final List<String> options,
      final Path... sources)
      throws IOException {
    final var fm = TestJavac.fileManager(classpath, options);
    Files.createDirectories(classDir);
    // The shared file manager keeps this output dir afterwards; only compileToDir writes classes.
    fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classDir));
    JavaSourceCompiler.COMPILER
        .getTask(null, fm, null, options, null, fm.getJavaFileObjects(sources))
        .call();
  }

  public static void compileToJar(
      final Path jar, final Path classDir, final List<Path> classpath, final Path... sources)
      throws IOException {
    compileToDir(classDir, classpath, sources);
    try (final var files = Files.walk(classDir);
        final var out = new JarOutputStream(Files.newOutputStream(jar))) {
      final List<Path> classFiles = files.filter(Files::isRegularFile).toList();
      for (final Path classFile : classFiles) {
        final var entry = new JarEntry(classDir.relativize(classFile).toString());
        out.putNextEntry(entry);
        Files.copy(classFile, out);
        out.closeEntry();
      }
    }
  }

  public static ParsedSource parse(final Path src) throws IOException {
    return parse(src, List.of());
  }

  public static ParsedSource parse(
      final Path primarySource, final List<String> options, final Path... companionSources)
      throws IOException {
    final List<Path> sources =
        Stream.concat(Stream.of(primarySource), Arrays.stream(companionSources)).toList();
    return doParse(sources, List.of(), options);
  }

  public static ParsedSource parseWithClasspath(final Path src, final Path classDir)
      throws IOException {
    return doParse(List.of(src), List.of(classDir), List.of());
  }

  private static ParsedSource doParse(
      final List<Path> sources, final List<Path> classpath, final List<String> options)
      throws IOException {
    final var fm = TestJavac.fileManager(classpath, options);
    final var diagnostics = new DiagnosticCollector<JavaFileObject>();
    final JavacTask task =
        (JavacTask)
            JavaSourceCompiler.COMPILER.getTask(
                null, fm, diagnostics, options, null, fm.getJavaFileObjectsFromPaths(sources));
    final CompilationUnitTree cu = task.parse().iterator().next();
    task.analyze();
    return new ParsedSource(task, Trees.instance(task), cu, fm, new SourceParser());
  }
}
