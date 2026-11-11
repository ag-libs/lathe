package io.github.aglibs.lathe.install;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.FormatterSpec;
import io.github.aglibs.lathe.core.schema.IndentSpec;
import io.github.aglibs.lathe.core.schema.WorkspaceStyleData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StyleWriterTest {

  @TempDir Path workspaceRoot;

  @Test
  void write_styleWrittenOnce_unchangedStyleLeavesTheFileAlone() throws Exception {
    final var style =
        new WorkspaceStyleData(
            new FormatterSpec("google", List.of(), "1.24.0", Map.of(), List.of("/repo/gjf.jar")),
            new IndentSpec("google", 2, 4));
    StyleWriter.write(workspaceRoot, style);
    final Path file = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.STYLE_FILE);
    final var stamp = FileTime.fromMillis(1_000_000L);
    Files.setLastModifiedTime(file, stamp);

    StyleWriter.write(workspaceRoot, style);

    assertThat(Files.getLastModifiedTime(file)).isEqualTo(stamp);
    assertThat(Json.read(file, WorkspaceStyleData.class)).isEqualTo(style);
  }

  // The server runs the formatter on .lathe/java-home's JDK: a release built for a newer Java is
  // named with both remedies, one that runs there is not, and nothing is said without a JDK to
  // compare with.
  @Test
  void unrunnable_formatterNewerThanTheServerJdk_namesBothRemedies() throws Exception {
    final FormatterSpec needs21 = google("1.36.1", formatterJar("gjf-21.jar", 65));
    final FormatterSpec needs17 = google("1.28.0", formatterJar("gjf-17.jar", 61));

    assertThat(StyleWriter.unrunnable(workspaceRoot, needs21)).isEmpty();

    javaHome("17.0.12");

    assertThat(StyleWriter.unrunnable(workspaceRoot, needs21))
        .hasValueSatisfying(
            warning ->
                assertThat(warning)
                    .contains("google 1.36.1 needs Java 21")
                    .contains("server runs on Java 17")
                    .contains(LatheLayout.STYLE_SHARED_FILE)
                    .contains("LATHE_JAVA_HOME"));
    assertThat(StyleWriter.unrunnable(workspaceRoot, needs17)).isEmpty();
    assertThat(StyleWriter.unrunnable(workspaceRoot, new FormatterSpec("none", List.of())))
        .isEmpty();
  }

  private FormatterSpec google(final String version, final Path jar) {
    return new FormatterSpec("google", List.of(), version, Map.of(), List.of(jar.toString()));
  }

  // A formatter jar holding only its main class's header: the class-file version is all the check
  // reads.
  private Path formatterJar(final String name, final int major) throws Exception {
    final Path jar = workspaceRoot.resolve(name);
    try (var out = new ZipOutputStream(Files.newOutputStream(jar))) {
      out.putNextEntry(new ZipEntry("com/google/googlejavaformat/java/Formatter.class"));
      out.write(
          new byte[] {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe, 0, 0, 0, (byte) major});
      out.closeEntry();
    }

    return jar;
  }

  private void javaHome(final String version) throws Exception {
    final Path jdk = Files.createDirectories(workspaceRoot.resolve("jdk"));
    Files.writeString(jdk.resolve("release"), "JAVA_VERSION=\"%s\"%n".formatted(version));
    final Path latheDir = Files.createDirectories(workspaceRoot.resolve(LatheLayout.LATHE_DIR));
    Files.writeString(latheDir.resolve(LatheLayout.JAVA_HOME_FILE), jdk.toString());
  }

  @Test
  void delete_writtenStyle_removesItAndToleratesNone() {
    StyleWriter.write(
        workspaceRoot,
        new WorkspaceStyleData(
            new FormatterSpec("google", List.of()), new IndentSpec("google", 2, 4)));

    StyleWriter.delete(workspaceRoot);
    StyleWriter.delete(workspaceRoot);

    assertThat(workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.STYLE_FILE))
        .doesNotExist();
  }
}
