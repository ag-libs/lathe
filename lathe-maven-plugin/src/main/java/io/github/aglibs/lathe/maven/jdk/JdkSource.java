package io.github.aglibs.lathe.maven.jdk;

import io.github.aglibs.lathe.core.schema.JdkSourceData;
import io.github.aglibs.lathe.core.schema.SourceStatus;
import io.github.aglibs.validcheck.ValidCheck;
import java.nio.file.Path;

public record JdkSource(
    String vendor,
    String version,
    String cacheKey,
    SourceStatus status,
    Path home,
    Path sourceZip,
    Path sourceDir,
    Path typeIndex) {

  public JdkSource {
    ValidCheck.check()
        .notNull(status, "status")
        .notBlank(vendor, "vendor")
        .notBlank(version, "version")
        .notBlank(cacheKey, "cacheKey")
        .when(
            status == SourceStatus.PRESENT,
            v ->
                v.notNull(home, "home")
                    .notNull(sourceZip, "sourceZip")
                    .notNull(sourceDir, "sourceDir"))
        .validate();
  }

  public static JdkSource present(
      final String vendor,
      final String version,
      final String cacheKey,
      final Path home,
      final Path sourceZip,
      final Path sourceDir) {
    return new JdkSource(
        vendor, version, cacheKey, SourceStatus.PRESENT, home, sourceZip, sourceDir, null);
  }

  public static JdkSource missing(
      final String vendor, final String version, final String cacheKey, final Path home) {
    return new JdkSource(vendor, version, cacheKey, SourceStatus.MISSING, home, null, null, null);
  }

  public boolean isPresent() {
    return status == SourceStatus.PRESENT;
  }

  public JdkSource withTypeIndex(final Path typeIndexPath) {
    return new JdkSource(
        vendor, version, cacheKey, status, home, sourceZip, sourceDir, typeIndexPath);
  }

  public JdkSourceData toData() {
    return new JdkSourceData(vendor, version, status, home, sourceZip, sourceDir, typeIndex);
  }
}
