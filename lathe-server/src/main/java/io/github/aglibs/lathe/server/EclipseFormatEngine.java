package io.github.aglibs.lathe.server;

import static io.github.aglibs.lathe.server.FormatterLoader.invoke;

import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

// The project's pinned Eclipse JDT formatter, loaded from its own jars on first use and called
// through JDT's public API reflectively, the way Spotless's eclipse step calls it: the profile's
// settings, the whole compilation unit (a module descriptor for module-info.java), comments
// included, "\n" line endings. Ranges use the same call with only the requested regions.
final class EclipseFormatEngine implements FormatEngine {

  private static final String MODULE_INFO = "module-info.java";
  private static final String LINE_DELIMITER = "\n";
  // An Eclipse formatter export: <profiles><profile><setting id=".." value=".."/>..
  private static final String PROFILES_ELEMENT = "profiles";
  private static final String SETTING_ELEMENT = "setting";

  private final String profile;
  private final List<String> classpath;
  private Jdt jdt;

  // profile is the formatter settings file (an Eclipse .xml export or a .prefs file), or empty for
  // Eclipse's built-in defaults.
  EclipseFormatEngine(final String profile, final List<String> classpath) {
    this.profile = profile;
    this.classpath = List.copyOf(classpath);
  }

  @Override
  public String format(final String source, final Path file) throws Exception {
    final Jdt loaded = jdt();
    final boolean moduleInfo = file != null && MODULE_INFO.equals(file.getFileName().toString());
    final int kind = moduleInfo ? loaded.moduleInfoKind() : loaded.compilationUnitKind();
    return FormatEngine.converge(
        source,
        current -> loaded.format(kind, current, List.of(new TextRange(0, current.length()))));
  }

  @Override
  public void warmUp() throws Exception {
    format(WARM_UP_SOURCE, WARM_UP_FILE);
  }

  @Override
  public boolean formatsRanges() {
    return true;
  }

  @Override
  public String formatRanges(final String source, final List<TextRange> ranges) throws Exception {
    final Jdt loaded = jdt();
    return loaded.format(loaded.compilationUnitKind(), source, ranges);
  }

  private synchronized Jdt jdt() throws Exception {
    if (jdt == null) {
      jdt = load();
    }

    return jdt;
  }

  private Jdt load() throws Exception {
    final ClassLoader loader = FormatterLoader.isolated(classpath);
    final Class<?> formatterType = loader.loadClass("org.eclipse.jdt.core.formatter.CodeFormatter");
    final Class<?> regionType = loader.loadClass("org.eclipse.jface.text.IRegion");
    final Class<?> documentType = loader.loadClass("org.eclipse.jface.text.Document");
    final Object formatter =
        loader
            .loadClass("org.eclipse.jdt.core.ToolFactory")
            .getMethod("createCodeFormatter", Map.class)
            .invoke(null, settings(profile));
    return new Jdt(
        formatter,
        formatterType.getMethod(
            "format", int.class, String.class, regionType.arrayType(), int.class, String.class),
        regionType,
        loader.loadClass("org.eclipse.jface.text.Region").getConstructor(int.class, int.class),
        documentType.getConstructor(String.class),
        loader
            .loadClass("org.eclipse.text.edits.TextEdit")
            .getMethod("apply", loader.loadClass("org.eclipse.jface.text.IDocument")),
        documentType.getMethod("get"),
        formatterType.getField("K_COMPILATION_UNIT").getInt(null),
        formatterType.getField("K_MODULE_INFO").getInt(null),
        formatterType.getField("F_INCLUDE_COMMENTS").getInt(null));
  }

  // The profile's formatter settings, read as Spotless reads them: an Eclipse export's <setting>
  // entries, else a properties file (.prefs, or properties XML).
  private static Map<String, String> settings(final String profile) throws Exception {
    if (profile.isEmpty()) {
      return Map.of();
    }

    final Path file = Path.of(profile);
    if (!profile.endsWith(".xml")) {
      return properties(file, Properties::load);
    }

    final var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    final Element root = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
    if (!PROFILES_ELEMENT.equals(root.getTagName())) {
      return properties(file, Properties::loadFromXML);
    }

    final NodeList settings = root.getElementsByTagName(SETTING_ELEMENT);
    return IntStream.range(0, settings.getLength())
        .mapToObj(i -> (Element) settings.item(i))
        .collect(
            Collectors.toUnmodifiableMap(
                setting -> setting.getAttribute("id"),
                setting -> setting.getAttribute("value"),
                (first, last) -> last));
  }

  private static Map<String, String> properties(final Path file, final PropertiesLoader loader)
      throws IOException {
    final var properties = new Properties();
    try (InputStream in = Files.newInputStream(file)) {
      loader.load(properties, in);
    }

    return properties.stringPropertyNames().stream()
        .collect(Collectors.toUnmodifiableMap(name -> name, properties::getProperty));
  }

  @FunctionalInterface
  private interface PropertiesLoader {
    void load(Properties properties, InputStream in) throws IOException;
  }

  // The loaded formatter and the JDT entry points format() needs.
  private record Jdt(
      Object formatter,
      Method format,
      Class<?> regionType,
      Constructor<?> region,
      Constructor<?> document,
      Method apply,
      Method text,
      int compilationUnitKind,
      int moduleInfoKind,
      int includeComments) {

    private Jdt {
      ValidCheck.check()
          .notNull(formatter, "formatter")
          .notNull(format, "format")
          .notNull(regionType, "regionType")
          .notNull(region, "region")
          .notNull(document, "document")
          .notNull(apply, "apply")
          .notNull(text, "text")
          .validate();
    }

    // Formats the given regions of source; JDT returns no edit when the source does not parse.
    String format(final int kind, final String source, final List<TextRange> ranges)
        throws Exception {
      final Object regions = Array.newInstance(regionType, ranges.size());
      for (int i = 0; i < ranges.size(); i++) {
        final TextRange range = ranges.get(i);
        Array.set(regions, i, region.newInstance(range.start(), range.end() - range.start()));
      }

      final Object edit =
          invoke(format, formatter, kind | includeComments, source, regions, 0, LINE_DELIMITER);
      if (edit == null) {
        throw new IllegalArgumentException("Invalid Java syntax for formatting");
      }

      final Object formatted = document.newInstance(source);
      invoke(apply, edit, formatted);
      return (String) invoke(text, formatted);
    }
  }
}
