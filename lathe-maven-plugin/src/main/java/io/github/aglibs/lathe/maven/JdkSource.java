package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.maven.JdkSourceEntry;
import java.nio.file.Path;

record JdkSource(
    String vendor, String version, String status, Path home, Path sourceZip, Path sourceDir) {

  static JdkSource present(
      final String vendor,
      final String version,
      final Path home,
      final Path sourceZip,
      final Path sourceDir) {
    return new JdkSource(vendor, version, "present", home, sourceZip, sourceDir);
  }

  static JdkSource missing(final String vendor, final String version, final Path home) {
    return new JdkSource(vendor, version, "missing", home, null, null);
  }

  boolean isPresent() {
    return "present".equals(status);
  }

  JdkSourceEntry toEntry() {
    return new JdkSourceEntry(vendor, version, status, home, sourceZip, sourceDir);
  }
}
