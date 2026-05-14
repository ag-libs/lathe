package io.github.aglibs.lathe.core.maven;

import java.nio.file.Path;

public record JdkSourceEntry(
    String vendor, String version, String status, Path home, Path sourceZip, Path sourceDir) {

  public static JdkSourceEntry present(
      final String vendor,
      final String version,
      final Path home,
      final Path sourceZip,
      final Path sourceDir) {
    return new JdkSourceEntry(vendor, version, "present", home, sourceZip, sourceDir);
  }

  public static JdkSourceEntry missing(final String vendor, final String version, final Path home) {
    return new JdkSourceEntry(vendor, version, "missing", home, null, null);
  }

  public boolean isPresent() {
    return "present".equals(status);
  }
}
