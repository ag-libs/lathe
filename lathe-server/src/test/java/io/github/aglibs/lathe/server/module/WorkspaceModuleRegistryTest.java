package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.ModuleConfigData;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceModuleRegistryTest {

  @TempDir Path tmp;

  @Test
  void allSourceRoots_includesHandWrittenSourceRoots() throws IOException {
    final var src = tmp.resolve("module-a/src/main/java");
    writeParams("module-a", src, null);

    final var registry = WorkspaceModuleRegistry.scan(tmp, WorkspaceManifest.empty());

    assertThat(registry.allSourceRoots()).containsExactly(src);
  }

  @Test
  void allSourceRoots_includesOriginalGenSourcesDir_whenPresent() throws IOException {
    final var src = tmp.resolve("module-a/src/main/java");
    final var generatedSrc = tmp.resolve("module-a/target/generated-sources/annotations");
    writeParams("module-a", src, generatedSrc);

    final var registry = WorkspaceModuleRegistry.scan(tmp, WorkspaceManifest.empty());

    assertThat(registry.allSourceRoots()).contains(src, generatedSrc);
  }

  @Test
  void moduleSourceFor_fileInSourceRoot_returnsModule() throws IOException {
    final var src = tmp.resolve("module-a/src/main/java");
    writeParams("module-a", src, null);

    final var registry = WorkspaceModuleRegistry.scan(tmp, WorkspaceManifest.empty());

    assertThat(registry.moduleSourceFor(src.resolve("com/example/Foo.java"))).isPresent();
  }

  @Test
  void moduleSourceFor_fileInGeneratedSourcesDir_returnsModule() throws IOException {
    final var src = tmp.resolve("module-a/src/main/java");
    final var generatedSrc = tmp.resolve("module-a/target/generated-sources/annotations");
    writeParams("module-a", src, generatedSrc);

    final var registry = WorkspaceModuleRegistry.scan(tmp, WorkspaceManifest.empty());

    assertThat(registry.moduleSourceFor(generatedSrc.resolve("com/example/FooBuilder.java")))
        .isPresent();
  }

  @Test
  void allSourceRoots_noGenSourcesDir_doesNotAddNull() throws IOException {
    final var src = tmp.resolve("module-a/src/main/java");
    writeParams("module-a", src, null);

    final var registry = WorkspaceModuleRegistry.scan(tmp, WorkspaceManifest.empty());

    assertThat(registry.allSourceRoots()).doesNotContainNull();
    assertThat(registry.allSourceRoots()).hasSize(1);
  }

  private void writeParams(
      final String moduleName, final Path sourceRoot, final Path generatedSourcesDir)
      throws IOException {
    final var latheModuleDir = tmp.resolve(LatheLayout.LATHE_DIR).resolve(moduleName);
    Files.createDirectories(latheModuleDir);
    final var config =
        new ModuleConfigData(
            "classes",
            latheModuleDir.resolve("classes").toString(),
            generatedSourcesDir != null ? generatedSourcesDir.toString() : null,
            List.of(sourceRoot.toString()),
            List.of(),
            List.of(),
            List.of(),
            "21",
            "UTF-8",
            false,
            false,
            null,
            List.of());
    Json.write(config, latheModuleDir.resolve(LatheLayout.paramsFileName("classes")));
  }
}
