package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.TestCompiler;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceModuleRegistryTest {

  @TempDir Path tmp;

  @Test
  void allSourceRoots_includesHandWrittenSourceRoots() throws Exception {
    final var src = tmp.resolve("module-a/src/main/java");
    TestCompiler.writeModuleParams(tmp, "module-a", src, null);

    final var registry = WorkspaceModuleRegistry.scan(tmp, WorkspaceManifest.empty());

    assertThat(registry.allSourceRoots()).containsExactly(src);
  }

  @Test
  void allSourceRoots_includesOriginalGenSourcesDir_whenPresent() throws Exception {
    final var src = tmp.resolve("module-a/src/main/java");
    final var generatedSrc = tmp.resolve("module-a/target/generated-sources/annotations");
    TestCompiler.writeModuleParams(tmp, "module-a", src, generatedSrc);

    final var registry = WorkspaceModuleRegistry.scan(tmp, WorkspaceManifest.empty());

    assertThat(registry.allSourceRoots()).contains(src, generatedSrc);
  }

  @Test
  void moduleSourceFor_fileInSourceRoot_returnsModule() throws Exception {
    final var src = tmp.resolve("module-a/src/main/java");
    TestCompiler.writeModuleParams(tmp, "module-a", src, null);

    final var registry = WorkspaceModuleRegistry.scan(tmp, WorkspaceManifest.empty());

    assertThat(registry.moduleSourceFor(src.resolve("com/example/Foo.java"))).isPresent();
  }

  @Test
  void moduleSourceFor_fileInGeneratedSourcesDir_returnsModule() throws Exception {
    final var src = tmp.resolve("module-a/src/main/java");
    final var generatedSrc = tmp.resolve("module-a/target/generated-sources/annotations");
    TestCompiler.writeModuleParams(tmp, "module-a", src, generatedSrc);

    final var registry = WorkspaceModuleRegistry.scan(tmp, WorkspaceManifest.empty());

    assertThat(registry.moduleSourceFor(generatedSrc.resolve("com/example/FooBuilder.java")))
        .isPresent();
  }

  @Test
  void allSourceRoots_noGenSourcesDir_doesNotAddNull() throws Exception {
    final var src = tmp.resolve("module-a/src/main/java");
    TestCompiler.writeModuleParams(tmp, "module-a", src, null);

    final var registry = WorkspaceModuleRegistry.scan(tmp, WorkspaceManifest.empty());

    assertThat(registry.allSourceRoots()).doesNotContainNull();
    assertThat(registry.allSourceRoots()).hasSize(1);
  }
}
