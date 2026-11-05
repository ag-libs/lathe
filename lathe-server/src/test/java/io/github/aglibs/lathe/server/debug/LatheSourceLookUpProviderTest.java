package io.github.aglibs.lathe.server.debug;

import static org.assertj.core.api.Assertions.assertThat;

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
}
