package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReferenceLocatorTest {

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
    return compile(TempSourceCompiler.TEST_URI, source);
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

  private List<ReferenceMatch> refs(
      final AttributedFileAnalysis analysis,
      final ReferenceTarget target,
      final boolean includeDecl)
      throws IOException {
    return ReferenceLocator.references(analysis, target, TempSourceCompiler.TEST_URI, includeDecl);
  }

  private static Position posOf(final String source, final String context, final String token) {
    final int from = source.indexOf(context);
    final int offset = source.indexOf(token, from);
    return SourceLocator.offsetToPosition(source, offset);
  }

  private static SourceFeatureRequest requestAt() {
    return new SourceFeatureRequest(
        TempSourceCompiler.TEST_URI,
        ReferenceLocatorTest.METHOD_SOURCE,
        posOf(ReferenceLocatorTest.METHOD_SOURCE, "run();", "run"),
        List.of(),
        WorkspaceManifest.empty());
  }

  // --- fields ---

  @Test
  void field_identifier_reportsReadSite() throws IOException {
    final var analysis = compile(FIELD_SOURCE);
    final var target = targetAt(analysis, "String name", "name");

    final List<ReferenceMatch> result = refs(analysis, target, false);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().range().getStart())
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

    final List<ReferenceMatch> result = refs(analysis, target, false);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().range().getStart()).isEqualTo(posOf(source, "this.name", "name"));
  }

  @Test
  void field_includeDeclaration_addsDeclarationSite() throws IOException {
    final var analysis = compile(FIELD_SOURCE);
    final var target = targetAt(analysis, "String name", "name");

    final List<ReferenceMatch> withDecl = refs(analysis, target, true);
    final List<ReferenceMatch> withoutDecl = refs(analysis, target, false);

    assertThat(withDecl).hasSize(withoutDecl.size() + 1);
    assertThat(withDecl)
        .anyMatch(l -> l.range().getStart().equals(posOf(FIELD_SOURCE, "String name", "name")));
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

    final List<ReferenceMatch> fieldRefs = refs(analysis, fieldTarget, false);
    final List<ReferenceMatch> paramRefs = refs(analysis, paramTarget, false);

    // field: this.name — one member-select ref
    assertThat(fieldRefs).hasSize(1);
    assertThat(fieldRefs.getFirst().range().getStart())
        .isEqualTo(posOf(source, "this.name", "name"));
    // param: the rhs "name" in "this.name = name"
    assertThat(paramRefs).hasSize(1);
    assertThat(paramRefs.getFirst().range().getStart()).isEqualTo(posOf(source, "= name", "name"));
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

    final List<ReferenceMatch> result = refs(analysis, target, false);

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

    final List<ReferenceMatch> result = refs(analysis, stringOverload, false);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().range().getStart()).isEqualTo(posOf(source, "run(\"x\")", "run"));
  }

  @Test
  void method_includeDeclaration_addsMethodNameSite() throws IOException {
    final var analysis = compile(METHOD_SOURCE);
    final var target = targetAt(analysis, "void run()", "run");

    final List<ReferenceMatch> withDecl = refs(analysis, target, true);
    final List<ReferenceMatch> withoutDecl = refs(analysis, target, false);

    assertThat(withDecl).hasSize(withoutDecl.size() + 1);
    assertThat(withDecl)
        .anyMatch(l -> l.range().getStart().equals(posOf(METHOD_SOURCE, "void run()", "run")));
  }

  @Test
  void method_interfaceDeclaration_findsCallThroughImplementingType() throws IOException {
    final var source =
        """
        interface Service {
            void handle();
        }
        class Impl implements Service {
            public void handle() {}
        }
        class Caller {
            void use(Service s, Impl i) {
                s.handle();
                i.handle();
            }
        }
        """;
    final var analysis = compile(source);
    final var target = targetAt(analysis, "void handle();", "handle");

    final List<ReferenceMatch> result = refs(analysis, target, false);

    assertThat(result).hasSize(2);
    assertThat(result)
        .anyMatch(l -> l.range().getStart().equals(posOf(source, "s.handle()", "handle")));
    assertThat(result)
        .anyMatch(l -> l.range().getStart().equals(posOf(source, "i.handle()", "handle")));
  }

  @Test
  void method_override_findsCallThroughInterfaceType() throws IOException {
    final var source =
        """
        interface Service {
            void handle();
        }
        class Impl implements Service {
            public void handle() {}
        }
        class Caller {
            void use(Service s, Impl i) {
                s.handle();
                i.handle();
            }
        }
        """;
    final var analysis = compile(source);
    final var target = targetAt(analysis, "public void handle()", "handle");

    final List<ReferenceMatch> result = refs(analysis, target, false);

    assertThat(result).hasSize(2);
    assertThat(result)
        .anyMatch(l -> l.range().getStart().equals(posOf(source, "s.handle()", "handle")));
    assertThat(result)
        .anyMatch(l -> l.range().getStart().equals(posOf(source, "i.handle()", "handle")));
  }

  @Test
  void method_interfaceDeclaration_includeDeclarationAddsOverrideDeclaration() throws IOException {
    final var source =
        """
        interface DbClient {
            String dbType();
        }
        abstract class DbClientBase implements DbClient {
        }
        class MongoDbClient extends DbClientBase implements DbClient {
            public String dbType() { return "mongo"; }
        }
        class DbClientHealthCheck {
            void use(DbClient dbClient) {
                dbClient.dbType();
            }
        }
        """;
    final var analysis = compile(source);
    final var target = targetAt(analysis, "String dbType();", "dbType");

    final List<ReferenceMatch> result = refs(analysis, target, true);

    assertThat(result)
        .anyMatch(l -> l.range().getStart().equals(posOf(source, "String dbType();", "dbType")));
    assertThat(result)
        .anyMatch(
            l -> l.range().getStart().equals(posOf(source, "public String dbType()", "dbType")));
    assertThat(result)
        .anyMatch(l -> l.range().getStart().equals(posOf(source, "dbClient.dbType()", "dbType")));
  }

  // --- constructors ---

  @Test
  void constructor_newExpression_callSiteFound() throws IOException {
    final var source =
        """
        class Box {
            Box() {}
            static Box make() { return new Box(); }
        }
        """;
    final var analysis = compile(source);
    final var target = targetAt(analysis, "Box() {}", "Box");

    final List<ReferenceMatch> result = refs(analysis, target, false);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().range().getStart()).isEqualTo(posOf(source, "new Box()", "Box"));
  }

  @Test
  void constructor_overload_doesNotMatchSibling() throws IOException {
    final var source =
        """
        class Box {
            Box() {}
            Box(String s) {}
            static void make() { new Box(); new Box("x"); }
        }
        """;
    final var analysis = compile(source);
    final var noArgTarget = targetAt(analysis, "Box() {}", "Box");

    final List<ReferenceMatch> result = refs(analysis, noArgTarget, false);

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().range().getStart()).isEqualTo(posOf(source, "new Box();", "Box"));
  }

  // --- method references ---

  @Test
  void methodReference_thisMethodRef_callSiteFound() throws IOException {
    final var source =
        """
        import java.util.function.Supplier;
        class Test {
            String value() { return "x"; }
            Supplier<String> ref() { return this::value; }
        }
        """;
    final var analysis = compile(source);
    final var target = targetAt(analysis, "String value()", "value");

    final List<ReferenceMatch> result = refs(analysis, target, false);

    assertThat(result).hasSize(1);
  }

  @Test
  void methodReference_staticMethodRef_callSiteFound() throws IOException {
    final var source =
        """
        import java.util.function.Supplier;
        class Test {
            static String produce() { return "x"; }
            Supplier<String> ref() { return Test::produce; }
        }
        """;
    final var analysis = compile(source);
    final var target = targetAt(analysis, "static String produce()", "produce");

    final List<ReferenceMatch> result = refs(analysis, target, false);

    assertThat(result).hasSize(1);
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

    final List<ReferenceMatch> result = refs(analysis, target, false);

    // import + field type = 2; "util" must not appear as a third false-positive hit
    assertThat(result).hasSize(2);
    assertThat(result)
        .anyMatch(
            l -> l.range().getStart().equals(posOf(source, "java.util.ArrayList", "ArrayList")));
    assertThat(result)
        .noneMatch(l -> l.range().getStart().equals(posOf(source, "java.util", "util")));
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

    final List<ReferenceMatch> result = refs(analysis, target, false);

    assertThat(result).hasSize(2);
    assertThat(result)
        .anyMatch(l -> l.range().getStart().equals(posOf(source, "Item create", "Item")));
    assertThat(result)
        .anyMatch(l -> l.range().getStart().equals(posOf(source, "new Item()", "Item")));
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

    final List<ReferenceMatch> result = refs(analysis, target, false);

    assertThat(result).hasSize(2);
  }

  // --- roles ---

  @Test
  void role_fieldRead_isRead() throws IOException {
    final var analysis = compile(FIELD_SOURCE);
    final var target = targetAt(analysis, "String name", "name");
    assertThat(refs(analysis, target, false).getFirst().role()).isEqualTo(ReferenceRole.READ);
  }

  @Test
  void role_fieldWrite_isWrite() throws IOException {
    final var source =
        """
        class Test {
            String name;
            void set(String v) { this.name = v; }
        }
        """;
    final var analysis = compile(source);
    final var target = targetAt(analysis, "String name", "name");
    assertThat(refs(analysis, target, false).getFirst().role()).isEqualTo(ReferenceRole.WRITE);
  }

  @Test
  void role_methodInvocation_isInvocation() throws IOException {
    final var analysis = compile(METHOD_SOURCE);
    final var target = targetAt(analysis, "void run()", "run");
    assertThat(refs(analysis, target, false)).allMatch(m -> m.role() == ReferenceRole.INVOCATION);
  }

  @Test
  void role_import_isImport() throws IOException {
    final var source =
        """
        import java.util.ArrayList;
        class Test {
            ArrayList<String> x;
        }
        """;
    final var analysis = compile(source);
    final var target = targetAt(analysis, "ArrayList<String>", "ArrayList");
    assertThat(refs(analysis, target, false))
        .anyMatch(
            m ->
                m.role() == ReferenceRole.IMPORT
                    && m.range()
                        .getStart()
                        .equals(posOf(source, "java.util.ArrayList", "ArrayList")));
  }

  @Test
  void role_declaration_isDeclaration() throws IOException {
    final var analysis = compile(FIELD_SOURCE);
    final var target = targetAt(analysis, "String name", "name");
    final var withDecl = refs(analysis, target, true);
    assertThat(withDecl)
        .anyMatch(
            m ->
                m.role() == ReferenceRole.DECLARATION
                    && m.range().getStart().equals(posOf(FIELD_SOURCE, "String name", "name")));
  }

  @Test
  void role_typeUse_isTypeUse() throws IOException {
    final var source =
        """
        class Test {
            static class Item {}
            Item create() { return new Item(); }
        }
        """;
    final var analysis = compile(source);
    final var target = targetAt(analysis, "class Item", "Item");
    assertThat(refs(analysis, target, false))
        .anyMatch(
            m ->
                m.role() == ReferenceRole.TYPE_USE
                    && m.range().getStart().equals(posOf(source, "Item create", "Item")));
  }

  @Test
  void searchReferences_uncachedOpenFile_compilesAndCachesAnalysis() {
    final var analysis = compile(TempSourceCompiler.TEST_URI, METHOD_SOURCE);
    final var target = targetAt(analysis, "void run()", "run");
    final var request = requestAt();

    try (final var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      final List<ReferenceMatch> results =
          session.searchReferences(TempSourceCompiler.TEST_URI, METHOD_SOURCE, 0, target, false);

      assertThat(results).hasSize(1);
      assertThat(session.resolveTarget(request)).isNotNull();
    }
  }

  @Test
  void searchReferencesTransient_repeatedScans_returnMatchesWithoutCaching() {
    final var analysis = compile(TempSourceCompiler.TEST_URI, METHOD_SOURCE);
    final var target = targetAt(analysis, "void run()", "run");
    final var request = requestAt();

    try (final var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      final List<ReferenceMatch> first =
          session.searchReferencesTransient(
              TempSourceCompiler.TEST_URI, METHOD_SOURCE, target, false);
      final List<ReferenceMatch> second =
          session.searchReferencesTransient(
              TempSourceCompiler.TEST_URI, METHOD_SOURCE, target, false);

      assertThat(first).hasSize(1);
      assertThat(second).isEqualTo(first);
      assertThat(session.resolveTarget(request)).isNull();
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
    final var analysisA = compile(TempSourceCompiler.TEST_URI, source);
    final var target = targetAt(analysisA, "void run()", "run");

    final var analysisB = compile(URI_B, source);
    final List<ReferenceMatch> results =
        ReferenceLocator.references(analysisB, target, URI_B, false);

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
  void searchScope_publicOverrideMethod_reactorModules() {
    final var source =
        """
        interface DbClient {
            String dbType();
        }
        class MongoDbClient implements DbClient {
            public String dbType() { return "mongo"; }
        }
        """;
    final var analysis = compile(source);
    final var target = targetAt(analysis, "public String dbType()", "dbType");
    assertThat(target.scope()).isEqualTo(ReferenceTarget.SearchScope.REACTOR_MODULES);
  }

  @Test
  void searchScope_protectedMethod_reactorModules() {
    final var analysis = compile("class Test { protected void run() {} }");
    final var target = targetAt(analysis, "protected void run()", "run");
    assertThat(target.scope()).isEqualTo(ReferenceTarget.SearchScope.REACTOR_MODULES);
  }

  @Test
  void searchScope_privateField_declaringFile() {
    final var analysis = compile("class Test { private String x; }");
    final var target = targetAt(analysis, "private String x", "x");
    assertThat(target.scope()).isEqualTo(ReferenceTarget.SearchScope.DECLARING_FILE);
  }

  @Test
  void searchScope_localVariable_declaringFile() {
    final var analysis = compile("class Test { void run() { int x = 1; } }");
    final var target = targetAt(analysis, "int x", "x");
    assertThat(target.scope()).isEqualTo(ReferenceTarget.SearchScope.DECLARING_FILE);
  }

  @Test
  void searchScope_publicClass_reactorModules() {
    final var analysis = compile("public class Test {}");
    final var target = targetAt(analysis, "public class Test", "Test");
    assertThat(target.scope()).isEqualTo(ReferenceTarget.SearchScope.REACTOR_MODULES);
  }

  // --- EG-027: out-of-range positions ---

  @Test
  void references_outOfRangePosition_returnsEmpty() {
    final var source = "class Test { void run() {} }";
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(TempSourceCompiler.TEST_URI, source, 1, CompileMode.OPEN);
      final var request =
          new SourceFeatureRequest(
              TempSourceCompiler.TEST_URI,
              source,
              new Position(9999, 0),
              List.of(),
              WorkspaceManifest.empty());
      assertThat(session.resolveTarget(request)).isNull();
    }
  }

  // --- edge cases ---

  @Test
  void nullTarget_returnsEmpty() throws IOException {
    final var analysis = compile("class Test {}");

    final List<ReferenceMatch> result =
        ReferenceLocator.references(analysis, null, TempSourceCompiler.TEST_URI, false);

    assertThat(result).isEmpty();
  }
}
