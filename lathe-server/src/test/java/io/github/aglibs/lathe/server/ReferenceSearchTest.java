package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.analysis.LibrarySources;
import io.github.aglibs.lathe.analysis.ReferenceMatch;
import io.github.aglibs.lathe.analysis.ReferenceTarget;
import io.github.aglibs.lathe.analysis.SearchHits;
import io.github.aglibs.lathe.analysis.SourceAnalysisSession;
import io.github.aglibs.lathe.analysis.SourceFeatureRequest;
import io.github.aglibs.lathe.analysis.SourceLocator;
import io.github.aglibs.lathe.analysis.TransientSource;
import io.github.aglibs.lathe.analysis.testing.TempSourceCompiler;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceSearchTest {

  @TempDir private Path tmp;

  @Test
  void isInPackageScope_generatedSourcesCandidate_reactorScope_inScope() {
    // A reactor-scoped search uses a null packageRel; the generated builder
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

  // Each closed file's hits are reported as it is searched, and the answer merges them all.
  @Test
  void searchBatch_twoClosedFiles_reportsEachFilesHitsAndMergesThem() {
    final String a = "class A { String a() { return String.valueOf(1); } }";
    final String b = "class B { void b() { String.valueOf(2); String.valueOf(3); } }";
    final List<TransientSource> sources =
        List.of(new TransientSource("file:///A.java", a), new TransientSource("file:///B.java", b));
    final var hitsPerFile = new ArrayList<Integer>();

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      final ReferenceTarget target =
          session
              .references()
              .resolveTarget(
                  new SourceFeatureRequest(
                      "file:///A.java",
                      a,
                      1,
                      SourceLocator.offsetToPosition(a, a.indexOf("valueOf")),
                      List.of(),
                      LibrarySources.none()));
      final SearchHits<ReferenceMatch> found =
          ReferenceSearch.searchBatch(
              session, sources, target, false, Set.of(), hitsPerFile::add, () -> {});

      assertThat(hitsPerFile).containsExactly(1, 2);
      assertThat(found.hits())
          .extracting(ReferenceMatch::uri)
          .containsExactly("file:///A.java", "file:///B.java", "file:///B.java");
    }
  }

  private ModuleSourceConfig configWithGen(final Path genRoot) {
    return TestCompiler.moduleConfig(
        tmp.resolve(".lathe/module"),
        tmp.resolve("module/target/classes"),
        tmp.resolve("module/src/main/java"),
        genRoot);
  }
}
