package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeKind;
import org.junit.jupiter.api.Test;

class FoldingRangeScannerTest {

  @Test
  void scan_importsAndRegions_returnsFoldingRanges() {
    final String source =
        """
        package demo;

        import java.util.List;
        import java.util.Map;

        class Outer {
          void method() {
            Runnable r = new Runnable() {
              public void run() {
              }
            };
          }
        }
        """;

    final List<FoldingRange> ranges = scan(source);

    assertThat(ranges)
        .extracting(FoldingRange::getKind)
        .containsSequence(
            FoldingRangeKind.Imports, FoldingRangeKind.Region, FoldingRangeKind.Region);
    final var imports = ranges.getFirst();
    assertThat(imports.getKind()).isEqualTo(FoldingRangeKind.Imports);
    assertThat(imports.getStartLine()).isEqualTo(2);
    assertThat(imports.getEndLine()).isEqualTo(3);
    assertThat(ranges.stream().filter(range -> range.getKind().equals(FoldingRangeKind.Region)))
        .hasSizeGreaterThanOrEqualTo(3);
  }

  @Test
  void scan_singleImport_returnsNoImportFold() {
    final String source =
        """
        import java.util.List;

        class Test {
        }
        """;

    final List<FoldingRange> ranges = scan(source);

    assertThat(ranges).extracting(FoldingRange::getKind).doesNotContain(FoldingRangeKind.Imports);
  }

  @Test
  void scan_singleLineDeclarations_returnsNoRegionFold() {
    final String source = "class Test { void method() {} }";

    final List<FoldingRange> ranges = scan(source);

    assertThat(ranges).isEmpty();
  }

  @Test
  void scan_methodAndConstructor_returnsRegionFolds() {
    final String source =
        """
        class Test {
          Test() {
          }

          void method() {
          }
        }
        """;

    final List<FoldingRange> ranges = scan(source);

    assertThat(ranges)
        .filteredOn(range -> range.getKind().equals(FoldingRangeKind.Region))
        .extracting(FoldingRange::getStartLine)
        .contains(0, 1, 4);
  }

  private static List<FoldingRange> scan(final String source) {
    try (var parser = new SourceParser()) {
      return parser
          .parseContent(TempSourceCompiler.TEST_URI, source, FoldingRangeScanner::scan)
          .orElseThrow();
    }
  }
}
