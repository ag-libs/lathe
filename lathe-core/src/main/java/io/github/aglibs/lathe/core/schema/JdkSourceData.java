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
}
