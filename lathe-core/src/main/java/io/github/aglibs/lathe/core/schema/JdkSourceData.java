package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.validcheck.ValidCheck;
import java.nio.file.Path;
import java.util.Optional;

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

  // Why go-to-definition into the JDK finds no source, and how to fix it, when the build found
  // none: the archive looked for, or that no JDK home was found. Empty when the sources are there.
  public Optional<String> missingSourcesNote() {
    if (status != SourceStatus.MISSING) {
      return Optional.empty();
    }

    final String cause =
        home == null
            ? "no JDK home was found (JAVA_HOME is unset and the JVM reports none)"
            : "%s was not found".formatted(home.resolve(LatheLayout.JDK_SOURCE_ZIP));
    // A package name only when the version gives a real one; `unknown` (a JDK home without a
    // `release` file) or a legacy `1.x` would name a package that does not exist.
    final String major = version.split("[.+-]")[0];
    final String sourcePackage =
        major.matches("\\d+") && Integer.parseInt(major) >= 9
            ? "the openjdk-%s-source package on Debian/Ubuntu".formatted(major)
            : "the JDK's source package";
    return Optional.of(
        "JDK sources are missing: %s, so go-to-definition into the JDK has no source. Install the JDK's sources (%s, or the source option of the JDK's installer) or build with a JDK that has %s (JAVA_HOME, or the Gradle toolchain), then sync again (`%s`)."
            .formatted(
                cause,
                sourcePackage,
                FileUtil.slashed(LatheLayout.JDK_SOURCE_ZIP),
                LatheLayout.SYNC_SCRIPT_COMMAND));
  }
}
