package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.TestCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportAnalyzerTest {

  @TempDir private Path tmp;

  @Test
  void insertionRange_packageAndImports_returnsAfterLastImport() throws IOException {
    final var source =
        """
        package com.example;

        import java.util.List;
        import java.util.Set;

        class Foo {}
        """;
    final var analysis = analyze(source);
    final var range = new ImportAnalyzer(analysis).insertionRange();

    assertThat(range).isNotNull();
    assertThat(range.getStart()).isEqualTo(new Position(4, 0));
    assertThat(range.getEnd()).isEqualTo(new Position(4, 0));
  }

  @Test
  void insertionRange_packageAndNoImports_returnsAfterPackage() throws IOException {
    final var source =
        """
        package com.example;

        class Foo {}
        """;
    final var analysis = analyze(source);
    final var range = new ImportAnalyzer(analysis).insertionRange();

    assertThat(range).isNotNull();
    assertThat(range.getStart()).isEqualTo(new Position(1, 0));
    assertThat(range.getEnd()).isEqualTo(new Position(1, 0));
  }

  @Test
  void insertionRange_noPackageAndNoImports_returnsStartOfFile() throws IOException {
    final var source =
        """
        class Foo {}
        """;
    final var analysis = analyze(source);
    final var range = new ImportAnalyzer(analysis).insertionRange();

    assertThat(range).isNotNull();
    assertThat(range.getStart()).isEqualTo(new Position(0, 0));
    assertThat(range.getEnd()).isEqualTo(new Position(0, 0));
  }

  @Test
  void importedQualifiedNames_mixedImports_extractsCorrectly() throws IOException {
    final var source =
        """
        package com.example;

        import java.util.List;
        import static java.util.Collections.emptyList;
        import java.util.Map;

        class Foo {}
        """;
    final var analysis = analyze(source);
    final var imports = new ImportAnalyzer(analysis).importedQualifiedNames();

    assertThat(imports).containsExactlyInAnyOrder("java.util.List", "java.util.Map");
  }

  @Test
  void importedStaticNames_mixedImports_extractsCorrectly() throws IOException {
    final var source =
        """
        package com.example;

        import java.util.List;
        import static java.util.Collections.emptyList;
        import static java.util.Collections.emptyMap;

        class Foo {}
        """;
    final var analysis = analyze(source);
    final var staticImports = new ImportAnalyzer(analysis).importedStaticNames();

    assertThat(staticImports)
        .containsExactlyInAnyOrder(
            "java.util.Collections.emptyList", "java.util.Collections.emptyMap");
  }

  private AttributedFileAnalysis analyze(final String source) throws IOException {
    final var file = writeSource(source);
    final var parsed = TestCompiler.parse(file);
    return new AttributedFileAnalysis(
        parsed.trees(),
        parsed.task().getElements(),
        parsed.task().getTypes(),
        parsed.cu(),
        List.of());
  }

  private Path writeSource(final String content) throws IOException {
    final var path = tmp.resolve("Test.java");
    Files.writeString(path, content);
    return path;
  }
}
