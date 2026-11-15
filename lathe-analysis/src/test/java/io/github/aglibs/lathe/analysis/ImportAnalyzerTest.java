package io.github.aglibs.lathe.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.aglibs.lathe.analysis.testing.TestJavac;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportAnalyzerTest {

  @TempDir private Path tmp;

  // A new import goes to its sorted place in its group, so an ordered block stays ordered.
  @Test
  void layout_sortedRegularImports_placesEachImportInOrder() throws IOException {
    final var layout =
        layoutOf(
            """
            package com.example;

            import java.util.List;
            import java.util.Set;

            class Foo {}
            """);

    assertThat(layout.edits(List.of("java.util.Map", "java.util.Objects")))
        .as("both before Set: one edit, in order")
        .singleElement()
        .satisfies(
            edit -> {
              assertThat(edit.getRange().getStart()).isEqualTo(new Position(3, 0));
              assertThat(edit.getNewText())
                  .isEqualTo("import java.util.Map;\nimport java.util.Objects;\n");
            });
    assertThat(layout.edits(List.of("java.util.Stack", "static java.util.Collections.max")))
        .extracting(edit -> edit.getRange().getStart(), TextEdit::getNewText)
        .containsExactly(
            tuple(new Position(2, 0), "import static java.util.Collections.max;\n"),
            tuple(new Position(3, 21), "\nimport java.util.Stack;"));
  }

  @Test
  void layout_staticGroupFirst_placesStaticAndRegularImportsInTheirGroups() throws IOException {
    final var layout =
        layoutOf(
            """
            package com.example;

            import static java.util.Collections.emptyList;
            import static java.util.Objects.requireNonNull;

            import java.util.List;

            class Foo {}
            """);

    assertThat(
            layout.edits(
                List.of(
                    "static java.util.Collections.nCopies",
                    "static java.util.Objects.toString",
                    "java.util.ArrayList",
                    "java.util.Set")))
        .extracting(edit -> edit.getRange().getStart(), TextEdit::getNewText)
        .containsExactly(
            tuple(new Position(3, 0), "import static java.util.Collections.nCopies;\n"),
            tuple(new Position(3, 47), "\nimport static java.util.Objects.toString;"),
            tuple(new Position(5, 0), "import java.util.ArrayList;\n"),
            tuple(new Position(5, 22), "\nimport java.util.Set;"));
  }

  // A file grouping its imports by package with blank lines, as Eclipse's import order does: a new
  // import joins its package's group, sorting after its last line too; one whose top-level package
  // has no group gets a group of its own, between the groups it sorts between.
  @Test
  void layout_groupsByPackage_placesEachImportInItsGroupOrANewOne() throws IOException {
    final var layout =
        layoutOf(
            """
            package com.example;

            import java.util.List;
            import java.util.concurrent.atomic.AtomicBoolean;

            import org.apiguardian.api.API;
            import org.jspecify.annotations.Nullable;

            class Foo {}
            """);

    assertThat(
            layout.edits(
                List.of(
                    "java.util.stream.Stream",
                    "javax.annotation.Nonnull",
                    "org.junit.Assert",
                    "zz.Last")))
        .extracting(edit -> edit.getRange().getStart(), TextEdit::getNewText)
        .containsExactly(
            tuple(new Position(3, 49), "\nimport java.util.stream.Stream;"),
            tuple(new Position(5, 0), "import javax.annotation.Nonnull;\n\n"),
            tuple(new Position(6, 41), "\nimport org.junit.Assert;\n\nimport zz.Last;"));
  }

  // IntelliJ's default layout, its "all other imports" holding one package: the JDK's block is not
  // a catch-all, so a new top-level package does not land in it.
  @Test
  void layout_intellijJdkBlock_keepsANewPackageOutOfIt() throws IOException {
    final var layout =
        layoutOf(
            """
            package com.example;

            import org.a.A;

            import javax.swing.JPanel;
            import java.io.File;

            class Foo {}
            """);

    assertThat(layout.edit("com.b.B"))
        .extracting(edit -> edit.getRange().getStart(), TextEdit::getNewText)
        .containsExactly(new Position(2, 0), "import com.b.B;\n\n");
  }

  // A single block may be a formatter's that never groups (google-java-format): a new top-level
  // package joins it, without a blank line, also when a comment line sits inside it.
  @Test
  void layout_singleBlock_sortsANewTopLevelPackageIntoIt() throws IOException {
    final var layout =
        layoutOf(
            """
            package com.example;

            import org.apiguardian.api.API;
            // a comment line, not a group break
            import org.jspecify.annotations.Nullable;

            class Foo {}
            """);

    assertThat(layout.edit("java.util.List"))
        .extracting(edit -> edit.getRange().getStart(), TextEdit::getNewText)
        .containsExactly(new Position(2, 0), "import java.util.List;\n");
  }

  // The first imports go a blank line below the package clause, as the build's formatter keeps
  // them; a file without one gets them at the very start.
  @Test
  void layout_noImports_placesBelowThePackageAfterABlankLineOrAtTheStart() throws IOException {
    final var afterPackage =
        layoutOf(
            """
            package com.example;

            class Foo {}
            """);
    final var atStart = layoutOf("class Foo {}\n");

    assertThat(afterPackage.edits(List.of("java.util.List", "static java.util.Objects.isNull")))
        .extracting(edit -> edit.getRange().getStart(), TextEdit::getNewText)
        .containsExactly(
            tuple(
                new Position(0, 20),
                "\n\nimport static java.util.Objects.isNull;\nimport java.util.List;"));
    assertThat(atStart.edit("java.util.List"))
        .extracting(edit -> edit.getRange().getStart(), TextEdit::getNewText)
        .containsExactly(new Position(0, 0), "import java.util.List;\n");
  }

  private ImportLayout layoutOf(final String source) throws IOException {
    return new ImportAnalyzer(analyze(source)).layout().orElseThrow();
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
    final var parsed = TestJavac.parse(file);
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
