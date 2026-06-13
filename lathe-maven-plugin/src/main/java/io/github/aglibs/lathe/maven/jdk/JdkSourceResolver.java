package io.github.aglibs.lathe.maven.jdk;

import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class JdkSourceResolver {

  private static final String IMPLEMENTOR_VERSION = "IMPLEMENTOR_VERSION";
  private static final String IMPLEMENTOR = "IMPLEMENTOR";
  private static final String JAVA_VERSION = "JAVA_VERSION";
  private static final List<String> LEGAL_SUFFIXES =
      List.of(" Corporation", " Corp.", " Corp", " Inc.", " Inc", " Ltd.", " LLC");

  private JdkSourceResolver() {}

  public static JdkSource resolve() {
    return resolve(System.getenv());
  }

  public static JdkSource resolve(final Map<String, String> env) {
    final var vendor = System.getProperty("java.vendor");
    final var version = System.getProperty("java.version");
    final var javaHome = env.get("JAVA_HOME");
    if (javaHome == null) {
      return JdkSource.missing(vendor, version, cacheKey(null, vendor, version), null);
    }

    final var home = Path.of(javaHome);
    final var key = cacheKey(home, vendor, version);
    final var sourceZip = home.resolve("lib").resolve("src.zip");
    final var sourceDir =
        LatheLayout.userCacheRoot().resolve(LatheLayout.CACHE_JDKS_DIR).resolve(key);
    if (Files.exists(sourceZip)) {
      return JdkSource.present(vendor, version, key, home, sourceZip, sourceDir);
    }

    return JdkSource.missing(vendor, version, key, home);
  }

  static String cacheKey(final Path home, final String vendor, final String version) {
    final var release = parseReleaseFile(home);

    final var implementorVersion = release.get(IMPLEMENTOR_VERSION);
    if (implementorVersion != null && !implementorVersion.isBlank()) {
      return sanitize(implementorVersion);
    }

    final var implementor = release.get(IMPLEMENTOR);
    final var javaVersion = release.get(JAVA_VERSION);
    if (implementor != null && javaVersion != null) {
      return sanitize(stripLegalSuffix(implementor) + "-" + javaVersion);
    }

    final var vendorVersion = System.getProperty("java.vendor.version");
    if (vendorVersion != null && !vendorVersion.isBlank()) {
      return sanitize(vendorVersion);
    }

    return sanitize(stripLegalSuffix(vendor) + "-" + version);
  }

  private static String stripLegalSuffix(final String implementor) {
    return LEGAL_SUFFIXES.stream()
        .filter(implementor::endsWith)
        .findFirst()
        .map(s -> implementor.substring(0, implementor.length() - s.length()).trim())
        .orElse(implementor);
  }

  private static String sanitize(final String value) {
    return LatheLayout.cacheName(value).toLowerCase(Locale.ROOT);
  }

  private static Map<String, String> parseReleaseFile(final Path home) {
    if (home == null) {
      return Map.of();
    }

    final var file = home.resolve("release");
    if (!Files.exists(file)) {
      return Map.of();
    }

    try {
      return Files.readAllLines(file).stream()
          .filter(line -> line.contains("="))
          .collect(
              Collectors.toMap(
                  line -> line.substring(0, line.indexOf('=')).trim(),
                  line -> unquote(line.substring(line.indexOf('=') + 1).trim()),
                  (a, b) -> a));
    } catch (final IOException e) {
      return Map.of();
    }
  }

  private static String unquote(final String value) {
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      return value.substring(1, value.length() - 1);
    }

    return value;
  }
}
