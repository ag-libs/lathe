package io.github.aglibs.lathe.core.schema;

import java.nio.file.Path;

public record JdkSourceData(
    String vendor, String version, String status, Path home, Path sourceZip, Path sourceDir) {

  public static JdkSourceData present(
      final String vendor,
      final String version,
      final Path home,
      final Path sourceZip,
      final Path sourceDir) {
    return new JdkSourceData(vendor, version, "present", home, sourceZip, sourceDir);
  }

  public static JdkSourceData missing(final String vendor, final String version, final Path home) {
    return new JdkSourceData(vendor, version, "missing", home, null, null);
  }

  public boolean isPresent() {
    return "present".equals(status);
  }
}
