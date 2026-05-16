package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ModuleConfigTest {

  private static final Path WORKSPACE = Path.of("/workspace");
  private static final Path LATHE_DIR = WORKSPACE.resolve(".lathe");

  @Test
  void remapPath_externalJar_passesThrough() {
    final var jar = Path.of("/home/user/.m2/repository/com/google/guava/guava.jar");
    assertThat(ModuleConfig.remapPath(jar, WORKSPACE, LATHE_DIR)).isEqualTo(jar);
  }

  @Test
  void remapPath_reactorJar_remappedToLatheClasses() {
    final var jar = WORKSPACE.resolve("service/registry/target/helidon-service-registry.jar");
    assertThat(ModuleConfig.remapPath(jar, WORKSPACE, LATHE_DIR))
        .isEqualTo(LATHE_DIR.resolve("service/registry/classes"));
  }

  @Test
  void remapPath_simpleModule_remappedToLathe() {
    final var outputDir = WORKSPACE.resolve("module-a/target/classes");
    assertThat(ModuleConfig.remapPath(outputDir, WORKSPACE, LATHE_DIR))
        .isEqualTo(LATHE_DIR.resolve("module-a/classes"));
  }

  @Test
  void remapPath_nestedModule_remappedToLathe() {
    final var outputDir = WORKSPACE.resolve("platform/core/target/classes");
    assertThat(ModuleConfig.remapPath(outputDir, WORKSPACE, LATHE_DIR))
        .isEqualTo(LATHE_DIR.resolve("platform/core/classes"));
  }

  @Test
  void remapPath_moduleUnderExtraDirectory_remappedToLathe() {
    final var outputDir = WORKSPACE.resolve("services/user/api/target/classes");
    assertThat(ModuleConfig.remapPath(outputDir, WORKSPACE, LATHE_DIR))
        .isEqualTo(LATHE_DIR.resolve("services/user/api/classes"));
  }

  @Test
  void remapPath_testClasses_remappedToLathe() {
    final var outputDir = WORKSPACE.resolve("module-a/target/test-classes");
    assertThat(ModuleConfig.remapPath(outputDir, WORKSPACE, LATHE_DIR))
        .isEqualTo(LATHE_DIR.resolve("module-a/test-classes"));
  }

  @Test
  void remapPath_rootModule_remappedToLathe() {
    final var outputDir = WORKSPACE.resolve("target/classes");
    assertThat(ModuleConfig.remapPath(outputDir, WORKSPACE, LATHE_DIR))
        .isEqualTo(LATHE_DIR.resolve("classes"));
  }

  @Test
  void remapPath_tooShortRelativePath_passesThrough() {
    final var shallow = WORKSPACE.resolve("classes");
    assertThat(ModuleConfig.remapPath(shallow, WORKSPACE, LATHE_DIR)).isEqualTo(shallow);
  }
}
