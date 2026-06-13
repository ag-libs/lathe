package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.SourceStatus;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import io.github.aglibs.lathe.maven.dependency.DependencySource;
import io.github.aglibs.lathe.maven.jdk.JdkSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceManifestWriterTest {

  @TempDir Path workspaceRoot;

  private WorkspaceManifestWriter writer() {
    return new WorkspaceManifestWriter(new SystemStreamLog());
  }

  @Test
  void write_writesWorkspaceJsonAtomically() throws Exception {
    final var dep =
        DependencySource.present(
            "com.example:dep:1",
            Path.of("/repo/dep.jar"),
            Path.of("/cache/dep"),
            null,
            List.of(Path.of("/repo/transitive.jar")));
    final var jdk =
        JdkSource.present(
                "Example Vendor",
                "21.0.1",
                "example-vendor-21.0.1",
                Path.of("/jdk"),
                Path.of("/jdk/lib/src.zip"),
                Path.of("/cache/jdks/example-vendor-21.0.1"))
            .withTypeIndex(Path.of("/cache/type-index/jdks/example-vendor-21.0.1/index.json"));

    writer().write(workspaceRoot, List.of(dep), jdk, null, List.of("pom.xml"));

    final var manifest =
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON);
    final var data = Json.read(manifest, WorkspaceManifestData.class);
    assertThat(data.schemaVersion()).isEqualTo(LatheLayout.SCHEMA_VERSION);
    assertThat(data.workspaceRoot()).isEqualTo(workspaceRoot.toString());
    assertThat(data.jdk().home().toString()).isEqualTo("/jdk");
    assertThat(data.jdk().vendor()).isEqualTo("Example Vendor");
    assertThat(data.jdk().version()).isEqualTo("21.0.1");
    assertThat(data.jdk().status()).isEqualTo(SourceStatus.PRESENT);
    assertThat(data.jdk().sourceDir().toString()).isEqualTo("/cache/jdks/example-vendor-21.0.1");
    assertThat(data.jdk().typeIndex().toString())
        .isEqualTo("/cache/type-index/jdks/example-vendor-21.0.1/index.json");
    assertThat(data.dependencySources()).hasSize(1);
    final var entry = data.dependencySources().getFirst();
    assertThat(entry.gav()).isEqualTo("com.example:dep:1");
    assertThat(entry.jar()).isEqualTo("/repo/dep.jar");
    assertThat(entry.status()).isEqualTo(SourceStatus.PRESENT);
    assertThat(entry.dir()).isEqualTo("/cache/dep");
    assertThat(entry.classpath()).containsExactly("/repo/transitive.jar");
    assertThat(data.pomPaths()).containsExactly("pom.xml");
  }

  @Test
  void write_missingJdkSource_hasNullSourceDir() throws Exception {
    final var jdk =
        JdkSource.missing("Example Vendor", "21.0.1", "example-vendor-21.0.1", Path.of("/jdk"));

    writer().write(workspaceRoot, List.of(), jdk, null, List.of("pom.xml"));

    final var manifest =
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON);
    final var data = Json.read(manifest, WorkspaceManifestData.class);
    assertThat(data.jdk().version()).isEqualTo("21.0.1");
    assertThat(data.jdk().status()).isEqualTo(SourceStatus.MISSING);
    assertThat(data.jdk().sourceDir()).isNull();
  }

  @Test
  void write_sameContent_skipsWrite() throws Exception {
    final var jdk = JdkSource.missing("Vendor", "21", "vendor-21", Path.of("/jdk"));
    writer().write(workspaceRoot, List.of(), jdk, null, List.of("pom.xml"));

    final var manifest =
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON);
    final var sentinel = FileTime.fromMillis(1_000_000L);
    Files.setLastModifiedTime(manifest, sentinel);

    writer().write(workspaceRoot, List.of(), jdk, null, List.of("pom.xml"));

    assertThat(Files.getLastModifiedTime(manifest)).isEqualTo(sentinel);
  }

  @Test
  void write_multipleDependencies_writesAllEntries() throws Exception {
    final var deps =
        List.of(
            DependencySource.present(
                "com.example:a:1", Path.of("/repo/a.jar"), Path.of("/cache/a"), null, List.of()),
            DependencySource.present(
                "com.example:b:2", Path.of("/repo/b.jar"), Path.of("/cache/b"), null, List.of()));
    final var jdk =
        JdkSource.present(
            "Vendor",
            "21",
            "vendor-21",
            Path.of("/jdk"),
            Path.of("/jdk/src.zip"),
            Path.of("/cache/jdk"));

    writer().write(workspaceRoot, deps, jdk, null, List.of("pom.xml"));

    final var manifest =
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON);
    final var data = Json.read(manifest, WorkspaceManifestData.class);
    assertThat(data.dependencySources()).hasSize(2);
    assertThat(data.dependencySources().get(0).gav()).isEqualTo("com.example:a:1");
    assertThat(data.dependencySources().get(1).gav()).isEqualTo("com.example:b:2");
    assertThat(data.dependencySources().get(1).jar()).isEqualTo("/repo/b.jar");
  }
}
