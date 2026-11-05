package io.github.aglibs.lathe.server.analysis.testing;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.server.analysis.JavaSourceCompiler;
import io.github.aglibs.lathe.server.analysis.SourceParser;
import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.platform.suite.api.Suite;

/**
 * The javac file managers test compiles use: JDK-only ones shared per thread and option set, and a
 * fresh one for each classpath.
 *
 * <p>Opening a file manager indexes the JDK image, which costs more than compiling a test snippet,
 * so a JDK-only configuration is opened once per thread and never closed. Options are part of the
 * key because javac applies path options such as {@code --module-path} to the file manager itself.
 * A classpath is what the test names, under its own temp dir: a manager for it is never reused by
 * another test, and it holds the classpath's jars open, which Windows won't delete, so its owner
 * {@link #release releases} it.
 */
public final class TestJavac {

  private record Config(List<Path> classpath, List<String> options) {

    private Config {
      classpath = List.copyOf(classpath);
      options = List.copyOf(options);
    }
  }

  // Per thread: a javac file manager is not thread-safe, and test classes may run in parallel.
  private static final ThreadLocal<Map<Config, StandardJavaFileManager>> FILE_MANAGERS =
      ThreadLocal.withInitial(HashMap::new);

  private TestJavac() {}

  public static StandardJavaFileManager fileManager(
      final List<Path> classpath, final List<String> options) {
    final var config = new Config(classpath, options);
    if (!classpath.isEmpty()) {
      return open(config);
    }

    return FILE_MANAGERS.get().computeIfAbsent(config, TestJavac::open);
  }

  // Closes a manager fileManager opened for a classpath; a JDK-only one is shared and stays open.
  public static void release(final List<Path> classpath, final StandardJavaFileManager fm) {
    if (!classpath.isEmpty()) {
      IOUtil.unchecked(fm::close);
    }
  }

  private static StandardJavaFileManager open(final Config config) {
    final var fm = JavaSourceCompiler.createFileManager();
    IOUtil.unchecked(
        () -> fm.setLocationFromPaths(StandardLocation.CLASS_PATH, config.classpath()));
    return fm;
  }

  // JUnit's API jars from the test JVM's own classpath, for fixtures whose @Test must resolve as in
  // a real project: javac only reports annotations it can resolve.
  public static final List<Path> JUNIT_CLASSPATH =
      List.of(
          jarOf(org.junit.jupiter.api.Test.class),
          jarOf(ParameterizedTest.class),
          jarOf(Suite.class));

  public record ParsedSource(
      JavacTask task,
      Trees trees,
      CompilationUnitTree cu,
      StandardJavaFileManager fm,
      List<Path> classpath,
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
      classpath = List.copyOf(classpath);
    }

    @Override
    public void close() {
      parser.close();
      release(classpath, fm);
    }
  }

  // Where a class was loaded from: its jar, or its classes directory.
  public static Path jarOf(final Class<?> type) {
    try {
      return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (final URISyntaxException e) {
      throw new IllegalStateException(e);
    }
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
    final var fm = fileManager(classpath, options);
    try {
      Files.createDirectories(classDir);
      // The shared file manager keeps this output dir afterwards; only compileToDir writes classes.
      fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classDir));
      JavaSourceCompiler.COMPILER
          .getTask(null, fm, null, options, null, fm.getJavaFileObjects(sources))
          .call();
    } finally {
      release(classpath, fm);
    }
  }

  public static void compileToJar(
      final Path jar, final Path classDir, final List<Path> classpath, final Path... sources)
      throws IOException {
    compileToDir(classDir, classpath, sources);
    try (final var files = Files.walk(classDir);
        final var out = new JarOutputStream(Files.newOutputStream(jar))) {
      final List<Path> classFiles = files.filter(Files::isRegularFile).toList();
      for (final Path classFile : classFiles) {
        // Jar entry names use '/' on every OS.
        final var entry = new JarEntry(FileUtil.slashed(classDir.relativize(classFile)));
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
    final var fm = fileManager(classpath, options);
    final var diagnostics = new DiagnosticCollector<JavaFileObject>();
    final JavacTask task =
        (JavacTask)
            JavaSourceCompiler.COMPILER.getTask(
                null, fm, diagnostics, options, null, fm.getJavaFileObjectsFromPaths(sources));
    final CompilationUnitTree cu = task.parse().iterator().next();
    task.analyze();
    return new ParsedSource(task, Trees.instance(task), cu, fm, classpath, new SourceParser());
  }
}
