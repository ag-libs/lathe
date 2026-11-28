package io.github.aglibs.lathe.server.debug;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.java.debug.core.JavaBreakpointLocation;
import com.microsoft.java.debug.core.protocol.Types;
import io.github.aglibs.lathe.server.TestCompiler;
import io.github.aglibs.lathe.server.module.WorkspaceModuleRegistry;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LatheSourceLookUpProviderTest {

  @TempDir private Path tmp;

  @Test
  void getSourceFileURI_classInAnotherModule_resolvesWhenRootsSpanTheReactor() throws IOException {
    final Path appRoot = Files.createDirectories(tmp.resolve("app/src/main/java"));
    final Path coreRoot = Files.createDirectories(tmp.resolve("core/src/main/java"));
    final Path coreFile = coreRoot.resolve("com/example/core/StringUtils.java");
    Files.createDirectories(coreFile.getParent());
    Files.writeString(coreFile, "package com.example.core;\npublic final class StringUtils {}\n");

    final var registry =
        WorkspaceModuleRegistry.scan(tmp, WorkspaceManifest.empty(), Runnable::run);

    final var wholeReactor =
        new LatheSourceLookUpProvider(
            new FrameSources(
                CompletableFuture::runAsync,
                registry::moduleWorkerFor,
                List.of(appRoot, coreRoot)));
    assertThat(wholeReactor.getSourceFileURI("com.example.core.StringUtils", null))
        .isEqualTo(coreFile.toUri().toString());

    // Launched-module-only roots (the bug): the cross-module class has no source.
    final var launchedModuleOnly =
        new LatheSourceLookUpProvider(
            new FrameSources(
                CompletableFuture::runAsync, registry::moduleWorkerFor, List.of(appRoot)));
    assertThat(launchedModuleOnly.getSourceFileURI("com.example.core.StringUtils", null)).isNull();
  }

  // DB-7: a breakpoint in a file the editor never opened arms on the file's top-level type (whose
  // nested classes java-debug arms too); one on a line outside every type stays unarmed.
  @Test
  void getBreakpointLocations_fileNeverOpened_armsOnItsTopLevelType() throws Exception {
    final Path root = Files.createDirectories(tmp.resolve("module/src/main/java"));
    final Path file = root.resolve("com/example/Outer.java");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        """
        package com.example;

        class Outer {
          static class Inner {
            void run() {}
          }
        }
        """);
    TestCompiler.writeModuleParams(tmp, "module", root, null);
    try (var registry =
        WorkspaceModuleRegistry.scan(tmp, WorkspaceManifest.empty(), Runnable::run)) {
      final var provider =
          new LatheSourceLookUpProvider(
              new FrameSources(
                  CompletableFuture::runAsync, registry::moduleWorkerFor, List.of(root)));

      final JavaBreakpointLocation[] locations =
          provider.getBreakpointLocations(
              file.toUri().toString(),
              new Types.SourceBreakpoint[] {
                new Types.SourceBreakpoint(5, 0), new Types.SourceBreakpoint(2, 0)
              });

      assertThat(locations)
          .extracting(JavaBreakpointLocation::className)
          .containsExactly("com.example.Outer", null);
    }
  }
}
