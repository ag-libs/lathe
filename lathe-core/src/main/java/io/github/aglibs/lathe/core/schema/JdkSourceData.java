package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.validcheck.ValidCheck;
import java.nio.file.Path;

public record JdkSourceData(
    String vendor,
    String version,
    SourceStatus status,
    Path home,
    Path sourceZip,
    Path sourceDir,
    Path typeIndex) {

  public JdkSourceData {
    ValidCheck.check()
        .notNull(status, "status")
        .notBlank(vendor, "vendor")
        .notBlank(version, "version")
        .when(
            status == SourceStatus.PRESENT,
            v -> v.notNull(home, "home").notNull(sourceDir, "sourceDir"))
        .validate();
  }

  public static JdkSourceData present(
      final String vendor,
      final String version,
      final Path home,
      final Path sourceZip,
      final Path sourceDir) {
    return new JdkSourceData(
        vendor, version, SourceStatus.PRESENT, home, sourceZip, sourceDir, null);
  }

  public static JdkSourceData missing(final String vendor, final String version, final Path home) {
    return new JdkSourceData(vendor, version, SourceStatus.MISSING, home, null, null, null);
  }

  public boolean isPresent() {
    return status == SourceStatus.PRESENT;
  }
}
