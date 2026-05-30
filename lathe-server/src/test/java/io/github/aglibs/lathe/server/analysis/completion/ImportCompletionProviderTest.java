package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.TempSourceCompiler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ImportCompletionProviderTest {

  private static TempSourceCompiler compiler;
  private static ImportCompletionProvider provider;

  @BeforeAll
  static void setup() {
    compiler = new TempSourceCompiler();
    final var analysis =
        compiler.compile("file:///Dummy.java", "class Dummy {}", CompileMode.FULL).fileAnalysis();
    provider = new ImportCompletionProvider(analysis);
  }

  @AfterAll
  static void teardown() {
    compiler.close();
  }

  // ── type listing ──────────────────────────────────────────────────────────

  @Test
  void types_matchingPrefix_returned() {
    final var items = provider.proposeCandidates("java.util", "Col");
    assertThat(items).extracting(CompletionCandidate::label).contains("Collections");
  }

  @Test
  void types_nonMatchingPrefix_excluded() {
    final var items = provider.proposeCandidates("java.util", "Col");
    assertThat(items).noneMatch(i -> i.label().equals("ArrayList"));
  }

  @Test
  void types_emptyPrefix_allTypesReturned() {
    final var items = provider.proposeCandidates("java.util", "");
    assertThat(items)
        .extracting(CompletionCandidate::label)
        .contains("ArrayList", "Collections", "HashMap");
  }

  // ── sub-package listing ───────────────────────────────────────────────────

  @Test
  void subPackages_immediateChild_returned() {
    final var items = provider.proposeCandidates("java.util", "");
    assertThat(items).extracting(CompletionCandidate::label).contains("concurrent");
  }

  @Test
  void subPackages_deepNested_excluded() {
    // java.util.concurrent.atomic is two levels deep — only "concurrent" is immediate
    final var items = provider.proposeCandidates("java.util", "");
    assertThat(items).noneMatch(i -> i.label().equals("atomic"));
  }

  @Test
  void subPackages_matchingPrefix_filtered() {
    final var items = provider.proposeCandidates("java", "ut");
    assertThat(items).extracting(CompletionCandidate::label).contains("util");
  }

  @Test
  void subPackages_nonMatchingPrefix_excluded() {
    final var items = provider.proposeCandidates("java", "ut");
    assertThat(items).noneMatch(i -> i.label().equals("io"));
  }

  @Test
  void subPackages_topLevelPackage_immediateChildrenReturned() {
    final var items = provider.proposeCandidates("java", "");
    assertThat(items).extracting(CompletionCandidate::label).contains("util", "io", "lang");
  }
}
