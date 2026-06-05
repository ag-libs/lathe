package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import javax.lang.model.element.Element;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReferenceLocatorTest {

  private static final String URI = "file:///Test.java";

  private TempSourceCompiler compiler;

  @BeforeEach
  void setUp() {
    compiler = new TempSourceCompiler();
  }

  @AfterEach
  void tearDown() {
    compiler.close();
  }

  // --- helpers ---

  private AttributedFileAnalysis compile(final String source) {
    return compiler.compile(URI, source, CompileMode.OPEN).fileAnalysis();
  }

  private Element elementAt(
      final AttributedFileAnalysis analysis, final String context, final String token) {
    final var path = SampleFixture.pathAt(analysis.trees(), analysis.tree(), context, token);
    return SourceLocator.elementAt(analysis.trees(), path);
  }

  private List<Location> refs(
      final AttributedFileAnalysis analysis, final Element target, final boolean includeDecl)
      throws IOException {
    return ReferenceLocator.references(analysis, target, URI, includeDecl);
  }

  private static Position posOf(final String source, final String context, final String token) {
    final int from = source.indexOf(context);
    final int offset = source.indexOf(token, from);
    return SourceLocator.offsetToPosition(source, offset);
  }

  // --- fields ---

  @Nested
  class Fields {

    private static final String SOURCE =
        """
        class Test {
            String name;
            String get() { return name; }
        }
        """;

    @Test
    void identifier_reportsReadSite() throws IOException {
      final var analysis = compile(SOURCE);
      final var target = elementAt(analysis, "String name", "name");

      final List<Location> result = refs(analysis, target, false);

      assertThat(result).hasSize(1);
      assertThat(result.getFirst().getRange().getStart())
          .isEqualTo(posOf(SOURCE, "return name", "name"));
    }

    @Test
    void memberSelect_reportsNamePosition() throws IOException {
      final var source =
          """
          class Test {
              String name;
              void set(String v) { this.name = v; }
          }
          """;
      final var analysis = compile(source);
      final var target = elementAt(analysis, "String name", "name");

      final List<Location> result = refs(analysis, target, false);

      assertThat(result).hasSize(1);
      assertThat(result.getFirst().getRange().getStart())
          .isEqualTo(posOf(source, "this.name", "name"));
    }

    @Test
    void includeDeclaration_addsDeclarationSite() throws IOException {
      final var analysis = compile(SOURCE);
      final var target = elementAt(analysis, "String name", "name");

      final List<Location> withDecl = refs(analysis, target, true);
      final List<Location> withoutDecl = refs(analysis, target, false);

      assertThat(withDecl).hasSize(withoutDecl.size() + 1);
      assertThat(withDecl).anyMatch(
          l -> l.getRange().getStart().equals(posOf(SOURCE, "String name", "name")));
    }

    @Test
    void parameterWithSameName_notConfusedWithField() throws IOException {
      final var source =
          """
          class Test {
              String name;
              void set(String name) { this.name = name; }
          }
          """;
      final var analysis = compile(source);
      final var fieldTarget = elementAt(analysis, "String name;", "name");
      final var paramTarget = elementAt(analysis, "String name)", "name");

      final List<Location> fieldRefs = refs(analysis, fieldTarget, false);
      final List<Location> paramRefs = refs(analysis, paramTarget, false);

      // field: this.name — one member-select ref
      assertThat(fieldRefs).hasSize(1);
      assertThat(fieldRefs.getFirst().getRange().getStart())
          .isEqualTo(posOf(source, "this.name", "name"));
      // param: the rhs "name" in "this.name = name"
      assertThat(paramRefs).hasSize(1);
      assertThat(paramRefs.getFirst().getRange().getStart())
          .isEqualTo(posOf(source, "= name", "name"));
    }
  }

  // --- methods ---

  @Nested
  class Methods {

    private static final String SOURCE =
        """
        class Test {
            void run() {}
            void test() { run(); }
        }
        """;

    @Test
    void invocations_reportedWithoutDeclaration() throws IOException {
      final var source =
          """
          class Test {
              void run() {}
              void test() { run(); run(); }
          }
          """;
      final var analysis = compile(source);
      final var target = elementAt(analysis, "void run()", "run");

      final List<Location> result = refs(analysis, target, false);

      assertThat(result).hasSize(2);
    }

    @Test
    void overload_doesNotMatchSibling() throws IOException {
      final var source =
          """
          class Test {
              void run(String s) {}
              void run(int i) {}
              void test() { run("x"); run(1); }
          }
          """;
      final var analysis = compile(source);
      final var stringOverload = elementAt(analysis, "void run(String", "run");

      final List<Location> result = refs(analysis, stringOverload, false);

      assertThat(result).hasSize(1);
      assertThat(result.getFirst().getRange().getStart())
          .isEqualTo(posOf(source, "run(\"x\")", "run"));
    }

    @Test
    void includeDeclaration_addsMethodNameSite() throws IOException {
      final var analysis = compile(SOURCE);
      final var target = elementAt(analysis, "void run()", "run");

      final List<Location> withDecl = refs(analysis, target, true);
      final List<Location> withoutDecl = refs(analysis, target, false);

      assertThat(withDecl).hasSize(withoutDecl.size() + 1);
      assertThat(withDecl).anyMatch(
          l -> l.getRange().getStart().equals(posOf(SOURCE, "void run()", "run")));
    }
  }

  // --- types ---

  @Nested
  class Types {

    @Test
    void typeUse_reportsReturnTypeAndConstructor() throws IOException {
      final var source =
          """
          class Test {
              static class Item {}
              Item create() { return new Item(); }
          }
          """;
      final var analysis = compile(source);
      final var target = elementAt(analysis, "class Item", "Item");

      final List<Location> result = refs(analysis, target, false);

      assertThat(result).hasSize(2);
      assertThat(result).anyMatch(
          l -> l.getRange().getStart().equals(posOf(source, "Item create", "Item")));
      assertThat(result).anyMatch(
          l -> l.getRange().getStart().equals(posOf(source, "new Item()", "Item")));
    }
  }

  // --- locals ---

  @Nested
  class Locals {

    @Test
    void localVariable_reportsUsesInMethod() throws IOException {
      final var source =
          """
          class Test {
              int compute() {
                  int x = 1;
                  return x + x;
              }
          }
          """;
      final var analysis = compile(source);
      final var target = elementAt(analysis, "int x = 1", "x");

      final List<Location> result = refs(analysis, target, false);

      assertThat(result).hasSize(2);
    }
  }

  // --- edge cases ---

  @Nested
  class EdgeCases {

    @Test
    void nullElement_returnsEmpty() throws IOException {
      final var analysis = compile("class Test {}");

      final List<Location> result = ReferenceLocator.references(analysis, null, URI, false);

      assertThat(result).isEmpty();
    }
  }
}
