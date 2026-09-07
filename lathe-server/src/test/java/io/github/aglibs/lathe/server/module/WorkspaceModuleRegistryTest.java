package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.TestCompiler;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.nio.file.Files;
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
  void scan_adoptsOnlyModulesOfThisWorkspace() throws Exception {
    // A real module of this workspace.
    final var mainSrc = tmp.resolve("module-a/src/main/java");
    TestCompiler.writeModuleParams(tmp, "module-a", mainSrc, null);

    // A nested checkout/worktree under the repo has its own .lathe/; a build inside it leaked its
    // params into this .lathe/ — that module belongs to the nested workspace and must be dropped.
    final var nestedSrc = tmp.resolve(".claude/worktrees/wt/module-b/src/main/java");
    TestCompiler.writeModuleParams(tmp, ".claude/worktrees/wt/module-b", nestedSrc, null);
    Files.createDirectories(tmp.resolve(".claude/worktrees/wt").resolve(LatheLayout.LATHE_DIR));

    // A .lathe/ at a module's OWN dir is a stray, not a nested workspace — only an ancestor .lathe/
    // disqualifies a module, so this real module is still adopted.
    final var straySrc = tmp.resolve("module-c/src/main/java");
    TestCompiler.writeModuleParams(tmp, "module-c", straySrc, null);
    Files.createDirectories(tmp.resolve("module-c").resolve(LatheLayout.LATHE_DIR));

    final var registry = WorkspaceModuleRegistry.scan(tmp, WorkspaceManifest.empty());

    assertThat(registry.allSourceRoots()).containsExactlyInAnyOrder(mainSrc, straySrc);
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
