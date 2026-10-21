package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.server.analysis.JavaSourceCompiler;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

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
}
