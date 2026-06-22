package io.github.aglibs.lathe.maven.dependency;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.maven.ZipFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencySourceSyncTest {

  @TempDir Path tmp;

  @Test
  void extract_writesMarkerAfterSuccess() throws IOException {
    final DependencySource source = presentSource();

    DependencySourceSync.extract(List.of(source), new SystemStreamLog());

    final var content = Files.readString(source.dir().resolve(".lathe-source.json"));
    assertThat(content).contains("\"schema\"").contains("\"" + LatheLayout.SCHEMA_VERSION + "\"");
    assertThat(content).contains("\"gav\"").contains("com.example:dep:1.0");
    assertThat(content).contains("\"sourceJar\"");
    final var marker = Json.fromJson(content, MarkerSnapshot.class);
    assertThat(marker.schema()).isEqualTo(LatheLayout.SCHEMA_VERSION);
    assertThat(marker.gav()).isEqualTo("com.example:dep:1.0");
    assertThat(marker.sourceJar()).isEqualTo(source.jar().toString());
  }

  @Test
  void extract_skipsWhenMarkerMatches() throws IOException {
    final DependencySource source = presentSource();

    DependencySourceSync.extract(List.of(source), new SystemStreamLog());
    Files.writeString(source.dir().resolve("sentinel"), "unchanged");

    DependencySourceSync.extract(List.of(source), new SystemStreamLog());

    assertThat(source.dir().resolve("sentinel")).hasContent("unchanged");
  }

  private DependencySource presentSource() throws IOException {
    final Path jar =
        ZipFixture.create(tmp.resolve("dep-sources.jar"), "com/example/A.java", "class A {}");
    final var artifact = new DefaultArtifact("com.example:dep:1.0").setFile(jar.toFile());
    return DependencySource.present(
        "com.example:dep:1.0", jar, tmp.resolve("cache/dep"), artifact, List.of());
  }

  private record MarkerSnapshot(String schema, String gav, String sourceJar) {}
}
