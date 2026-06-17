package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.SymbolKind;
import org.junit.jupiter.api.Test;

class DocumentSymbolScannerTest {

  @Test
  void scan_classesMethodsAndFields_returnsHierarchy() {
    final String source =
        """
        package demo;

        class Outer {
          private String field;

          Outer() {
          }

          void method(int param) {
            String local = "";
          }

          class Inner {
            int nested;
          }
        }
        """;

    final List<DocumentSymbol> symbols = scan(source);

    assertThat(symbols).extracting(DocumentSymbol::getName).containsExactly("Outer");
    final var outer = symbols.getFirst();
    assertThat(outer.getKind()).isEqualTo(SymbolKind.Class);
    assertThat(outer.getSelectionRange().getStart().getLine()).isEqualTo(2);
    assertThat(outer.getSelectionRange().getStart().getCharacter()).isEqualTo(6);
    assertThat(outer.getChildren())
        .extracting(DocumentSymbol::getName)
        .containsExactly("field", "Outer", "method", "Inner");
    assertThat(outer.getChildren())
        .extracting(DocumentSymbol::getKind)
        .containsExactly(
            SymbolKind.Field, SymbolKind.Constructor, SymbolKind.Method, SymbolKind.Class);
    assertThat(children(named(outer.getChildren(), "method"))).isEmpty();
    assertThat(named(outer.getChildren(), "Inner").getChildren())
        .extracting(DocumentSymbol::getName)
        .containsExactly("nested");
  }

  @Test
  void scan_typeKinds_mapsToSymbolKinds() {
    final String source =
        """
        interface Service {
        }

        @interface Marker {
        }

        enum State {
          ON
        }

        record User(String name) {
        }
        """;

    final List<DocumentSymbol> symbols = scan(source);

    assertThat(symbols)
        .extracting(DocumentSymbol::getName)
        .containsExactly("Service", "Marker", "State", "User");
    assertThat(symbols)
        .extracting(DocumentSymbol::getKind)
        .containsExactly(
            SymbolKind.Interface, SymbolKind.Interface, SymbolKind.Enum, SymbolKind.Struct);
  }

  @Test
  void scan_fieldWithSameTypeName_selectionRangeUsesFieldName() {
    final String source =
        """
        class Foo {
          Foo Foo;
        }
        """;

    final var fooClass = scan(source).getFirst();
    final var field = fooClass.getChildren().getFirst();

    assertThat(field.getName()).isEqualTo("Foo");
    assertThat(field.getSelectionRange().getStart().getLine()).isEqualTo(1);
    assertThat(field.getSelectionRange().getStart().getCharacter()).isEqualTo(6);
  }

  @Test
  void scan_localVariablesAndParameters_returnsNoSymbols() {
    final String source =
        """
        class Test {
          void method(String param) {
            String local = "";
            for (int i = 0; i < 1; i++) {
            }
          }
        }
        """;

    final var method = named(scan(source).getFirst().getChildren(), "method");

    assertThat(children(method)).isEmpty();
  }

  private static List<DocumentSymbol> scan(final String source) {
    try (var parser = new SourceParser()) {
      return parser
          .parseContent(
              TempSourceCompiler.TEST_URI,
              source,
              (trees, tree) -> DocumentSymbolScanner.scan(trees, tree, source))
          .orElseThrow();
    }
  }

  private static DocumentSymbol named(final List<DocumentSymbol> symbols, final String name) {
    return symbols.stream()
        .filter(symbol -> symbol.getName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static List<DocumentSymbol> children(final DocumentSymbol symbol) {
    return symbol.getChildren() != null ? symbol.getChildren() : List.of();
  }
}
