package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.launch.ReactorRewrite;
import io.github.aglibs.lathe.core.schema.OutputData;
import io.github.aglibs.lathe.server.TestCompiler;
import java.nio.file.Path;
import java.util.List;
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

  @Test
  void testScope_usesSeparateGeneratedMirror() {
    final Path testSrc = WORKSPACE.resolve("module/src/test/java");
    final Path gen = WORKSPACE.resolve("module/target/generated-test-sources/test-annotations");
    final Path moduleDir = LATHE_DIR.resolve("module");
    final var config = testConfig(gen, testSrc);

    // Each scope owns exactly one generated mirror; the main mirror belongs to the main config, so
    // it must NOT appear here (double-ownership breaks candidate->source-root routing in analysis).
    assertThat(config.generatedSourcesDir())
        .isEqualTo(moduleDir.resolve(LatheLayout.GENERATED_TEST_SOURCES));
    assertThat(config.searchRoots())
        .containsExactly(testSrc, moduleDir.resolve(LatheLayout.GENERATED_TEST_SOURCES));
  }

  @Test
  void isGenerated_buildOutputOrLatheMirror_true_handWrittenSource_false() {
    final Path src = WORKSPACE.resolve("module/src/main/java");
    final Path mavenTarget = WORKSPACE.resolve("module/target/generated-sources/annotations");
    final var config = config(mavenTarget, src);

    assertThat(config.isGenerated(mavenTarget.resolve("com/example/UserBuilder.java"))).isTrue();
    assertThat(
            config.isGenerated(
                config.generatedSourcesDir().resolve("com/example/UserBuilder.java")))
        .isTrue();
    assertThat(config.isGenerated(src.resolve("com/example/User.java"))).isFalse();
  }

  private static ModuleSourceConfig config(
      final Path originalGenSourcesDir, final Path sourceRoot) {
    final Path moduleDir = LATHE_DIR.resolve("module");
    return TestCompiler.moduleConfig(
        moduleDir, moduleDir.resolve("classes"), sourceRoot, originalGenSourcesDir);
  }

  private static ModuleSourceConfig testConfig(
      final Path originalGenSourcesDir, final Path sourceRoot) {
    final Path moduleDir = LATHE_DIR.resolve("module");
    return TestCompiler.moduleConfig(
        moduleDir,
        moduleDir.resolve("test-classes"),
        sourceRoot,
        originalGenSourcesDir,
        LatheLayout.TEST_CLASSES_DIR);
  }

  @Test
  void remappedPaths_listedOutputsMapToMirrorOthersPassThrough() {
    final Path sibling = WORKSPACE.resolve("platform/core/target/classes");
    final Path siblingTestJar = WORKSPACE.resolve("platform/core/target/core-2.0-tests.jar");
    final Path external = Path.of("/home/user/.m2/repository/com/google/guava/guava.jar");
    final Path inRepoJar = WORKSPACE.resolve("libs/vendor.jar");
    final var rewrite =
        ReactorRewrite.of(
            WORKSPACE,
            List.of(
                new OutputData("platform/core/target/classes", "platform/core", "classes"),
                new OutputData(
                    "platform/core/target/core-2.0-tests.jar", "platform/core", "test-classes")));
    final var config =
        new ModuleSourceConfig(
            LATHE_DIR.resolve("app"),
            LatheLayout.CLASSES_DIR,
            WORKSPACE.resolve("app/target/classes"),
            null,
            List.of(),
            List.of(sibling, external, inRepoJar),
            List.of(siblingTestJar),
            List.of(sibling),
            "21",
            "UTF-8",
            false,
            false,
            null,
            List.of(),
            null,
            false,
            rewrite);

    assertThat(config.remappedClasspath())
        .containsExactly(LATHE_DIR.resolve("platform/core/classes"), external, inRepoJar);
    assertThat(config.remappedModulepath())
        .containsExactly(LATHE_DIR.resolve("platform/core/test-classes"));
    assertThat(config.remappedProcessorPath())
        .containsExactly(LATHE_DIR.resolve("platform/core/classes"));
  }
}
