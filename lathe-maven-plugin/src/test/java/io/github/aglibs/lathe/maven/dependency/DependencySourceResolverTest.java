package io.github.aglibs.lathe.maven.dependency;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.schema.SourceStatus;
import io.github.aglibs.lathe.install.dependency.DependencySource;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.transfer.ArtifactNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencySourceResolverTest {

  @TempDir Path tmp;

  // Each result of the batch maps back to its binary: a resolved sources jar is present, extracted
  // under the artifact's cache dir; one the repositories lack is missing, the binary kept.
  @Test
  void toSource_resolvedOrMissingResult_mapsToPresentOrMissing() {
    final Artifact binary = binary("lib");
    final List<Path> classpath = List.of(tmp.resolve("dep.jar"));
    final Path sourcesJar = tmp.resolve("lib-1.0-sources.jar");
    final var sources =
        new org.eclipse.aether.artifact.DefaultArtifact("com.example:lib:jar:sources:1.0")
            .setFile(sourcesJar.toFile());
    final var resolved = new ArtifactResult(new ArtifactRequest());
    resolved.setArtifact(sources);
    final var notFound = new ArtifactResult(new ArtifactRequest());
    notFound.addException(new ArtifactNotFoundException(sources, null));

    final DependencySource present =
        DependencySourceResolver.toSource(binary, resolved, classpath, new SystemStreamLog());
    final DependencySource missing =
        DependencySourceResolver.toSource(
            binary("gone"), notFound, List.of(), new SystemStreamLog());

    assertThat(present.status()).isEqualTo(SourceStatus.PRESENT);
    assertThat(present.gav()).isEqualTo("com.example:lib:1.0");
    assertThat(present.jar()).isEqualTo(binary.getFile().toPath());
    assertThat(present.dir()).endsWithRaw(Path.of("com.example", "lib", "1.0"));
    assertThat(present.sourceArtifact().jar()).isEqualTo(sourcesJar);
    assertThat(present.classpath()).isEqualTo(classpath);
    assertThat(missing.status()).isEqualTo(SourceStatus.MISSING);
    assertThat(missing.gav()).isEqualTo("com.example:gone:1.0");
    assertThat(missing.jar()).isEqualTo(tmp.resolve("gone-1.0.jar"));
    assertThat(missing.dir()).isNull();
  }

  private Artifact binary(final String artifactId) {
    final Artifact artifact =
        new DefaultArtifact(
            "com.example", artifactId, "1.0", Artifact.SCOPE_COMPILE, "jar", "", null);
    artifact.setFile(tmp.resolve("%s-1.0.jar".formatted(artifactId)).toFile());
    return artifact;
  }
}
