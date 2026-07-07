package io.github.aglibs.lathe.core;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public final class LatheBuildInfo {

  private static final String UNKNOWN = "unknown";
  private static final Attributes.Name BUILD_TIME = new Attributes.Name("Lathe-Build-Time");
  private static final Attributes.Name GIT_COMMIT = new Attributes.Name("Lathe-Git-Commit");

  private LatheBuildInfo() {}

  public static String summary(final Class<?> type) {
    final Attributes attributes = manifestAttributes(Objects.requireNonNull(type));
    return "version=%s git=%s built=%s"
        .formatted(
            value(attributes, Attributes.Name.IMPLEMENTATION_VERSION),
            value(attributes, GIT_COMMIT),
            value(attributes, BUILD_TIME));
  }

  private static String value(final Attributes attributes, final Attributes.Name name) {
    final String value = attributes.getValue(name);
    return value == null || value.isBlank() ? UNKNOWN : value;
  }

  private static Attributes manifestAttributes(final Class<?> type) {
    try {
      final var source = Objects.requireNonNull(type.getProtectionDomain().getCodeSource());
      final Path location = Path.of(source.getLocation().toURI());
      if (!Files.isRegularFile(location)) {
        return new Attributes();
      }

      try (final var jar = new JarFile(location.toFile())) {
        final Manifest manifest = jar.getManifest();
        if (manifest == null) {
          return new Attributes();
        }

        return manifest.getMainAttributes();
      }
    } catch (final IOException
        | IllegalArgumentException
        | NullPointerException
        | SecurityException
        | URISyntaxException e) {
      return new Attributes();
    }
  }
}
