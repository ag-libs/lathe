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
 * The javac file managers test compiles share, one per thread, classpath, and option set.
 *
 * <p>Opening a file manager indexes the JDK image and the classpath, which costs more than
 * compiling a test snippet, so a configuration is opened once per thread and never closed. The
 * classpath is only what the test names: an empty one means JDK only, never the test JVM's own.
 * Options are part of the key because javac applies path options such as {@code --module-path} to
 * the file manager itself.
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
    return FILE_MANAGERS.get().computeIfAbsent(new Config(classpath, options), TestJavac::open);
  }

  private static StandardJavaFileManager open(final Config config) {
    final var fm = JavaSourceCompiler.createFileManager();
    IOUtil.unchecked(
        () -> fm.setLocationFromPaths(StandardLocation.CLASS_PATH, config.classpath()));
    return fm;
  }
}
