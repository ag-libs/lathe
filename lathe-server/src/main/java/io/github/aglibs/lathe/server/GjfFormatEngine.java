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

// The project's pinned google-java-format, or its fork palantir-java-format (the same API under its
// own package), loaded from its own jars on first use and called reflectively. format() is the
// matching Spotless step: googleJavaFormat formats, removes unused imports, then reorders imports
// and reflows long strings only when the project enables them; palantirJavaFormat reorders and
// removes unused imports, then formats.
final class GjfFormatEngine implements FormatEngine {

  private final Family family;
  private final String style;
  private final Map<String, String> options;
  private final List<String> classpath;
  private Gjf gjf;

  GjfFormatEngine(
      final Family family,
      final String style,
      final Map<String, String> options,
      final List<String> classpath) {
    this.family = family;
    this.style = style;
    this.options = Map.copyOf(options);
    this.classpath = List.copyOf(classpath);
  }

  @Override
  public String format(final String source, final Path file) throws Exception {
    final List<Pass> pipeline = gjf().pipeline();
    return FormatEngine.converge(source, current -> applyAll(pipeline, current));
  }

  @Override
  public boolean formatsRanges() {
    return true;
  }

  // GJF's partial formatting: only the given spans change, and imports are left alone.
  @Override
  public String formatRanges(final String source, final List<TextRange> ranges) throws Exception {
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
    final Class<?> optionsType = type(loader, "JavaFormatterOptions");
    final Class<?> builderType = type(loader, "JavaFormatterOptions$Builder");
    final Class<?> styleType = type(loader, "JavaFormatterOptions$Style");
    final Object styleValue = enumConstant(styleType, style);
    final Object builder = optionsType.getMethod("builder").invoke(null);
    builderType.getMethod("style", styleType).invoke(builder, styleValue);
    // Set only when it differs from the builder's own default: palantir before 2.36 has no
    // formatJavadoc option at all.
    final boolean formatJavadoc =
        enabled(LatheFlags.FORMAT_JAVADOC, family.formatJavadocByDefault());
    if (formatJavadoc != family.formatJavadocByDefault()) {
      builderType.getMethod("formatJavadoc", boolean.class).invoke(builder, formatJavadoc);
    }

    final Object formatterOptions = builderType.getMethod("build").invoke(builder);
    final Class<?> formatterType = type(loader, "Formatter");
    final Object formatter =
        switch (family) {
          case GOOGLE -> formatterType.getConstructor(optionsType).newInstance(formatterOptions);
          case PALANTIR ->
              formatterType
                  .getMethod("createFormatter", optionsType)
                  .invoke(null, formatterOptions);
        };
    final Pass format = step(formatterType.getMethod("formatSource", String.class), formatter);
    final Pass removeUnused =
        step(
            type(loader, "RemoveUnusedImports").getMethod("removeUnusedImports", String.class),
            null);
    final Pass reorder =
        step(
            type(loader, "ImportOrderer").getMethod("reorderImports", String.class, styleType),
            null,
            styleValue);
    final List<Pass> pipeline =
        switch (family) {
          case GOOGLE ->
              googlePipeline(loader, formatterType, formatter, format, removeUnused, reorder);
          case PALANTIR -> List.of(reorder, removeUnused, format);
        };
    return new Gjf(
        formatter,
        formatterType.getMethod("getFormatReplacements", String.class, Collection.class),
        loader
            .loadClass("com.google.common.collect.Range")
            .getMethod("closedOpen", Comparable.class, Comparable.class),
        pipeline);
  }

  private List<Pass> googlePipeline(
      final ClassLoader loader,
      final Class<?> formatterType,
      final Object formatter,
      final Pass format,
      final Pass removeUnused,
      final Pass reorder)
      throws ReflectiveOperationException {
    final List<Pass> pipeline = new ArrayList<>(List.of(format, removeUnused));
    if (enabled(LatheFlags.FORMAT_REORDER_IMPORTS, false)) {
      pipeline.add(reorder);
    }

    if (enabled(LatheFlags.FORMAT_REFLOW_LONG_STRINGS, false)) {
      final Method wrap =
          type(loader, "StringWrapper").getMethod("wrap", String.class, formatterType);
      pipeline.add(step(wrap, null, formatter));
    }

    return pipeline;
  }

  private Class<?> type(final ClassLoader loader, final String simpleName)
      throws ClassNotFoundException {
    return loader.loadClass(family.api() + simpleName);
  }

  // A GJF call that takes the source first, then any fixed trailing arguments.
  private static Pass step(final Method method, final Object target, final Object... trailing) {
    return source ->
        (String)
            invoke(
                method,
                target,
                Stream.concat(Stream.of(source), Arrays.stream(trailing)).toArray());
  }

  private static String applyAll(final List<Pass> pipeline, final String source) throws Exception {
    String formatted = source;
    for (final Pass step : pipeline) {
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

  // The formatters sharing google-java-format's API, with the javadoc formatting their options
  // builder applies unless told otherwise.
  enum Family {
    GOOGLE("com.google.googlejavaformat.java.", true),
    PALANTIR("com.palantir.javaformat.java.", false);

    private final String api;
    private final boolean formatJavadocByDefault;

    Family(final String api, final boolean formatJavadocByDefault) {
      this.api = api;
      this.formatJavadocByDefault = formatJavadocByDefault;
    }

    String api() {
      return api;
    }

    boolean formatJavadocByDefault() {
      return formatJavadocByDefault;
    }
  }

  // The loaded formatter: its instance, the partial-formatting entry points, and the format
  // pipeline built once from the project's options.
  private record Gjf(
      Object formatter, Method replacements, Method closedOpen, List<Pass> pipeline) {

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
