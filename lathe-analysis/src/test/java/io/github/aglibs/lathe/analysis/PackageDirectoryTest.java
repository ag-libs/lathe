package io.github.aglibs.lathe.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.analysis.testing.TempSourceCompiler;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// MC-90: a file whose package is not its directory under its source root gets an error naming
// both, on its package line, as Lathe finds a type's file from its package.
class PackageDirectoryTest {

  private static final Path ROOT = Path.of("module", "src", "main", "java");
  private static final Path CORE_FILE = ROOT.resolve(Path.of("com", "example", "core", "A.java"));

  private SourceAnalysisSession session;

  @BeforeEach
  void setUp() {
    session = new SourceAnalysisSession(new TempSourceCompiler());
  }

  @AfterEach
  void tearDown() {
    session.close();
  }

  @Test
  void mismatch_packageNamesTheDirectory_none() {
    assertThat(mismatch("package com.example.core;\nclass A {}\n", CORE_FILE, List.of(ROOT)))
        .isEmpty();
  }

  @Test
  void mismatch_packageNamesAnotherDirectory_errorOnThePackageLine() {
    final Optional<Diagnostic> mismatch =
        mismatch("package com.example.other;\nclass A {}\n", CORE_FILE, List.of(ROOT));

    assertThat(mismatch).isPresent();
    assertThat(mismatch.get().getSeverity()).isEqualTo(DiagnosticSeverity.Error);
    assertThat(mismatch.get().getCode().getLeft()).isEqualTo(PackageDirectory.CODE);
    assertThat(mismatch.get().getRange().getStart().getLine()).isZero();
    assertThat(mismatch.get().getMessage().getLeft())
        .contains("com.example.other")
        .contains("package com.example.core");
  }

  @Test
  void mismatch_noPackageInASubdirectory_errorNamingTheDirectorysPackage() {
    final Optional<Diagnostic> mismatch = mismatch("class A {}\n", CORE_FILE, List.of(ROOT));

    assertThat(mismatch).isPresent();
    assertThat(mismatch.get().getMessage().getLeft())
        .startsWith("The file declares no package")
        .contains("com.example.core");
  }

  @Test
  void mismatch_defaultPackageAtTheSourceRoot_none() {
    assertThat(mismatch("class A {}\n", ROOT.resolve("A.java"), List.of(ROOT))).isEmpty();
  }

  @Test
  void mismatch_innermostRootHoldingTheFile_isTheOneChecked() {
    final Path generated = ROOT.resolve("com");
    assertThat(mismatch("package example.core;\nclass A {}\n", CORE_FILE, List.of(ROOT, generated)))
        .isEmpty();
  }

  @Test
  void mismatch_fileInNoSourceRoot_none() {
    assertThat(
            mismatch(
                "package com.example.other;\nclass A {}\n",
                Path.of("elsewhere", "A.java"),
                List.of(ROOT)))
        .isEmpty();
  }

  private Optional<Diagnostic> mismatch(
      final String source, final Path file, final List<Path> sourceRoots) {
    session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
    final AttributedFileAnalysis analysis =
        session.cachedAnalysis(TempSourceCompiler.TEST_URI).orElseThrow();
    return PackageDirectory.mismatch(analysis, file, sourceRoots);
  }
}
