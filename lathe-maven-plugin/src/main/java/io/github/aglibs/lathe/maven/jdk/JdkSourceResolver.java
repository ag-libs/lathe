package io.github.aglibs.lathe.maven.jdk;

import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class JdkSourceResolver {

  private JdkSourceResolver() {}

  public static JdkSource resolve() {
    return resolve(System.getenv());
  }

  public static JdkSource resolve(final Map<String, String> env) {
    final var vendor = System.getProperty("java.vendor");
    final var version = System.getProperty("java.version");
    final var javaHome = env.get("JAVA_HOME");
    if (javaHome == null) {
      return JdkSource.missing(vendor, version, null);
    }

    final var home = Path.of(javaHome);
    final var sourceZip = home.resolve("lib").resolve("src.zip");
    final var sourceDir =
        LatheLayout.userCacheRoot()
            .resolve(LatheLayout.CACHE_JDKS_DIR)
            .resolve(sanitize(vendor))
            .resolve(sanitize(version));
    if (Files.exists(sourceZip)) {
      return JdkSource.present(vendor, version, home, sourceZip, sourceDir);
    }

    return JdkSource.missing(vendor, version, home);
  }

  private static String sanitize(final String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("-+", "-");
  }
}
