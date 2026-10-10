package com.example.formatters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

// lathe:sync runs before the tests, so .lathe/ holds what it wrote for the active profile's
// Spotless formatter: the engine, the release that Spotless version pins, and its resolved jars.
class FormatterSyncTest {

  private static final Path ROOT = Path.of(System.getProperty("user.dir"));
  private static final String ENGINE = System.getProperty("expected.engine");

  @Test
  void sync_spotlessPalantir_resolvesPinnedJarsAndGrantsJavac() throws IOException {
    assumeTrue("palantir".equals(ENGINE), "palantir profile only");
    final String style = Files.readString(lathe("style.json"));

    assertThat(style).contains("\"engine\": \"palantir\"");
    assertThat(style).containsPattern("\"version\": \"\\d+\\.\\d+\\.\\d+\"");
    assertThat(style).contains("\"style\": \"PALANTIR\"");
    assertThat(style).containsPattern("\"[^\"]+palantir-java-format-[^\"]+\\.jar\"");
    assertThat(Files.readAllLines(lathe("jvm.args")))
        .containsExactly(
            "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED");
  }

  // Runs after the palantir build in the same directory, so the missing jvm.args also shows that
  // sync removes grants no formatter needs any more.
  @Test
  void sync_spotlessEclipse_resolvesLockfileJarsWithAbsoluteProfile() throws IOException {
    assumeTrue("eclipse".equals(ENGINE), "eclipse profile only");
    final String style = Files.readString(lathe("style.json"));

    assertThat(style).contains("\"engine\": \"eclipse\"");
    assertThat(style).containsPattern("\"version\": \"4\\.\\d+\"");
    assertThat(style)
        .contains(
            "\"file\": \"%s\"".formatted(ROOT.resolve("eclipse-formatter.xml").toAbsolutePath()));
    assertThat(style).containsPattern("\"[^\"]+org\\.eclipse\\.jdt\\.core-[^\"]+\\.jar\"");
    assertThat(lathe("jvm.args")).doesNotExist();
  }

  private static Path lathe(final String file) {
    return ROOT.resolve(".lathe").resolve(file);
  }
}
