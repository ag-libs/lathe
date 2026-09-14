package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.TestCompiler;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ModuleSourceConfigTest {

  private static final Path WORKSPACE = Path.of("/workspace");
  private static final Path LATHE_DIR = WORKSPACE.resolve(".lathe");

  @Test
  void searchRoots_withGeneratedSources_usesLatheMirrorNotMavenTarget() {
    // EG-052: definition/reference resolution must search the fresh .lathe generated-sources mirror
    // Lathe keeps in sync, not the stale Maven target/generated-sources copy.
    final Path src = WORKSPACE.resolve("module/src/main/java");
    final Path mavenTarget = WORKSPACE.resolve("module/target/generated-sources/annotations");
    final var config = config(mavenTarget, src);

    assertThat(config.searchRoots())
        .containsExactly(src, config.generatedSourcesDir())
        .doesNotContain(mavenTarget);
  }

  @Test
  void searchRoots_noGeneratedSources_returnsSourceRootsOnly() {
    final Path src = WORKSPACE.resolve("module/src/main/java");
    final var config = config(null, src);

    assertThat(config.searchRoots()).containsExactly(src);
  }

  private static ModuleSourceConfig config(
      final Path originalGenSourcesDir, final Path sourceRoot) {
    final Path moduleDir = LATHE_DIR.resolve("module");
    return TestCompiler.moduleConfig(
        moduleDir, moduleDir.resolve("classes"), sourceRoot, originalGenSourcesDir);
  }

  @Test
  void remapPath_externalJar_passesThrough() {
    final var jar = Path.of("/home/user/.m2/repository/com/google/guava/guava.jar");
    assertThat(ModuleSourceConfig.remapPath(jar, WORKSPACE, LATHE_DIR)).isEqualTo(jar);
  }

  @Test
  void remapPath_reactorJar_remappedToLatheClasses() {
    final Path jar = WORKSPACE.resolve("service/registry/target/helidon-service-registry.jar");
    assertThat(ModuleSourceConfig.remapPath(jar, WORKSPACE, LATHE_DIR))
        .isEqualTo(LATHE_DIR.resolve("service/registry/classes"));
  }

  @Test
  void remapPath_simpleModule_remappedToLathe() {
    final Path outputDir = WORKSPACE.resolve("module-a/target/classes");
    assertThat(ModuleSourceConfig.remapPath(outputDir, WORKSPACE, LATHE_DIR))
        .isEqualTo(LATHE_DIR.resolve("module-a/classes"));
  }

  @Test
  void remapPath_nestedModule_remappedToLathe() {
    final Path outputDir = WORKSPACE.resolve("platform/core/target/classes");
    assertThat(ModuleSourceConfig.remapPath(outputDir, WORKSPACE, LATHE_DIR))
        .isEqualTo(LATHE_DIR.resolve("platform/core/classes"));
  }

  @Test
  void remapPath_moduleUnderExtraDirectory_remappedToLathe() {
    final Path outputDir = WORKSPACE.resolve("services/user/api/target/classes");
    assertThat(ModuleSourceConfig.remapPath(outputDir, WORKSPACE, LATHE_DIR))
        .isEqualTo(LATHE_DIR.resolve("services/user/api/classes"));
  }

  @Test
  void remapPath_testClasses_remappedToLathe() {
    final Path outputDir = WORKSPACE.resolve("module-a/target/test-classes");
    assertThat(ModuleSourceConfig.remapPath(outputDir, WORKSPACE, LATHE_DIR))
        .isEqualTo(LATHE_DIR.resolve("module-a/test-classes"));
  }

  @Test
  void remapPath_rootModule_remappedToLathe() {
    final Path outputDir = WORKSPACE.resolve("target/classes");
    assertThat(ModuleSourceConfig.remapPath(outputDir, WORKSPACE, LATHE_DIR))
        .isEqualTo(LATHE_DIR.resolve("classes"));
  }

  @Test
  void remapPath_reactorTestJar_remappedToLatheTestClasses() {
    final Path jar = WORKSPACE.resolve("module-a/target/module-a-1.0-tests.jar");
    assertThat(ModuleSourceConfig.remapPath(jar, WORKSPACE, LATHE_DIR))
        .isEqualTo(LATHE_DIR.resolve("module-a/test-classes"));
  }

  @Test
  void remapPath_reactorTestJar_nestedModule_remappedToLatheTestClasses() {
    final Path jar = WORKSPACE.resolve("platform/core/target/core-2.0-tests.jar");
    assertThat(ModuleSourceConfig.remapPath(jar, WORKSPACE, LATHE_DIR))
        .isEqualTo(LATHE_DIR.resolve("platform/core/test-classes"));
  }

  @Test
  void remapPath_tooShortRelativePath_passesThrough() {
    final Path shallow = WORKSPACE.resolve("classes");
    assertThat(ModuleSourceConfig.remapPath(shallow, WORKSPACE, LATHE_DIR)).isEqualTo(shallow);
  }
}
