package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceFinderTest {

  @TempDir private Path tmp;

  @Test
  void resources_reactorDirAndDependencyJar_tagsOriginsAndSkipsClasses() throws Exception {
    final var resourceRoot = tmp.resolve("app/src/main/resources");
    Files.createDirectories(resourceRoot.resolve("com/x"));
    Files.writeString(resourceRoot.resolve("com/x/schema.graphqls"), "type Query");
    final var jar =
        writeJar(
            tmp.resolve("lib.jar"),
            Map.of("com/y/config.xml", "<c/>", "com/y/App.class", "bytecode"));

    final List<ResourceEntry> entries =
        ResourceFinder.list(Map.of(resourceRoot, "app"), Map.of(jar, "g:a:1"));

    assertThat(entries)
        .containsExactlyInAnyOrder(
            // reactor file: editable path, origin from the captured module; App.class excluded
            ResourceEntry.reactor(
                "com/x/schema.graphqls",
                "app",
                resourceRoot.resolve("com/x/schema.graphqls").toString()),
            ResourceEntry.dependency("g:a:1", jar.toString(), "com/y/config.xml"));
  }

  private static Path writeJar(final Path path, final Map<String, String> entries)
      throws IOException {
    try (final var out = new ZipOutputStream(Files.newOutputStream(path))) {
      for (final Map.Entry<String, String> entry : entries.entrySet()) {
        out.putNextEntry(new ZipEntry(entry.getKey()));
        out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }

    return path;
  }
}
