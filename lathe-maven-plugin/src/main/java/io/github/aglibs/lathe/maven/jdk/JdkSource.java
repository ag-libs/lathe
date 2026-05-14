package io.github.aglibs.lathe.maven.jdk;

import io.github.aglibs.lathe.core.schema.JdkSourceData;
import java.nio.file.Path;

public record JdkSource(
    String vendor, String version, String status, Path home, Path sourceZip, Path sourceDir) {

  public static JdkSource present(
      final String vendor,
      final String version,
      final Path home,
      final Path sourceZip,
      final Path sourceDir) {
    return new JdkSource(vendor, version, "present", home, sourceZip, sourceDir);
  }

  public static JdkSource missing(final String vendor, final String version, final Path home) {
    return new JdkSource(vendor, version, "missing", home, null, null);
  }

  public boolean isPresent() {
    return "present".equals(status);
  }

  public JdkSourceData toData() {
    return new JdkSourceData(vendor, version, status, home, sourceZip, sourceDir);
  }
}
