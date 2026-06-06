package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReferenceLocatorTest {

  private static final String URI = "file:///Test.java";
  private static final String URI_B = "file:///Other.java";

  private static final String FIELD_SOURCE =
      """
      class Test {
          String name;
          String get() { return name; }
      }
      """;

  private static final String METHOD_SOURCE =
      """
      class Test {
          void run() {}
          void test() { run(); }
      }
      """;

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
    return compile(URI, source);
  }

  private AttributedFileAnalysis compile(final String uri, final String source) {
    return compiler.compile(uri, source, CompileMode.OPEN).fileAnalysis();
  }

  private ReferenceTarget targetAt(
      final AttributedFileAnalysis analysis, final String context, final String token) {
    final var path = SampleFixture.pathAt(analysis.trees(), analysis.tree(), context, token);
    final var element = Objects.requireNonNull(SourceLocator.elementAt(analysis.trees(), path));
    return ReferenceTarget.from(element, analysis.types(), analysis.elements());
  }

  private List<Location> refs(
      final AttributedFileAnalysis analysis,
      final ReferenceTarget target,
      final boolean includeDecl)
      throws IOException {
    return ReferenceLocator.references(analysis, target, URI, includeDecl);
  }

  private static Position posOf(final String source, final String context, final String token) {
    final int from = source.indexOf(context);
    final int offset = source.indexOf(token, from);
    return SourceLocator.offsetToPosition(source, offset);
  }

  // --- fields ---

  @Test
  void field_identifier_reportsReadSite() throws IOException {
    final var analysis = compile(FIELD_SOURCE);
    final var target = targetAt(analysis, "String name", "name");

    final List<Location> result = refs(analysis, target, false);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().getRange().getStart())
        .isEqualTo(posOf(FIELD_SOURCE, "return name", "name"));
  }

  @Test
  void field_memberSelect_reportsNamePosition() throws IOException {
    final var source =
        """
        class Test {
            String name;
            void set(String v) { this.name = v; }
        }
        """;
    final var analysis = compile(source);
    final var target = targetAt(analysis, "String name", "name");

    final List<Location> result = refs(analysis, target, false);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().getRange().getStart())
        .isEqualTo(posOf(source, "this.name", "name"));
  }

  @Test
  void field_includeDeclaration_addsDeclarationSite() throws IOException {
    final var analysis = compile(FIELD_SOURCE);
    final var target = targetAt(analysis, "String name", "name");

    final List<Location> withDecl = refs(analysis, target, true);
    final List<Location> withoutDecl = refs(analysis, target, false);

    assertThat(withDecl).hasSize(withoutDecl.size() + 1);
    assertThat(withDecl)
        .anyMatch(l -> l.getRange().getStart().equals(posOf(FIELD_SOURCE, "String name", "name")));
  }

  @Test
  void field_parameterWithSameName_notConfusedWithField() throws IOException {
    final var source =
        """
        class Test {
            String name;
            void set(String name) { this.name = name; }
        }
        """;
    final var analysis = compile(source);
    final var fieldTarget = targetAt(analysis, "String name;", "name");
    final var paramTarget = targetAt(analysis, "String name)", "name");

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

  // --- methods ---

  @Test
  void method_invocations_reportedWithoutDeclaration() throws IOException {
    final var source =
        """
        class Test {
            void run() {}
            void test() { run(); run(); }
        }
        """;
    final var analysis = compile(source);
    final var target = targetAt(analysis, "void run()", "run");

    final List<Location> result = refs(analysis, target, false);

    assertThat(result).hasSize(2);
  }

  @Test
  void method_overload_doesNotMatchSibling() throws IOException {
    final var source =
        """
        class Test {
            void run(String s) {}
            void run(int i) {}
            void test() { run("x"); run(1); }
        }
        """;
    final var analysis = compile(source);
    final var stringOverload = targetAt(analysis, "void run(String", "run");

    final List<Location> result = refs(analysis, stringOverload, false);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().getRange().getStart())
        .isEqualTo(posOf(source, "run(\"x\")", "run"));
  }

  @Test
  void method_includeDeclaration_addsMethodNameSite() throws IOException {
    final var analysis = compile(METHOD_SOURCE);
    final var target = targetAt(analysis, "void run()", "run");

    final List<Location> withDecl = refs(analysis, target, true);
    final List<Location> withoutDecl = refs(analysis, target, false);

    assertThat(withDecl).hasSize(withoutDecl.size() + 1);
    assertThat(withDecl)
        .anyMatch(l -> l.getRange().getStart().equals(posOf(METHOD_SOURCE, "void run()", "run")));
  }

  // --- imports ---

  @Test
  void import_reportsTypeNamePosition_notIntermediatePackageSegments() throws IOException {
    final var source =
        """
        import java.util.ArrayList;
        class Test {
            ArrayList<String> x;
        }
        """;
    final var analysis = compile(source);
    final var target = targetAt(analysis, "ArrayList<String>", "ArrayList");

    final List<Location> result = refs(analysis, target, false);

    // import + field type = 2; "util" must not appear as a third false-positive hit
    assertThat(result).hasSize(2);
    assertThat(result)
        .anyMatch(
            l -> l.getRange().getStart().equals(posOf(source, "java.util.ArrayList", "ArrayList")));
    assertThat(result)
        .noneMatch(l -> l.getRange().getStart().equals(posOf(source, "java.util", "util")));
  }

  // --- types ---

  @Test
  void type_reportsReturnTypeAndConstructor() throws IOException {
    final var source =
        """
        class Test {
            static class Item {}
            Item create() { return new Item(); }
        }
        """;
    final var analysis = compile(source);
    final var target = targetAt(analysis, "class Item", "Item");

    final List<Location> result = refs(analysis, target, false);

    assertThat(result).hasSize(2);
    assertThat(result)
        .anyMatch(l -> l.getRange().getStart().equals(posOf(source, "Item create", "Item")));
    assertThat(result)
        .anyMatch(l -> l.getRange().getStart().equals(posOf(source, "new Item()", "Item")));
  }

  // --- locals ---

  @Test
  void local_reportsUsesInMethod() throws IOException {
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
    final var target = targetAt(analysis, "int x = 1", "x");

    final List<Location> result = refs(analysis, target, false);

    assertThat(result).hasSize(2);
  }

  // --- disk search (searchReferences on uncached file) ---

  @Test
  void searchReferences_compilesUncachedFile() {
    final var analysis = compile(URI, METHOD_SOURCE);
    final var target = targetAt(analysis, "void run()", "run");

    try (final var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      final List<Location> results = session.searchReferences(URI, METHOD_SOURCE, 0, target, false);

      assertThat(results).hasSize(1);
    }
  }

  // --- cross-file ---

  @Test
  void crossFile_target_matchesEquivalentElementInSeparateCompilation() throws IOException {
    final var source =
        """
        class Test {
            void run() {}
            void test() { run(); }
        }
        """;
    final var analysisA = compile(URI, source);
    final var target = targetAt(analysisA, "void run()", "run");

    final var analysisB = compile(URI_B, source);
    final List<Location> results = ReferenceLocator.references(analysisB, target, URI_B, false);

    assertThat(results).hasSize(1);
  }

  // --- search scope ---

  @Test
  void searchScope_packagePrivateField_declaringModule() {
    final var analysis = compile(FIELD_SOURCE);
    final var target = targetAt(analysis, "String name", "name");
    assertThat(target.scope()).isEqualTo(ReferenceTarget.SearchScope.DECLARING_MODULE);
  }

  @Test
  void searchScope_publicMethod_reactorModules() {
    final var analysis = compile("class Test { public void run() {} }");
    final var target = targetAt(analysis, "public void run()", "run");
    assertThat(target.scope()).isEqualTo(ReferenceTarget.SearchScope.REACTOR_MODULES);
  }

  @Test
  void searchScope_protectedMethod_reactorModules() {
    final var analysis = compile("class Test { protected void run() {} }");
    final var target = targetAt(analysis, "protected void run()", "run");
    assertThat(target.scope()).isEqualTo(ReferenceTarget.SearchScope.REACTOR_MODULES);
  }

  @Test
  void searchScope_privateField_declaringModule() {
    final var analysis = compile("class Test { private String x; }");
    final var target = targetAt(analysis, "private String x", "x");
    assertThat(target.scope()).isEqualTo(ReferenceTarget.SearchScope.DECLARING_MODULE);
  }

  @Test
  void searchScope_localVariable_declaringModule() {
    final var analysis = compile("class Test { void run() { int x = 1; } }");
    final var target = targetAt(analysis, "int x", "x");
    assertThat(target.scope()).isEqualTo(ReferenceTarget.SearchScope.DECLARING_MODULE);
  }

  @Test
  void searchScope_publicClass_reactorModules() {
    final var analysis = compile("public class Test {}");
    final var target = targetAt(analysis, "public class Test", "Test");
    assertThat(target.scope()).isEqualTo(ReferenceTarget.SearchScope.REACTOR_MODULES);
  }

  // --- edge cases ---

  @Test
  void nullTarget_returnsEmpty() throws IOException {
    final var analysis = compile("class Test {}");

    final List<Location> result = ReferenceLocator.references(analysis, null, URI, false);

    assertThat(result).isEmpty();
  }
}
