package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.server.TestCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtractionSupportTest {

  @TempDir Path tmp;

  // Declaring a non-canonical constructor forces javac to synthesize a separate canonical
  // constructor; its parameter type references carry no source position (NOPOS). Scanning the whole
  // record for occurrences of a type therefore reaches positionless nodes that cannot become a
  // TextEdit — the crash before occurrences() learned to drop them.
  private static final String RECORD_WITH_NON_CANONICAL_CTOR =
      """
      package com.example;
      import java.util.List;

      record Bundle(String bundleId, String name, String description, List<String> products, String status) {
        Bundle(String id, String name, String description, List<String> products, String status, String ignored) {
          this(id, name, description, products, status);
        }
      }
      """;

  @Test
  void occurrences_synthesizedCanonicalConstructor_excludesPositionlessNodes() throws IOException {
    final var file = tmp.resolve("Bundle.java");
    Files.writeString(file, RECORD_WITH_NON_CANONICAL_CTOR);

    try (var parsed = TestCompiler.parse(file)) {
      final var trees = parsed.trees();
      final CompilationUnitTree cu = parsed.cu();
      final var scope = TreePath.getPath(cu, cu.getTypeDecls().getFirst());
      final String content = SampleFixture.sourceContent(cu);
      final TreePath selected = SourceLocator.pathAt(trees, cu, content.indexOf("String"));

      final List<TreePath> occurrences = ExtractionSupport.occurrences(scope, selected, trees);

      // Real source references survive; no positionless synthetic leaks in — a leaked NOPOS node is
      // what made offsetToPosition throw and kill the whole code-action request.
      final var positions = trees.getSourcePositions();
      assertThat(occurrences)
          .isNotEmpty()
          .allSatisfy(
              o -> {
                assertThat(positions.getStartPosition(cu, o.getLeaf())).isNotNegative();
                assertThat(positions.getEndPosition(cu, o.getLeaf())).isNotNegative();
              });
    }
  }
}
