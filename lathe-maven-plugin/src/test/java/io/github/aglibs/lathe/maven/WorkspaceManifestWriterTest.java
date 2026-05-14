package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.ParamStore;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceManifestWriterTest {

  @TempDir Path workspaceRoot;

  @Test
  void write_writesWorkspacePropertiesAtomically() throws Exception {
    final DependencySource dependencySource =
        DependencySource.present(
            "com.example:dep:1",
            Path.of("/repo/dep.jar"),
            Path.of("/cache/dep"),
            null,
            List.of(Path.of("/repo/transitive.jar")));
    final JdkSource jdkSource =
        JdkSource.present(
            "Example Vendor",
            "21.0.1",
            Path.of("/jdk"),
            Path.of("/jdk/lib/src.zip"),
            Path.of("/cache/jdks/Example-Vendor/21.0.1"));

    WorkspaceManifestWriter.write(
        workspaceRoot, List.of(dependencySource), jdkSource, new SystemStreamLog());

    final Path manifest =
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_PROPERTIES);
    final ParamStore props = ParamStore.load(manifest);
    assertThat(props.get("schemaVersion")).isEqualTo(LatheLayout.SCHEMA_VERSION);
    assertThat(props.get("workspaceRoot")).isEqualTo(workspaceRoot.toString());
    assertThat(props.get("jdk.home")).isEqualTo("/jdk");
    assertThat(props.get("jdk.vendor")).isEqualTo("Example Vendor");
    assertThat(props.get("jdk.version")).isEqualTo("21.0.1");
    assertThat(props.get("jdk.sourceStatus")).isEqualTo("present");
    assertThat(props.get("jdk.sourceDir")).isEqualTo("/cache/jdks/Example-Vendor/21.0.1");
    assertThat(props.get("dependencySource.0.gav")).isEqualTo("com.example:dep:1");
    assertThat(props.get("dependencySource.0.jar")).isEqualTo("/repo/dep.jar");
    assertThat(props.get("dependencySource.0.status")).isEqualTo("present");
    assertThat(props.get("dependencySource.0.dir")).isEqualTo("/cache/dep");
    assertThat(props.get("dependencySource.0.classpath.0")).isEqualTo("/repo/transitive.jar");
  }

  @Test
  void write_missingJdkSource_omitsSourceDir() throws Exception {
    final JdkSource jdkSource = JdkSource.missing("Example Vendor", "21.0.1", Path.of("/jdk"));

    WorkspaceManifestWriter.write(workspaceRoot, List.of(), jdkSource, new SystemStreamLog());

    final Path manifest =
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_PROPERTIES);
    final ParamStore props = ParamStore.load(manifest);
    assertThat(props.get("jdk.version")).isEqualTo("21.0.1");
    assertThat(props.get("jdk.sourceStatus")).isEqualTo("missing");
    assertThat(props.get("jdk.sourceDir")).isNull();
  }

  @Test
  void write_multipleDependencies_writesAllEntries() throws Exception {
    final List<DependencySource> deps =
        List.of(
            DependencySource.present(
                "com.example:a:1", Path.of("/repo/a.jar"), Path.of("/cache/a"), null, List.of()),
            DependencySource.present(
                "com.example:b:2", Path.of("/repo/b.jar"), Path.of("/cache/b"), null, List.of()));
    final JdkSource jdkSource =
        JdkSource.present(
            "Vendor", "21", Path.of("/jdk"), Path.of("/jdk/src.zip"), Path.of("/cache/jdk"));

    WorkspaceManifestWriter.write(workspaceRoot, deps, jdkSource, new SystemStreamLog());

    final Path manifest =
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_PROPERTIES);
    final ParamStore props = ParamStore.load(manifest);
    assertThat(props.get("dependencySource.0.gav")).isEqualTo("com.example:a:1");
    assertThat(props.get("dependencySource.1.gav")).isEqualTo("com.example:b:2");
    assertThat(props.get("dependencySource.1.jar")).isEqualTo("/repo/b.jar");
  }
}
