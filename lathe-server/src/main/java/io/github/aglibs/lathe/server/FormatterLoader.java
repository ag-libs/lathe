package io.github.aglibs.lathe.server;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

// Runs a project's pinned formatter from its own jars, isolated from the server: the parent is the
// platform loader, so the JDK's modules (jdk.compiler) resolve but none of the server's libraries
// do, and the formatter's dependencies (its own Guava, say) cannot clash with ours. The server
// calls it reflectively, so it never depends on a formatter at compile time.
final class FormatterLoader {

  private FormatterLoader() {}

  static ClassLoader isolated(final List<String> classpath) {
    final URL[] urls = classpath.stream().map(FormatterLoader::toUrl).toArray(URL[]::new);
    return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
  }

  // Surfaces the formatter's own failure (e.g. its FormatterException naming the syntax error),
  // not the reflection wrapper around it.
  static Object invoke(final Method method, final Object target, final Object... args)
      throws Exception {
    try {
      return method.invoke(target, args);
    } catch (final InvocationTargetException e) {
      if (e.getCause() instanceof final Exception cause) {
        throw cause;
      }

      // Typically the javac-internals grants missing from the server JVM (a .lathe/ older than
      // the last lathe:sync, or a launcher not started from the workspace root). Reported as a
      // formatting failure rather than escaping as an internal error.
      if (e.getCause() instanceof final LinkageError error) {
        throw new IllegalStateException(
            "%s cannot run in this server JVM; run lathe:sync and restart the server: %s"
                .formatted(method.getDeclaringClass().getName(), error.getMessage()),
            error);
      }

      if (e.getCause() instanceof final Error error) {
        throw error;
      }

      throw e;
    }
  }

  // Reads a no-argument accessor of a formatter-owned object (a replacement, a range).
  static Object get(final Object target, final String accessor) {
    try {
      return target.getClass().getMethod(accessor).invoke(target);
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException(
          "%s has no accessible %s()".formatted(target.getClass().getName(), accessor), e);
    }
  }

  private static URL toUrl(final String path) {
    try {
      return Path.of(path).toUri().toURL();
    } catch (final MalformedURLException e) {
      throw new IllegalArgumentException("bad formatter classpath entry %s".formatted(path), e);
    }
  }
}
