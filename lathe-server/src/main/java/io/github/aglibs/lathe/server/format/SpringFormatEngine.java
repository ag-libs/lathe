package io.github.aglibs.lathe.server.format;

import static io.github.aglibs.lathe.server.format.FormatterLoader.construct;
import static io.github.aglibs.lathe.server.format.FormatterLoader.invoke;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.validcheck.ValidCheck;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The project's pinned spring-javaformat, loaded from its own jars on first use and called through
// its public API reflectively, the way spring-javaformat-maven-plugin calls it: the configuration
// .springjavaformatconfig sets for the file's module (searched upwards from the module's base
// directory, as the plugin searches from the project's, else the default), one pass over the
// whole file, and the source's own line endings. Ranges format only the requested regions.
final class SpringFormatEngine implements FormatEngine {

  private final String version;
  private final List<String> classpath;
  // The workspace root the modules are relative to; null without one, when the search starts at
  // the file's directory.
  private final Path root;
  private Spring spring;
  // A formatter per configuration (a Java baseline and an indentation style), each built once.
  private final Map<String, Object> formatters = new HashMap<>();

  SpringFormatEngine(final String version, final List<String> classpath, final Path root) {
    this.version = version;
    this.classpath = List.copyOf(classpath);
    this.root = root;
  }

  @Override
  public String format(final String source, final FormatTarget target) throws Exception {
    return format(source, target, List.of());
  }

  @Override
  public boolean formatsRanges() {
    return true;
  }

  @Override
  public String formatRanges(
      final String source, final FormatTarget target, final List<TextRange> ranges)
      throws Exception {
    return format(source, target, ranges);
  }

  // No ranges formats the whole file.
  private synchronized String format(
      final String source, final FormatTarget target, final List<TextRange> ranges)
      throws Exception {
    if (spring == null) {
      spring = load();
    }

    final Object config = invoke(spring.findConfig(), null, configStart(target));
    final String key =
        "%s %s"
            .formatted(
                invoke(spring.baseline(), config), invoke(spring.indentationStyle(), config));
    Object formatter = formatters.get(key);
    if (formatter == null) {
      formatter = construct(spring.formatter(), config);
      formatters.put(key, formatter);
    }

    return spring.format(formatter, source, ranges);
  }

  private Path configStart(final FormatTarget target) {
    return root != null
        ? root.resolve(target.module()).toAbsolutePath()
        : target.file().toAbsolutePath().getParent();
  }

  // A jar missing from the classpath leaves the formatter unavailable, which a sync repairs; a
  // missing method is a release whose API Lathe does not call, which a sync cannot repair.
  private Spring load() {
    try {
      final ClassLoader loader = FormatterLoader.isolated(classpath);
      final Class<?> configType = loader.loadClass("io.spring.javaformat.config.JavaFormatConfig");
      final Class<?> formatterType = loader.loadClass("io.spring.javaformat.formatter.Formatter");
      final Class<?> regionType = loader.loadClass("org.eclipse.jface.text.IRegion");
      final Class<?> documentType = loader.loadClass("org.eclipse.jface.text.Document");
      return new Spring(
          configType.getMethod("findFrom", Path.class),
          configType.getMethod("getJavaBaseline"),
          configType.getMethod("getIndentationStyle"),
          formatterType.getConstructor(configType),
          formatterType.getMethod("format", String.class, String.class),
          formatterType.getMethod("format", String.class, regionType.arrayType(), String.class),
          regionType,
          loader.loadClass("org.eclipse.jface.text.Region").getConstructor(int.class, int.class),
          documentType.getConstructor(String.class),
          loader
              .loadClass("org.eclipse.text.edits.TextEdit")
              .getMethod("apply", loader.loadClass("org.eclipse.jface.text.IDocument")),
          documentType.getMethod("get"));
    } catch (final NoSuchMethodException e) {
      throw new IllegalStateException(
          "spring-javaformat %s is not a release Lathe supports; declare a newer version in %s: %s"
              .formatted(version, LatheLayout.STYLE_SHARED_FILE, e.getMessage()),
          e);
    } catch (final ReflectiveOperationException | LinkageError e) {
      throw new IllegalStateException(
          "spring-javaformat is unavailable, its jars are incomplete; run `%s`: %s"
              .formatted(LatheLayout.SYNC_SCRIPT_COMMAND, e),
          e);
    }
  }

  // The loaded spring-javaformat entry points format() needs.
  private record Spring(
      Method findConfig,
      Method baseline,
      Method indentationStyle,
      Constructor<?> formatter,
      Method formatFile,
      Method formatRegions,
      Class<?> regionType,
      Constructor<?> region,
      Constructor<?> document,
      Method apply,
      Method text) {

    private Spring {
      ValidCheck.check()
          .notNull(findConfig, "findConfig")
          .notNull(baseline, "baseline")
          .notNull(indentationStyle, "indentationStyle")
          .notNull(formatter, "formatter")
          .notNull(formatFile, "formatFile")
          .notNull(formatRegions, "formatRegions")
          .notNull(regionType, "regionType")
          .notNull(region, "region")
          .notNull(document, "document")
          .notNull(apply, "apply")
          .notNull(text, "text")
          .validate();
    }

    // Formats the given regions of source, or all of it for none. spring-javaformat leaves source
    // it cannot parse as it is; a missing edit is still reported, not applied.
    String format(final Object formatter, final String source, final List<TextRange> ranges)
        throws Exception {
      // The formatter rewrites every line ending it touches to the separator it's given; '\n' on
      // a CRLF document would make every line differ from the editor's.
      final String separator = source.contains("\r\n") ? "\r\n" : "\n";
      final Object edit =
          ranges.isEmpty()
              ? invoke(formatFile, formatter, source, separator)
              : invoke(formatRegions, formatter, source, regions(ranges), separator);
      if (edit == null) {
        throw new IllegalArgumentException("Invalid Java syntax for formatting");
      }

      final Object formatted = construct(document, source);
      invoke(apply, edit, formatted);
      return (String) invoke(text, formatted);
    }

    private Object regions(final List<TextRange> ranges) throws Exception {
      final Object regions = Array.newInstance(regionType, ranges.size());
      for (int i = 0; i < ranges.size(); i++) {
        final TextRange range = ranges.get(i);
        Array.set(regions, i, construct(region, range.start(), range.end() - range.start()));
      }

      return regions;
    }
  }
}
