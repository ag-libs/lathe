package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.ParamStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencySourceExtractorTest {

  @TempDir Path tmp;

  @Test
  void extract_writesMarkerAfterSuccess() throws IOException {
    final DependencySource source = presentSource();

    DependencySourceExtractor.extract(List.of(source), new SystemStreamLog());

    final ParamStore marker = ParamStore.load(source.dir().resolve(".lathe-source.properties"));
    assertThat(marker.get("schema")).isEqualTo(LatheLayout.SCHEMA_VERSION);
    assertThat(marker.get("gav")).isEqualTo("com.example:dep:1.0");
    assertThat(marker.get("sourceJar")).isEqualTo(source.jar().toString());
  }

  @Test
  void extract_skipsWhenMarkerMatches() throws IOException {
    final DependencySource source = presentSource();

    DependencySourceExtractor.extract(List.of(source), new SystemStreamLog());
    Files.writeString(source.dir().resolve("sentinel"), "unchanged");

    DependencySourceExtractor.extract(List.of(source), new SystemStreamLog());

    assertThat(source.dir().resolve("sentinel")).hasContent("unchanged");
  }

  private DependencySource presentSource() throws IOException {
    final Path jar =
        ZipFixture.create(tmp.resolve("dep-sources.jar"), "com/example/A.java", "class A {}");
    final Artifact artifact = new DefaultArtifact("com.example:dep:1.0").setFile(jar.toFile());
    return DependencySource.present(
        "com.example:dep:1.0", jar, tmp.resolve("cache/dep"), artifact, List.of());
  }
}
