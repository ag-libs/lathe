package io.github.aglibs.lathe.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class JdkSourceDataTest {

  @Test
  void missingSourcesNote_homeKnown_namesTheArchiveAndTheRemedy() {
    final Path home = Path.of("jdk-21");
    final var data =
        new JdkSourceData(
            "Eclipse Adoptium", "21.0.2", SourceStatus.MISSING, home, null, null, null);

    assertThat(data.missingSourcesNote())
        .hasValueSatisfying(
            note ->
                assertThat(note)
                    .startsWith("JDK sources are missing: ")
                    .contains(home.resolve(LatheLayout.JDK_SOURCE_ZIP) + " was not found")
                    .contains("openjdk-21-source")
                    .contains(
                        "build with a JDK that has lib/src.zip (JAVA_HOME, or the Gradle toolchain)")
                    .contains(LatheLayout.SYNC_SCRIPT_COMMAND));
  }

  @Test
  void missingSourcesNote_homeUnknown_saysNoHomeWasFound() {
    final var data =
        new JdkSourceData("Oracle", "25", SourceStatus.MISSING, null, null, null, null);

    assertThat(data.missingSourcesNote())
        .hasValueSatisfying(
            note ->
                assertThat(note)
                    .contains("no JDK home was found")
                    .doesNotContain("was not found,"));
  }

  @Test
  void missingSourcesNote_versionWithoutAMajor_namesNoPackage() {
    final var data =
        new JdkSourceData(
            "Vendor", "unknown", SourceStatus.MISSING, Path.of("jdk"), null, null, null);

    assertThat(data.missingSourcesNote())
        .hasValueSatisfying(
            note ->
                assertThat(note).contains("the JDK's source package").doesNotContain("openjdk-"));
  }

  @Test
  void missingSourcesNote_sourcesPresent_isEmpty() {
    final Path home = Path.of("jdk-21");
    final var data =
        new JdkSourceData(
            "Oracle",
            "21",
            SourceStatus.PRESENT,
            home,
            home.resolve(LatheLayout.JDK_SOURCE_ZIP),
            Path.of("cache"),
            null);

    assertThat(data.missingSourcesNote()).isEmpty();
  }
}
