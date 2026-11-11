package com.example.declared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

// lathe:sync runs before the tests: with no Spotless in the build, it completes the formatter
// lathe-style.json declares from the Spotless release Lathe pins, and resolves its jars.
class DeclaredFormatterSyncTest {

  private static final Path ROOT = Path.of(System.getProperty("user.dir"));

  @Test
  void sync_declaredAospWithoutSpotless_resolvesJarsIndentAndGrants() throws IOException {
    final String style = Files.readString(lathe("style.json"));

    assertThat(style).contains("\"engine\": \"aosp\"");
    assertThat(style).containsPattern("\"version\": \"\\d+\\.\\d+(\\.\\d+)?\"");
    assertThat(style).contains("\"style\": \"AOSP\"");
    assertThat(style).containsPattern("\"[^\"]+google-java-format-[^\"]+\\.jar\"");
    assertThat(style).contains("\"block\": 4");
    assertThat(Files.readAllLines(lathe("jvm.args")))
        .contains("--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED");
    assertThat(Files.readString(lathe("workspace.json"))).contains("\"lathe-style.json\"");
  }

  private static Path lathe(final String file) {
    return ROOT.resolve(".lathe").resolve(file);
  }
}
