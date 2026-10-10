package io.github.aglibs.lathe.server;

import static io.github.aglibs.lathe.server.FormatterLoader.get;
import static io.github.aglibs.lathe.server.FormatterLoader.invoke;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.validcheck.ValidCheck;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

// The project's pinned google-java-format (GOOGLE or AOSP style), loaded from its own jars on first
// use and called reflectively. format() is Spotless's googleJavaFormat step: format, remove unused
// imports, then reorder imports and reflow long strings only when the project enables them.
final class GoogleFormatEngine implements FormatEngine {

  private static final String API = "com.google.googlejavaformat.java.";
  // Spotless re-applies a step until its output stops changing (its PaddedCell, at most 10 passes),
  // so spotless:apply writes the converged text: removing an unused import after formatting leaves
  // a blank line that only the next pass collapses.
  private static final int MAX_PASSES = 10;

  private final String style;
  private final Map<String, String> options;
  private final List<String> classpath;
  private Gjf gjf;

  GoogleFormatEngine(
      final String style, final Map<String, String> options, final List<String> classpath) {
    this.style = style;
    this.options = Map.copyOf(options);
    this.classpath = List.copyOf(classpath);
  }

  @Override
  public String format(final String source, final Path file) throws Exception {
    final List<Step> pipeline = gjf().pipeline();
    String current = source;
    for (int pass = 0; pass < MAX_PASSES; pass++) {
      final String next = applyAll(pipeline, current);
      if (next.equals(current)) {
        return current;
      }

      current = next;
    }

    return current;
  }

  // GJF's partial formatting: only the given spans change, and imports are left alone.
  String formatRanges(final String source, final List<TextRange> ranges) throws Exception {
    final Gjf loaded = gjf();
    final List<Object> spans = ranges.stream().map(loaded::span).toList();
    final var replacements =
        (List<?>) invoke(loaded.replacements(), loaded.formatter(), source, spans);
    final var out = new StringBuilder(source);
    replacements.stream()
        .map(Edit::of)
        .sorted(Comparator.comparingInt(Edit::start).reversed())
        .toList()
        .forEach(edit -> out.replace(edit.start(), edit.end(), edit.text()));
    return out.toString();
  }

  private synchronized Gjf gjf() throws Exception {
    if (gjf == null) {
      gjf = load();
    }

    return gjf;
  }

  private Gjf load() throws Exception {
    final ClassLoader loader = FormatterLoader.isolated(classpath);
    final Class<?> optionsType = loader.loadClass(API + "JavaFormatterOptions");
    final Class<?> builderType = loader.loadClass(API + "JavaFormatterOptions$Builder");
    final Class<?> styleType = loader.loadClass(API + "JavaFormatterOptions$Style");
    final Object styleValue = enumConstant(styleType, style);
    final Object builder = optionsType.getMethod("builder").invoke(null);
    builderType.getMethod("style", styleType).invoke(builder, styleValue);
    builderType
        .getMethod("formatJavadoc", boolean.class)
        .invoke(builder, enabled(LatheFlags.FORMAT_JAVADOC, LatheFlags.FORMAT_JAVADOC_DEFAULT));
    final Class<?> formatterType = loader.loadClass(API + "Formatter");
    final Object formatter =
        formatterType
            .getConstructor(optionsType)
            .newInstance(builderType.getMethod("build").invoke(builder));
    final Method formatSource = formatterType.getMethod("formatSource", String.class);
    final Method removeUnused =
        method(loader, "RemoveUnusedImports", "removeUnusedImports", String.class);
    final Method reorder =
        method(loader, "ImportOrderer", "reorderImports", String.class, styleType);
    final Method wrap = method(loader, "StringWrapper", "wrap", String.class, formatterType);

    final List<Step> pipeline = new ArrayList<>();
    pipeline.add(step(formatSource, formatter));
    pipeline.add(step(removeUnused, null));
    if (enabled(LatheFlags.FORMAT_REORDER_IMPORTS, LatheFlags.FORMAT_REORDER_IMPORTS_DEFAULT)) {
      pipeline.add(step(reorder, null, styleValue));
    }

    if (enabled(
        LatheFlags.FORMAT_REFLOW_LONG_STRINGS, LatheFlags.FORMAT_REFLOW_LONG_STRINGS_DEFAULT)) {
      pipeline.add(step(wrap, null, formatter));
    }

    return new Gjf(
        formatter,
        formatterType.getMethod("getFormatReplacements", String.class, Collection.class),
        loader
            .loadClass("com.google.common.collect.Range")
            .getMethod("closedOpen", Comparable.class, Comparable.class),
        pipeline);
  }

  private static Method method(
      final ClassLoader loader, final String type, final String name, final Class<?>... params)
      throws ReflectiveOperationException {
    return loader.loadClass(API + type).getMethod(name, params);
  }

  // A GJF call that takes the source first, then any fixed trailing arguments.
  private static Step step(final Method method, final Object target, final Object... trailing) {
    return source ->
        (String)
            invoke(
                method,
                target,
                Stream.concat(Stream.of(source), Arrays.stream(trailing)).toArray());
  }

  private static String applyAll(final List<Step> pipeline, final String source) throws Exception {
    String formatted = source;
    for (final Step step : pipeline) {
      formatted = step.apply(formatted);
    }

    return formatted;
  }

  private boolean enabled(final String option, final boolean defaultValue) {
    return Boolean.parseBoolean(options.getOrDefault(option, String.valueOf(defaultValue)));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object enumConstant(final Class<?> type, final String name) {
    return Enum.valueOf((Class) type, name);
  }

  @FunctionalInterface
  private interface Step {
    String apply(String source) throws Exception;
  }

  // The loaded formatter: its instance, the partial-formatting entry points, and the format
  // pipeline built once from the project's options.
  private record Gjf(
      Object formatter, Method replacements, Method closedOpen, List<Step> pipeline) {

    private Gjf {
      ValidCheck.check()
          .notNull(formatter, "formatter")
          .notNull(replacements, "replacements")
          .notNull(closedOpen, "closedOpen")
          .notNull(pipeline, "pipeline")
          .validate();
      pipeline = List.copyOf(pipeline);
    }

    // A Guava Range from the formatter's own Guava.
    Object span(final TextRange range) {
      try {
        return closedOpen.invoke(null, range.start(), range.end());
      } catch (final ReflectiveOperationException e) {
        throw new IllegalStateException("cannot build a formatter range", e);
      }
    }
  }

  // One of GJF's replacements, read reflectively.
  private record Edit(int start, int end, String text) {

    private Edit {
      ValidCheck.check().notNull(text, "text").validate();
    }

    static Edit of(final Object replacement) {
      final Object range = get(replacement, "getReplaceRange");
      return new Edit(
          (Integer) get(range, "lowerEndpoint"),
          (Integer) get(range, "upperEndpoint"),
          (String) get(replacement, "getReplacementString"));
    }
  }
}
