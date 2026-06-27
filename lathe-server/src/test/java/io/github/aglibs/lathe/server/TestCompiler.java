package io.github.aglibs.lathe.server;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import io.github.aglibs.lathe.server.analysis.JavaSourceCompiler;
import io.github.aglibs.lathe.server.analysis.SourceParser;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

public final class TestCompiler {

  public record ParsedSource(
      JavacTask task,
      Trees trees,
      CompilationUnitTree cu,
      StandardJavaFileManager fm,
      SourceParser parser)
      implements AutoCloseable {

    public ParsedSource {
      ValidCheck.check()
          .notNull(task, "task")
          .notNull(trees, "trees")
          .notNull(cu, "cu")
          .notNull(fm, "fm")
          .notNull(parser, "parser")
          .validate();
    }

    @Override
    public void close() throws IOException {
      fm.close();
    }
  }

  private TestCompiler() {}

  public static ModuleSourceConfig moduleConfig(final Path workspaceRoot, final Path sourceRoot) {
    return moduleConfig(
        workspaceRoot.resolve(".lathe/module"),
        workspaceRoot.resolve("target/classes"),
        sourceRoot);
  }

  public static ModuleSourceConfig moduleConfig(
      final Path moduleDir, final Path outputDir, final Path sourceRoot) {
    return new ModuleSourceConfig(
        moduleDir,
        "classes",
        outputDir,
        null,
        List.of(sourceRoot),
        List.of(),
        List.of(),
        List.of(),
        "21",
        "UTF-8",
        false,
        false,
        null,
        List.of());
  }

  public static void writeModuleParams(
      final Path workspaceRoot,
      final String moduleName,
      final Path sourceRoot,
      final Path generatedSourcesDir)
      throws IOException {
    final var latheModuleDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(moduleName);
    Files.createDirectories(latheModuleDir);
    final var config =
        new ModuleConfigData(
            "classes",
            latheModuleDir.resolve("classes").toString(),
            generatedSourcesDir != null ? generatedSourcesDir.toString() : null,
            List.of(sourceRoot.toString()),
            List.of(),
            List.of(),
            List.of(),
            "21",
            "UTF-8",
            false,
            false,
            null,
            List.of());
    final Path paramsFile = latheModuleDir.resolve(LatheLayout.paramsFileName("classes"));
    Json.write(config, paramsFile);
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
    final var fm = JavaSourceCompiler.createFileManager();
    Files.createDirectories(classDir);
    fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classDir));
    if (!classpath.isEmpty()) {
      fm.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
    }
    JavaSourceCompiler.COMPILER
        .getTask(
            null,
            fm,
            null,
            options.isEmpty() ? null : options,
            null,
            fm.getJavaFileObjects(sources))
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
    final var fm = JavaSourceCompiler.createFileManager();
    if (!classpath.isEmpty()) {
      fm.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
    }
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
