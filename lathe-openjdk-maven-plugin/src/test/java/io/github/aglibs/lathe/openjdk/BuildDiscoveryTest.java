package io.github.aglibs.lathe.openjdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildDiscoveryTest {

  @TempDir private Path root;

  @Test
  void resolve_explicitBuildDir_usedVerbatimWithoutScanning() throws IOException {
    // No build/ exists, yet an explicit dir is returned as-is (never scanned).
    assertThat(BuildDiscovery.resolve("build/linux-x86_64-server-release", root))
        .isEqualTo(Path.of("build/linux-x86_64-server-release"));
  }

  @Test
  void resolve_singleConfiguredBuild_discoversIt() throws IOException {
    configuredBuild("linux-x86_64-server-release");
    // A build dir without spec.gmk is ignored, so it does not count as a second config.
    Files.createDirectories(root.resolve("build/not-configured"));

    assertThat(BuildDiscovery.resolve(null, root))
        .isEqualTo(root.resolve("build/linux-x86_64-server-release"));
  }

  @Test
  void resolve_multipleConfiguredBuilds_throwsListingNames() throws IOException {
    configuredBuild("linux-x86_64-server-release");
    configuredBuild("linux-aarch64-server-fastdebug");

    assertThatThrownBy(() -> BuildDiscovery.resolve("  ", root))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("multiple configured builds")
        .hasMessageContaining("linux-x86_64-server-release")
        .hasMessageContaining("linux-aarch64-server-fastdebug")
        .hasMessageContaining("-Dlathe.buildDir");
  }

  @Test
  void resolve_noConfiguredBuild_throwsWithHint() throws IOException {
    // build/ exists but nothing is configured (no spec.gmk under it).
    Files.createDirectories(root.resolve("build/stale"));

    assertThatThrownBy(() -> BuildDiscovery.resolve(null, root))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("no configured build")
        .hasMessageContaining("bash configure");
  }

  private void configuredBuild(final String conf) throws IOException {
    final Path spec = root.resolve("build").resolve(conf).resolve("spec.gmk");
    Files.createDirectories(spec.getParent());
    Files.writeString(spec, "OUTPUTDIR := %s".formatted(conf));
  }
}
