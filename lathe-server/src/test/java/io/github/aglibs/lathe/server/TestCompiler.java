package io.github.aglibs.lathe.server;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

public final class TestCompiler {

  public record ParsedSource(
      JavacTask task,
      Trees trees,
      CompilationUnitTree cu,
      StandardJavaFileManager fm,
      JavaCompiler compiler,
      SourceParser parser) {}

  private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();

  private TestCompiler() {}

  private static StandardJavaFileManager newFm() {
    return COMPILER.getStandardFileManager(null, null, null);
  }

  public static void compileToDir(final Path classDir, final Path... sources) throws IOException {
    compileToDir(classDir, List.of(), sources);
  }

  public static void compileToDir(
      final Path classDir, final List<Path> classpath, final Path... sources) throws IOException {
    final var fm = newFm();
    Files.createDirectories(classDir);
    fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classDir));
    if (!classpath.isEmpty()) {
      fm.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
    }
    COMPILER.getTask(null, fm, null, null, null, fm.getJavaFileObjects(sources)).call();
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
    return doParse(src, List.of());
  }

  public static ParsedSource parseWithClasspath(final Path src, final Path classDir)
      throws IOException {
    return doParse(src, List.of(classDir));
  }

  private static ParsedSource doParse(final Path src, final List<Path> classpath)
      throws IOException {
    final var fm = newFm();
    if (!classpath.isEmpty()) {
      fm.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
    }
    final JavacTask task =
        (JavacTask) COMPILER.getTask(null, fm, null, null, null, fm.getJavaFileObjects(src));
    final CompilationUnitTree cu = task.parse().iterator().next();
    task.analyze();
    return new ParsedSource(
        task, Trees.instance(task), cu, fm, COMPILER, new SourceParser(COMPILER, fm));
  }
}
