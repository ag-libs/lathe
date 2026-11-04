package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceSearchTest {

  @TempDir private Path tmp;

  @Test
  void isInPackageScope_generatedSourcesCandidate_reactorScope_inScope() {
    // FR-012/FR-013 + EG-052: a reactor-scoped search uses a null packageRel; the generated builder
    // lives under the .lathe generated-sources mirror (the fresh copy Lathe keeps in sync), never
    // under a regular source root, yet must stay in scope. The stale Maven target/ copy is not.
    final var config = configWithGen(tmp.resolve("module/target/generated-sources/annotations"));
    final var sourceRoot = tmp.resolve("module/src/main/java");
    final var genCandidate = config.generatedSourcesDir().resolve("com/example/FooBuilder.java");
    final var staleTargetCandidate =
        config.originalGenSourcesDir().resolve("com/example/FooBuilder.java");
    final List<Path> roots = config.searchRoots();

    assertThat(ReferenceSearch.isInPackageScope(genCandidate, roots, null)).isTrue();
    assertThat(ReferenceSearch.isInPackageScope(staleTargetCandidate, roots, null)).isFalse();
    assertThat(
            ReferenceSearch.isInPackageScope(
                sourceRoot.resolve("com/example/Foo.java"), roots, null))
        .isTrue();
  }

  @Test
  void isInPackageScope_pathOutsideEverySearchRoot_notInScope() {
    final var config = configWithGen(tmp.resolve("module/target/generated-sources/annotations"));
    final var outside = tmp.resolve("other-module/target/classes/com/example/Bar.java");
    final List<Path> roots = config.searchRoots();

    assertThat(ReferenceSearch.isInPackageScope(outside, roots, null)).isFalse();
  }

  private ModuleSourceConfig configWithGen(final Path genRoot) {
    return TestCompiler.moduleConfig(
        tmp.resolve(".lathe/module"),
        tmp.resolve("module/target/classes"),
        tmp.resolve("module/src/main/java"),
        genRoot);
  }
}
